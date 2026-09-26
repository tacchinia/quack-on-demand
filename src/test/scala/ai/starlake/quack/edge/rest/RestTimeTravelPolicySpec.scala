package ai.starlake.quack.edge.rest

import ai.starlake.quack.edge.RouterFailure
import ai.starlake.quack.edge.cls.ColumnCatalog
import ai.starlake.quack.edge.sql.PostgresAclValidator
import ai.starlake.quack.ondemand.state.{RoleColumnPolicy, RolePermission, RoleRowPolicy}
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers

/** Spike S1 / test P1 of the REST edge design (spec 2026-09-25-quack-rest-data-edge-design §2.5,
  * §11.4): do the column- and row-policy rewriters keep `AT (VERSION => n)` on a three-part table
  * reference AND still apply the policy? The edge sends both its schema probe and its data
  * statement with the same `AT` id on a DuckLake tenant-db (§6.3, §6.6).
  *
  * Finding (2026-09-26): the ACL validator and the metadata filter strip the clause before parsing
  * (SqlParser.stripTimeTravelClauses), but ColumnPolicyRewriter and RowPolicyRewriter parse the raw
  * text, where jsqlparser rejects the clause. Both report a parse failure and the router refuses
  * the statement fail-closed: nothing leaks, but a principal holding any column or row policy on
  * the tenant-db cannot read at a snapshot at all. The correct expectation is written below and
  * ignored as a KNOWN GAP (ColumnPolicyRewriterSpec's precedent); the active tests pin today's
  * refusal and show that the policies do apply to the same statements without the clause.
  */
class RestTimeTravelPolicySpec extends AnyFlatSpec with Matchers:

  import RestPipelineFixture.*

  private val kc  = DuckLake
  private val Ref = s""""${kc.catalog}"."main"."customer""""
  private val At  = "AT (VERSION => 3)"

  private val maskEmail =
    RoleColumnPolicy("cp-s1", "r-1", "*", "main", "customer", "c_email", "mask", Some("'***'"))
  private val regionX =
    RoleRowPolicy("rp-s1", "r-1", "*", "main", "customer", "c_region = 'X'")

  private val columnCatalog = new ColumnCatalog.MapCatalog(
    Map((kc.catalog, "main", "customer") -> List("c_id", "c_email", "c_region"))
  )

  /** §6.2 schema probe and §6.3 data statement, with or without the snapshot clause. */
  private def probe(at: String) = s"SELECT * FROM $Ref$at LIMIT 0"
  private def data(at: String)  =
    s"""SELECT "c_id", "c_email" FROM $Ref$at WHERE "c_id" >= CAST('2' AS INTEGER) """ +
      """ORDER BY "c_id" DESC LIMIT 101 OFFSET 0"""
  private val WithAt                             = s" $At"
  private val statements: List[String => String] = List(probe, data)

  private def pipeline = harness(kc, columnCatalog)

  "a snapshot read" should "reach the node with the AT clause and the three-part name intact when no policy applies" in
    statements.foreach { st =>
      val sent = pipeline.run(st(WithAt), effWith()).fold(f => fail(f.toString), identity)
      sent should include(s"$Ref $At")
    }

  it should "pass the ACL validator on a table grant: the validator strips the clause before parsing" in {
    val validator = new PostgresAclValidator(tenantCatalogs = _ => Set(kc.catalog))
    val grant     = RolePermission("p-s1", "r-1", kc.catalog, "main", "customer", "RO")
    val h         = harness(kc, columnCatalog, validator = validator)
    statements.foreach { st =>
      h.run(st(WithAt), effWith(permissions = List(grant))) match
        case Right(sent) => sent should include(s"$Ref $At")
        case Left(f)     => fail(s"expected admitted, got $f")
    }
  }

  "the policies" should "apply to the same probe and data statement without the AT clause" in
    statements.foreach { st =>
      val masked = pipeline.run(st(""), effWith(columnPolicies = List(maskEmail)))
      masked.fold(f => fail(f.toString), identity) should include("'***'")
      val filtered = pipeline.run(st(""), effWith(rowPolicies = List(regionX)))
      filtered.fold(f => fail(f.toString), identity) should include(
        s"FROM $Ref WHERE (c_region = 'X')"
      )
    }

  it should "filter the edge's data statement by the row policy together with its own filter and order" in
    withDuckDb(kc.catalog) { conn =>
      exec(conn, s"CREATE TABLE $Ref (c_id INTEGER, c_email VARCHAR, c_region VARCHAR)")
      exec(
        conn,
        s"INSERT INTO $Ref VALUES (1, 'a', 'X'), (2, 'b', 'Y'), (3, 'c', 'X'), (4, 'd', 'X'), " +
          "(5, 'e', 'Y')"
      )
      val sent =
        pipeline
          .run(data(""), effWith(rowPolicies = List(regionX)))
          .fold(f => fail(f.toString), identity)
      // Row policy (region X) AND the edge's own c_id >= 2, ordered by c_id DESC.
      rowsOf(conn, sent).map(_.head) shouldBe List("4", "3")
    }

  // Characterization, active on purpose: when the rewriters learn the clause this test fails and
  // the ignored KNOWN GAP tests below are the ones to enable.
  "a snapshot read by a policy holder" should "currently be refused fail-closed, never forwarded unfiltered (KNOWN GAP, pinned)" in
    statements.foreach { st =>
      pipeline.run(st(WithAt), effWith(columnPolicies = List(maskEmail))) match
        case Left(RouterFailure.AccessDenied(reason)) =>
          reason should include("column policy rewrite could not parse statement")
        case other => fail(s"expected a fail-closed CLS refusal, got $other")
      pipeline.run(st(WithAt), effWith(rowPolicies = List(regionX))) match
        case Left(RouterFailure.AccessDenied(reason)) =>
          reason should include("row policy rewrite could not parse statement")
        case other => fail(s"expected a fail-closed RLS refusal, got $other")
    }

  // KNOWN GAP (ignored below): time travel under a column or row policy.
  //
  // ColumnPolicyRewriter and RowPolicyRewriter hand the raw text to jsqlparser, whose grammar has
  // no `AT (VERSION => n)` (SqlParser.stripTimeTravelClauses documents the same limit for the ACL
  // parser, which strips the clause first). Both return a parse failure and FlightSqlRouter denies.
  // The fix belongs in the rewriters (spec §2.2 constraint 1, §2.5 S1): they must keep the clause
  // on the base table reference, inside the RLS wrapper, and apply the policy. Enable when fixed.
  ignore should "keep AT (VERSION => n) on the three-part reference and still apply the column mask (KNOWN GAP, S1)" in
    statements.foreach { st =>
      pipeline.run(st(WithAt), effWith(columnPolicies = List(maskEmail))) match
        case Right(sent) =>
          sent should include(s"$Ref $At")
          sent should include("'***'")
        case Left(f) => fail(s"expected the mask applied at the snapshot, got $f")
    }

  ignore should "keep AT (VERSION => n) on the base table inside the row-policy wrapper (KNOWN GAP, S1)" in
    statements.foreach { st =>
      pipeline.run(st(WithAt), effWith(rowPolicies = List(regionX))) match
        case Right(sent) => sent should include(s"FROM $Ref $At WHERE (c_region = 'X')")
        case Left(f)     => fail(s"expected the row filter applied at the snapshot, got $f")
    }

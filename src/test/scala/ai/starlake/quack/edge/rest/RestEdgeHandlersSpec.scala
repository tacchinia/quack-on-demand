package ai.starlake.quack.edge.rest

import ai.starlake.quack.RestEdgeConfig
import ai.starlake.quack.edge.{QueryResult, RouterFailure}
import ai.starlake.quack.edge.adapter.{NodeLoadTracker, TestArrow}
import ai.starlake.quack.model.{
  PoolKey,
  RoleDistribution,
  StatementKind,
  Tenant,
  TenantDb,
  TenantDbKind
}
import ai.starlake.quack.ondemand.PoolSupervisor
import ai.starlake.quack.ondemand.api.{CatalogPreviewHandlers, ErrorResponse, ExecCaller}
import ai.starlake.quack.ondemand.auth.{PatPrincipal, SessionScope, TokenRestriction}
import ai.starlake.quack.ondemand.catalog.DuckLakeCatalogReader
import ai.starlake.quack.ondemand.runtime.testkit.StubQuackBackend
import ai.starlake.quack.ondemand.state.{InMemoryControlPlaneStore, RbacUser}
import ai.starlake.quack.route.StatementClassifier
import ch.qos.logback.classic.{Level, Logger as LogbackLogger}
import ch.qos.logback.classic.spi.ILoggingEvent
import ch.qos.logback.core.read.ListAppender
import cats.effect.IO
import cats.effect.unsafe.implicits.global
import io.circe.Json
import io.circe.parser.parse
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers
import org.slf4j.LoggerFactory
import sttp.model.{Header, StatusCode}

import java.util.concurrent.{CountDownLatch, TimeUnit}
import java.util.concurrent.atomic.AtomicInteger
import scala.collection.mutable.ListBuffer
import scala.concurrent.duration.*
import scala.jdk.CollectionConverters.*

/** The REST edge over the executor seam, without the wire: H1-H8, H10 and H12 of the design's §11.2
  * (`docs/superpowers/specs/2026-09-25-quack-rest-data-edge-design.md`).
  *
  * The executor is a recording stub in the shape of `McpDataToolsSpec.capturingExecutor`: it keeps
  * every `(caller, poolKey, sql)` it is handed and answers canned Arrow data built with
  * [[TestArrow]], keyed on the statement's shape (probe, listing, data) and its three-part name.
  * The supervisor is the in-memory one the MCP specs use; the PAT resolver is a map.
  */
class RestEdgeHandlersSpec extends AnyFlatSpec with Matchers:

  // ---- principals ----------------------------------------------------------------------------

  private val Token      = "qod_pat_alice"
  private val SuperToken = "qod_pat_root"
  private val OtherToken = "qod_pat_bob"

  private def principal(tenant: Option[String], r: TokenRestriction, patId: String) =
    PatPrincipal(
      user = RbacUser(s"u-$patId", tenant, "alice", "user"),
      patId = patId,
      scope = SessionScope(superuser = tenant.isEmpty, manageableTenants = Set.empty),
      isAdmin = tenant.isEmpty,
      restriction = r
    )

  // ---- control plane -------------------------------------------------------------------------

  private lazy val sup: PoolSupervisor =
    val s = new PoolSupervisor(
      StubQuackBackend.noop(),
      new NodeLoadTracker,
      new InMemoryControlPlaneStore(),
      duckLakeInitializer = (_, _) => ()
    )
    def ok[A](io: IO[Either[?, A]]): A =
      io.unsafeRunSync().fold(e => throw new IllegalStateException(e.toString), identity)
    List("acme", "globex").foreach(t => ok(s.createTenant(Tenant(t))))
    val lakeMeta = Map(
      "pgHost"     -> "localhost",
      "pgPort"     -> "5432",
      "pgUser"     -> "postgres",
      "pgPassword" -> "unused",
      "schemaName" -> "main"
    )
    val lake = ok(
      s.createTenantDb("acme", "lake", TenantDbKind.DuckLake, lakeMeta, "/tmp/qod-rest-h/lake")
    )
    ok(
      s.createTenantDb(
        "acme",
        "file",
        TenantDbKind.DuckDbFile,
        Map("dbName" -> "acme_file", "schemaName" -> "main"),
        "/tmp/qod-rest-h/file"
      )
    )
    ok(s.createTenantDb("acme", "mem", TenantDbKind.InMemory, Map.empty, ""))
    ok(s.createTenantDb("acme", "vault", TenantDbKind.InMemory, Map.empty, ""))
    s.registerBranchTenantDb(
      TenantDb(
        id = "td-branch",
        tenantId = lake.tenantId,
        name = "acme_lake__br_0123abcd",
        kind = TenantDbKind.DuckLake,
        metastore = lakeMeta + ("catalogAlias" -> "acme_lake"),
        dataPath = "/tmp/qod-rest-h/lake__br_0123abcd",
        branchOf = Some(lake.id)
      )
    ).fold(e => throw new IllegalStateException(e.toString), identity)
    List(
      PoolKey("acme", "acme_lake", "sales"),
      PoolKey("acme", "acme_lake", "batch"),
      PoolKey("acme", "acme_file", "fpool"),
      PoolKey("acme", "acme_mem", "mpool"),
      PoolKey("acme", "acme_vault", "vpool"),
      PoolKey("acme", "acme_lake__br_0123abcd", "__br_0123abcd")
    ).foreach(k => s.createPool(k, RoleDistribution(0, 0, 1)).unsafeRunSync())
    s

  /** DuckLake snapshots: 2..7 exist, the tag `v1` points at 4, any timestamp resolves to 5. */
  private val reader: DuckLakeCatalogReader = new DuckLakeCatalogReader(null):
    override def maxSnapshotId(): Option[Long]                           = Some(7L)
    override def snapshotExists(id: Long): Boolean                       = id >= 2 && id <= 7
    override def snapshotAtOrBefore(ts: java.time.Instant): Option[Long] = Some(5L)

  private val tags: (String, String, String) => Option[Long] =
    (_, _, tag) => Option.when(tag == "v1")(4L)

  // ---- executor stub -------------------------------------------------------------------------

  final case class Call(caller: ExecCaller, poolKey: PoolKey, sql: String)

  private type Responder = String => IO[Either[RouterFailure, QueryResult]]

  private val NameRe = "\"([^\"]+)\"\\.\"([^\"]+)\"\\.\"([^\"]+)\"".r

  /** The probe's columns per table name. `ghost` is missing, `hidden` is ungranted. */
  private val tableColumns: Map[String, String] = Map(
    "customer"   -> "1::INTEGER AS c_id, 'a' AS c_email, 'X' AS c_region",
    "v_customer" -> "1::INTEGER AS c_id, 'a' AS c_email",
    "fmt_t"      -> "1::INTEGER AS id, 'x' AS format",
    "pool_t"     -> "1::INTEGER AS id, 'x' AS pool",
    "tag_t"      -> "1::INTEGER AS id, 'x' AS \"asOfTag\"",
    "nested_t"   -> "1::INTEGER AS id, [1, 2] AS xs",
    "limit_t"    -> "1::INTEGER AS id, 5 AS \"limit\""
  )

  private def result(
      sql: String,
      closes: AtomicInteger,
      node: String = "n-data"
  ): IO[Either[RouterFailure, QueryResult]] =
    IO(Right(QueryResult(TestArrow.readerFor(sql), () => closes.incrementAndGet(): Unit, node, 1L)))

  /** Canned answers: probe -> the table's schema from node `n-probe`, listings -> fixed rows, data
    * -> `dataRows` rows of the customer shape from node `n-data`.
    */
  private def defaultResponder(closes: AtomicInteger, dataRows: Int): Responder = sql =>
    val name = NameRe.findFirstMatchIn(sql).map(_.group(3))
    if sql.contains("information_schema.schemata") then
      result("SELECT * FROM (VALUES ('main'), ('sales')) t(schema_name)", closes)
    else if sql.contains("information_schema.tables") then
      if sql.contains("'empty'") then
        result("SELECT 'x' AS table_name, 'BASE TABLE' AS table_type WHERE false", closes)
      else
        result(
          "SELECT * FROM (VALUES ('customer', 'BASE TABLE'), ('v_customer', 'VIEW')) " +
            "t(table_name, table_type)",
          closes
        )
    else if sql.startsWith("SELECT * FROM") && sql.endsWith("LIMIT 0") then
      name match
        case Some("hidden") =>
          IO.pure(Left(RouterFailure.AccessDenied("access denied: acme_lake.main.hidden")))
        case Some(n) if tableColumns.contains(n) =>
          result(s"SELECT ${tableColumns(n)} WHERE false", closes, node = "n-probe")
        case _ =>
          IO.pure(Left(RouterFailure.NotFound("permanent failure: Table does not exist")))
    else
      result(
        s"SELECT range::INTEGER AS c_id, 'e' || range AS c_email, 'X' AS c_region " +
          s"FROM range($dataRows)",
        closes
      )

  final class Fixture(val handlers: RestEdgeHandlers, val calls: ListBuffer[Call]):
    val closes = new AtomicInteger()

  private val baseCfg = RestEdgeConfig(
    enabled = true,
    host = "127.0.0.1",
    port = 0,
    tlsEnabled = false,
    tlsCertChain = "",
    tlsPrivateKey = "",
    defaultLimit = 1000,
    maxRows = 100000,
    stmtTimeoutSec = 60,
    maxConnections = 16,
    maxHeaderBytes = 16384,
    headerReceiveTimeoutSec = 10,
    idleTimeoutSec = 60
  )

  private def fixture(
      restriction: TokenRestriction = TokenRestriction.Unrestricted,
      cfg: RestEdgeConfig = baseCfg,
      dataRows: Int = 3,
      respond: Option[AtomicInteger => Responder] = None,
      stmtTimeout: Option[FiniteDuration] = None
  ): Fixture =
    val calls                           = ListBuffer.empty[Call]
    val pats: Map[String, PatPrincipal] = Map(
      Token      -> principal(Some("acme"), restriction, "pat-1"),
      SuperToken -> principal(None, TokenRestriction.Unrestricted, "pat-root"),
      OtherToken -> principal(Some("globex"), TokenRestriction.Unrestricted, "pat-bob")
    )
    var fx: Fixture                                      = null
    val executor: CatalogPreviewHandlers.PreviewExecutor = (caller, key, sql) =>
      IO(calls.synchronized(calls += Call(caller, key, sql))) *>
        respond.fold(defaultResponder(fx.closes, dataRows))(_(fx.closes))(sql)
    fx = new Fixture(
      new RestEdgeHandlers(
        cfg,
        sup,
        pats.get,
        executor,
        (_, _) => reader,
        tags,
        stmtTimeout = stmtTimeout
      ),
      calls
    )
    fx

  // ---- request helpers -----------------------------------------------------------------------

  private def req(
      query: String = "",
      auth: List[String] = List(s"Bearer $Token"),
      accept: Option[String] = None
  ) = RestRequest(auth, accept, query, "rid-0001")

  private type Out = Either[(StatusCode, List[Header], ErrorResponse), RestOk]

  private def rows(fx: Fixture, table: String, query: String = "", db: String = "acme_lake") =
    fx.handlers.rows("acme", db, "main", table, req(query)).unsafeRunSync()

  private def okOf(out: Out): RestOk = out.fold(e => fail(s"expected 200, got $e"), identity)

  private def errOf(out: Out): (StatusCode, List[Header], ErrorResponse) =
    out.fold(identity, ok => fail(s"expected an error, got ${ok.body}"))

  private def header(hs: List[Header], name: String): Option[String] =
    hs.find(_.name.equalsIgnoreCase(name)).map(_.value)

  private def json(body: String): Json = parse(body).fold(e => fail(e.toString), identity)

  private def dataSql(fx: Fixture): String = fx.calls.last.sql

  // ---- H1: happy path ------------------------------------------------------------------------

  "the rows endpoint" should "serve JSON built by one probe and one data statement" in {
    val fx  = fixture()
    val out = okOf(rows(fx, "customer"))
    json(out.body).asArray.map(_.size) shouldBe Some(3)
    header(out.headers, "Content-Type") shouldBe Some("application/json")
    fx.calls.size shouldBe 2
    fx.calls.head.sql should endWith("LIMIT 0")
    fx.calls.last.sql should startWith("SELECT \"c_id\", \"c_email\", \"c_region\" FROM")
  }

  it should "hand the executor a rest caller for the PAT, never an unrestricted one" in {
    val r  = TokenRestriction.Unrestricted.copy(maxRows = Some(500))
    val fx = fixture(restriction = r)
    okOf(rows(fx, "customer"))
    fx.calls.foreach { c =>
      c.caller.source shouldBe "rest"
      c.caller.patId shouldBe Some("pat-1")
      c.caller.connectionId shouldBe "rest-pat-1"
      c.caller.identity shouldBe "alice"
      c.caller.restriction shouldBe r
      c.caller.restriction should not be TokenRestriction.Unrestricted
    }
  }

  it should "send the probe and the data statement with the same AT id, echoed in X-QoD-Snapshot" in {
    val fx  = fixture()
    val out = okOf(rows(fx, "customer"))
    fx.calls.map(_.sql).foreach(_ should include("AT (VERSION => 7)"))
    header(out.headers, "X-QoD-Snapshot") shouldBe Some("7")
    val pinned = fixture()
    val out2   = okOf(rows(pinned, "customer", "asOf=3"))
    pinned.calls.map(_.sql).foreach(_ should include("AT (VERSION => 3)"))
    header(out2.headers, "X-QoD-Snapshot") shouldBe Some("3")
    val tagged = fixture()
    header(okOf(rows(tagged, "customer", "asOfTag=v1")).headers, "X-QoD-Snapshot") shouldBe
      Some("4")
  }

  it should "pin the data statement to the node that answered the probe" in {
    val fx = fixture()
    okOf(rows(fx, "customer"))
    fx.calls.head.caller.preferredNode shouldBe None
    fx.calls.last.caller.preferredNode shouldBe Some("n-probe")
  }

  it should "mark responses private per Authorization, cacheable only when asOf pins them" in {
    val current = okOf(rows(fixture(), "customer"))
    header(current.headers, "Cache-Control") shouldBe Some("private, no-cache")
    header(current.headers, "Vary") shouldBe Some("Authorization")
    List("asOf=3", "asOfTag=v1").foreach { q =>
      header(okOf(rows(fixture(), "customer", q)).headers, "Cache-Control") shouldBe
        Some("private, max-age=300")
    }
    // A timestamp resolves to whatever snapshot is current at that instant: not immutable.
    header(
      okOf(rows(fixture(), "customer", "asOfTs=2026-01-01T00:00:00Z")).headers,
      "Cache-Control"
    ) shouldBe Some("private, no-cache")
  }

  it should "serve CSV when asked by format or by Accept" in {
    val byParam = okOf(rows(fixture(), "customer", "format=csv"))
    header(byParam.headers, "Content-Type") shouldBe Some("text/csv; charset=utf-8")
    byParam.body should startWith("c_id,c_email,c_region\r\n")
    val fx       = fixture()
    val byAccept =
      fx.handlers.rows("acme", "acme_lake", "main", "customer", req(accept = Some("text/csv")))
    okOf(byAccept.unsafeRunSync()).body should startWith("c_id,")
  }

  it should "close every result exactly once" in {
    val fx = fixture()
    okOf(rows(fx, "customer"))
    fx.closes.get shouldBe 2
  }

  "the listings" should "list schemas as name objects, in JSON and CSV" in {
    val fx  = fixture()
    val out = okOf(fx.handlers.schemas("acme", "acme_lake", req()).unsafeRunSync())
    json(out.body) shouldBe Json.arr(
      Json.obj("name" -> Json.fromString("main")),
      Json.obj("name" -> Json.fromString("sales"))
    )
    fx.calls.head.sql should include("'acme_lake'")
    fx.calls.foreach(_.caller.source shouldBe "rest")
    val csv = okOf(fx.handlers.schemas("acme", "acme_lake", req("format=csv")).unsafeRunSync())
    csv.body shouldBe "name\r\nmain\r\nsales\r\n"
    fx.closes.get shouldBe 2
  }

  it should "list tables and views with their type" in {
    val out =
      okOf(fixture().handlers.tables("acme", "acme_lake", "main", req()).unsafeRunSync())
    json(out.body) shouldBe Json.arr(
      Json.obj("name" -> Json.fromString("customer"), "type"   -> Json.fromString("table")),
      Json.obj("name" -> Json.fromString("v_customer"), "type" -> Json.fromString("view"))
    )
  }

  it should "describe one table with the columns the probe returned" in {
    val fx  = fixture()
    val out =
      okOf(fx.handlers.table("acme", "acme_lake", "main", "customer", req()).unsafeRunSync())
    json(out.body) shouldBe Json.obj(
      "name"    -> Json.fromString("customer"),
      "type"    -> Json.fromString("table"),
      "columns" -> Json.arr(
        Json.obj("name" -> Json.fromString("c_id"), "type"     -> Json.fromString("INTEGER")),
        Json.obj("name" -> Json.fromString("c_email"), "type"  -> Json.fromString("VARCHAR")),
        Json.obj("name" -> Json.fromString("c_region"), "type" -> Json.fromString("VARCHAR"))
      )
    )
    header(out.headers, "X-QoD-Snapshot") shouldBe Some("7")
    fx.calls.head.sql should endWith("LIMIT 0")
  }

  it should "refuse the rows vocabulary on the listing and detail endpoints" in {
    val fx = fixture()
    List("limit=5", "c_id=eq.1", "select=c_id").foreach { q =>
      errOf(
        fx.handlers.table("acme", "acme_lake", "main", "customer", req(q)).unsafeRunSync()
      )._3.error shouldBe "invalid_parameter"
      errOf(fx.handlers.schemas("acme", "acme_lake", req(q)).unsafeRunSync())._3.error shouldBe
        "invalid_parameter"
    }
    fx.calls shouldBe empty
  }

  // ---- H2: tools axis ------------------------------------------------------------------------

  "the tools axis" should "admit no restriction and a list naming rest, and refuse any other" in {
    def withTools(t: Option[Set[String]]) =
      fixture(TokenRestriction.Unrestricted.copy(tools = t))
    rows(withTools(None), "customer").isRight shouldBe true
    rows(withTools(Some(Set("rest"))), "customer").isRight shouldBe true
    val refused = withTools(Some(Set("run_sql")))
    val e       = errOf(rows(refused, "customer"))
    (e._1, e._3.error) shouldBe (StatusCode.Forbidden, "forbidden")
    refused.calls shouldBe empty
  }

  it should "admit a branchOnly token, whose every statement classifies as a SELECT" in {
    val fx = fixture(TokenRestriction.Unrestricted.copy(branchOnly = true))
    okOf(rows(fx, "customer", "c_region=eq.X&order=c_id.desc&limit=2"))
    fx.calls.toList.map(c => StatementClassifier.classify(c.sql)).distinct shouldBe
      List(StatementKind.Select)
  }

  // ---- H3: credentials -----------------------------------------------------------------------

  "the credentials" should "answer a superuser PAT byte-identically to a garbage token" in {
    val fx                          = fixture()
    def with401(auth: List[String]) =
      errOf(
        fx.handlers.rows("acme", "acme_lake", "main", "customer", req(auth = auth)).unsafeRunSync()
      )
    val superuser = with401(List(s"Bearer $SuperToken"))
    superuser shouldBe with401(List("Bearer qod_pat_garbage"))
    superuser._1 shouldBe StatusCode.Unauthorized
    List(
      Nil,
      List("Basic YWxpY2U6c2VjcmV0"),
      List(s"Bearer $Token", s"Bearer $Token"),
      List("Bearer eyJhbGciOiJIUzI1NiJ9.e30.sig")
    ).foreach(a => with401(a) shouldBe superuser)
    fx.calls shouldBe empty
  }

  it should "ignore ?access_token= entirely" in {
    val fx = fixture()
    val e  = errOf(
      fx.handlers
        .rows("acme", "acme_lake", "main", "customer", req(s"access_token=$Token", auth = Nil))
        .unsafeRunSync()
    )
    e._1 shouldBe StatusCode.Unauthorized
  }

  // ---- H4: tenant ----------------------------------------------------------------------------

  "the tenant binding" should "answer another tenant's path and an unknown tenant with one 403" in {
    val fx      = fixture()
    val other   = errOf(fx.handlers.rows("globex", "globex_x", "main", "t", req()).unsafeRunSync())
    val unknown = errOf(fx.handlers.rows("nobody", "nobody_x", "main", "t", req()).unsafeRunSync())
    other shouldBe unknown
    (other._1, other._3.error) shouldBe (StatusCode.Forbidden, "forbidden")
    other._3.message shouldBe "your token is scoped to tenant 'acme'"
    val bob = errOf(
      fx.handlers
        .rows("acme", "acme_lake", "main", "customer", req(auth = List(s"Bearer $OtherToken")))
        .unsafeRunSync()
    )
    bob._1 shouldBe StatusCode.Forbidden
    fx.calls shouldBe empty
  }

  // ---- H5: identical 404s --------------------------------------------------------------------

  "a missing object and an ungranted one" should "get identical 404s after the same calls" in {
    val missing = fixture()
    val hidden  = fixture()
    val a       = errOf(rows(missing, "ghost"))
    val b       = errOf(rows(hidden, "hidden"))
    a shouldBe b
    (a._1, a._3.error) shouldBe (StatusCode.NotFound, "not_found")
    missing.calls.size shouldBe hidden.calls.size
    missing.calls.size shouldBe 1
    val d1 =
      errOf(missing.handlers.table("acme", "acme_lake", "main", "ghost", req()).unsafeRunSync())
    val d2 =
      errOf(hidden.handlers.table("acme", "acme_lake", "main", "hidden", req()).unsafeRunSync())
    d1 shouldBe d2
    d1 shouldBe a
  }

  it should "cover a missing database, one outside the databases axis and a branch" in {
    val scoped = fixture(TokenRestriction.Unrestricted.copy(databases = Some(Set("acme_lake"))))
    val out    = List(
      rows(scoped, "customer", db = "acme_nope"),
      rows(scoped, "customer", db = "acme_vault"),
      rows(fixture(), "customer", db = "acme_lake__br_0123abcd"),
      rows(fixture(), "customer", db = "acme_nope")
    ).map(errOf)
    out.distinct.size shouldBe 1
    out.head._1 shouldBe StatusCode.NotFound
    scoped.calls shouldBe empty
  }

  it should "cover a schema with nothing visible in it" in {
    val fx = fixture()
    val e  = errOf(fx.handlers.tables("acme", "acme_lake", "empty", req()).unsafeRunSync())
    e shouldBe errOf(rows(fixture(), "ghost"))
  }

  "a missing tag" should "get the plain 404, never echoing the tag" in {
    val e = errOf(rows(fixture(), "customer", "asOfTag=secret_release_tag"))
    (e._1, e._3.error) shouldBe (StatusCode.NotFound, "not_found")
    e._3.message should not include "secret_release_tag"
    e shouldBe errOf(rows(fixture(), "ghost"))
  }

  // ---- H6: path segments ---------------------------------------------------------------------

  "a path segment outside the identifier rule" should "404 before the executor sees anything" in {
    val fx     = fixture()
    val bad    = List("..", "a/b", "a\"b", "x" * 64, "", "a b", "ümlaut")
    val system = List("information_schema", "pg_catalog", "PG_CATALOG")
    (bad ++ system).foreach { seg =>
      withClue(seg) {
        errOf(
          fx.handlers.rows("acme", "acme_lake", seg, "customer", req()).unsafeRunSync()
        )._1 shouldBe
          StatusCode.NotFound
        errOf(fx.handlers.tables("acme", "acme_lake", seg, req()).unsafeRunSync())._1 shouldBe
          StatusCode.NotFound
      }
    }
    // The system names are never valid SCHEMA segments; as a table name they are ordinary.
    bad.foreach { seg =>
      withClue(seg) {
        errOf(fx.handlers.rows("acme", "acme_lake", "main", seg, req()).unsafeRunSync())._1 shouldBe
          StatusCode.NotFound
      }
    }
    List("..", "INFORMATION_SCHEMA").foreach(db =>
      errOf(fx.handlers.schemas("acme", db, req()).unsafeRunSync())._1 shouldBe StatusCode.NotFound
    )
    fx.calls shouldBe empty
  }

  // ---- H7: limits ----------------------------------------------------------------------------

  "the row cap" should "be the smallest of the server cap, the token's cap and limit" in {
    val cfg = baseCfg.copy(maxRows = 100, defaultLimit = 50)
    val fx  = fixture(TokenRestriction.Unrestricted.copy(maxRows = Some(20)), cfg, dataRows = 21)
    val out = okOf(rows(fx, "customer", "limit=50"))
    dataSql(fx) should endWith("LIMIT 21 OFFSET 0")
    json(out.body).asArray.map(_.size) shouldBe Some(20)
    header(out.headers, "X-QoD-Truncated") shouldBe Some("true")
    header(out.headers, "Content-Range") shouldBe Some("0-19/*")
  }

  it should "not flag truncation when the client's own limit cut the page" in {
    val fx  = fixture(dataRows = 11)
    val out = okOf(rows(fx, "customer", "limit=10&order=c_id&offset=5"))
    dataSql(fx) should endWith("LIMIT 11 OFFSET 5")
    header(out.headers, "X-QoD-Truncated") shouldBe None
    header(out.headers, "Content-Range") shouldBe Some("5-14/*")
  }

  it should "flag truncation when the default limit cut the page" in {
    val fx  = fixture(cfg = baseCfg.copy(defaultLimit = 5), dataRows = 6)
    val out = okOf(rows(fx, "customer"))
    dataSql(fx) should endWith("LIMIT 6 OFFSET 0")
    header(out.headers, "X-QoD-Truncated") shouldBe Some("true")
  }

  it should "answer an empty page with Content-Range */*" in {
    val out = okOf(rows(fixture(dataRows = 0), "customer"))
    out.body shouldBe "[]"
    header(out.headers, "Content-Range") shouldBe Some("*/*")
  }

  it should "cap the listings too, flagging a cut listing" in {
    val fx  = fixture(cfg = baseCfg.copy(maxRows = 1, defaultLimit = 1))
    val out = okOf(fx.handlers.schemas("acme", "acme_lake", req()).unsafeRunSync())
    json(out.body).asArray.map(_.size) shouldBe Some(1)
    header(out.headers, "X-QoD-Truncated") shouldBe Some("true")
  }

  "a page past the first" should "require an order" in {
    val fx = fixture()
    errOf(rows(fx, "customer", "offset=10"))._3.error shouldBe "order_required"
    fx.calls shouldBe empty
  }

  "time travel on a database that is not DuckLake" should "get invalid_kind and no AT" in
    List("acme_file", "acme_mem").foreach { db =>
      val fx = fixture()
      errOf(rows(fx, "customer", "asOf=3", db = db))._3.error shouldBe "invalid_kind"
      fx.calls shouldBe empty
      val plain = fixture()
      val out   = okOf(rows(plain, "customer", db = db))
      plain.calls.map(_.sql).foreach(_ should not include "AT (")
      header(out.headers, "X-QoD-Snapshot") shouldBe None
    }

  it should "qualify memory tables with the built-in memory catalog" in {
    val fx = fixture()
    okOf(rows(fx, "customer", db = "acme_mem"))
    fx.calls.head.sql shouldBe "SELECT * FROM \"memory\".\"main\".\"customer\" LIMIT 0"
  }

  "the pool parameter" should "pick a named pool of the database and refuse any other" in {
    val fx = fixture()
    okOf(rows(fx, "customer", "pool=batch"))
    fx.calls.map(_.poolKey.pool).distinct shouldBe List("batch")
    errOf(rows(fx, "customer", "pool=fpool"))._1 shouldBe StatusCode.NotFound
    errOf(rows(fx, "customer", "pool=nope"))._1 shouldBe StatusCode.NotFound
  }

  it should "refuse a pool outside the token's pools axis with acl_denied" in {
    val fx = fixture(TokenRestriction.Unrestricted.copy(pools = Some(Set("batch"))))
    val e  = errOf(rows(fx, "customer", "pool=sales"))
    (e._1, e._3.error) shouldBe (StatusCode.Forbidden, "acl_denied")
    fx.calls shouldBe empty
  }

  // ---- reserved parameters that look like filters (Q6, §6.1) ---------------------------------

  "a filter-shaped reserved parameter" should "end as reserved_column when the table has that column" in {
    List("fmt_t" -> "format=eq.csv", "pool_t" -> "pool=eq.x", "limit_t" -> "limit=eq.5")
      .foreach { case (table, q) =>
        withClue(q)(errOf(rows(fixture(), table, q))._3.error shouldBe "reserved_column")
      }
    errOf(rows(fixture(), "tag_t", "asOfTag=eq.v", db = "acme_file"))._3.error shouldBe
      "reserved_column"
  }

  it should "end as the parameter's own error, after the probe, when the table has no such column" in {
    val fmt = fixture()
    errOf(rows(fmt, "customer", "format=eq.csv"))._1 shouldBe StatusCode.NotAcceptable
    fmt.calls.size shouldBe 1
    errOf(rows(fixture(), "customer", "pool=eq.x"))._1 shouldBe StatusCode.NotFound
    errOf(rows(fixture(), "customer", "asOfTag=eq.v", db = "acme_file"))._3.error shouldBe
      "invalid_kind"
    errOf(rows(fixture(), "customer", "asOfTag=eq.v"))._1 shouldBe StatusCode.NotFound
  }

  it should "leave an order that is not filter-shaped an ordinary order" in {
    val fx = fixture()
    okOf(rows(fx, "customer", "order=c_id.desc"))
    dataSql(fx) should include("ORDER BY \"c_id\" DESC")
  }

  // ---- H8: timeouts and resuming pools -------------------------------------------------------

  "a statement past the timeout" should "get 504 and have its late result closed exactly once" in {
    val release                          = new CountDownLatch(1)
    val closes                           = new AtomicInteger()
    val slow: AtomicInteger => Responder = _ =>
      sql =>
        IO.blocking(release.await(10, TimeUnit.SECONDS)) *>
          IO(
            Right(
              QueryResult(TestArrow.oneRowReader(), () => closes.incrementAndGet(): Unit, "n", 1L)
            )
          )
    val fx = fixture(respond = Some(slow), stmtTimeout = Some(200.millis))
    val e  = errOf(rows(fx, "customer"))
    (e._1, e._3.error) shouldBe (StatusCode.GatewayTimeout, "statement_timeout")
    closes.get shouldBe 0
    release.countDown()
    val deadline = System.nanoTime() + 5.seconds.toNanos
    while closes.get == 0 && System.nanoTime() < deadline do Thread.sleep(20)
    Thread.sleep(200)
    closes.get shouldBe 1
  }

  it should "take the token's stmtTimeoutMs when it is the smaller bound" in {
    val slow: AtomicInteger => Responder =
      _ => _ => IO.sleep(5.seconds) *> IO.pure(Left(RouterFailure.Internal("never")))
    val fx = fixture(
      TokenRestriction.Unrestricted.copy(stmtTimeoutMs = Some(150)),
      respond = Some(slow)
    )
    val start = System.nanoTime()
    errOf(rows(fx, "customer"))._1 shouldBe StatusCode.GatewayTimeout
    (System.nanoTime() - start).nanos should be < 3.seconds
  }

  "a resuming pool" should "get 503 pool_resuming with Retry-After" in {
    val resuming: AtomicInteger => Responder =
      _ => _ => IO.pure(Left(RouterFailure.Unavailable("pool is resuming, retry shortly")))
    val e = errOf(rows(fixture(respond = Some(resuming)), "customer"))
    (e._1, e._3.error) shouldBe (StatusCode.ServiceUnavailable, "pool_resuming")
    header(e._2, "Retry-After") shouldBe Some("5")
  }

  // ---- H10: logs -----------------------------------------------------------------------------

  private def capturingLogs[A](body: => A): (A, List[String]) =
    val logger   = LoggerFactory.getLogger(classOf[RestEdgeHandlers]).asInstanceOf[LogbackLogger]
    val appender = new ListAppender[ILoggingEvent]
    appender.start()
    val before = logger.getLevel
    logger.setLevel(Level.ALL)
    logger.addAppender(appender)
    try
      val a = body
      (
        a,
        appender.list.asScala.toList.map(e =>
          e.getFormattedMessage + String.valueOf(e.getThrowableProxy)
        )
      )
    finally
      logger.detachAppender(appender)
      logger.setLevel(before)

  "the logs and bodies" should "never carry the token or a parameter value" in {
    val fx            = fixture()
    val (outs, lines) = capturingLogs {
      List(
        rows(fx, "customer", "c_email=bogus.secret@x.io"),
        rows(fx, "customer", "c_email=eq.secret@x.io&limit=secret"),
        rows(fx, "customer", "c_email=eq.secret@x.io")
      )
    }
    lines should not be empty
    val bodies = outs.map(_.fold(_._3.message, _.body))
    (lines ++ bodies.take(2)).foreach { l =>
      l should not include "secret"
      l should not include Token
    }
  }

  it should "sanitise hostile parameter names" in {
    val fx           = fixture()
    val (out, lines) = capturingLogs(rows(fx, "customer", "%01evil%0Aname=eq.1"))
    errOf(out)._3.message should include("?evil?name")
    lines.foreach(_ should not include "\n")
    lines.mkString should include("?evil?name")
  }

  // ---- H12: upstream errors ------------------------------------------------------------------

  "an upstream failure" should "get 502 with the request id and never the node's text" in {
    val secret = "password=hunter2 at node 10.0.0.7"
    List[AtomicInteger => Responder](
      _ => _ => IO.pure(Left(RouterFailure.Internal(secret))),
      _ => _ => IO.raiseError(new RuntimeException(secret))
    ).foreach { r =>
      val e = errOf(rows(fixture(respond = Some(r)), "customer"))
      (e._1, e._3.error) shouldBe (StatusCode.BadGateway, "upstream_error")
      e._3.message should include("rid-0001")
      e._3.message should not include "hunter2"
    }
  }

  it should "turn a denial of the data statement into acl_denied without its reason" in {
    val deny: AtomicInteger => Responder = closes =>
      sql =>
        if sql.endsWith("LIMIT 0") then defaultResponder(closes, 1)(sql)
        else IO.pure(Left(RouterFailure.AccessDenied("access denied: acme_lake.main.salary")))
    val e = errOf(rows(fixture(respond = Some(deny)), "customer"))
    (e._1, e._3.error) shouldBe (StatusCode.Forbidden, "acl_denied")
    e._3.message should not include "salary"
  }

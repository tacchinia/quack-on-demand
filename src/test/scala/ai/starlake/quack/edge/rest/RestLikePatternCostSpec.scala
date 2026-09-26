package ai.starlake.quack.edge.rest

import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers

import java.sql.Connection

/** Spike S8 / E7 of the REST edge design (spec 2026-09-25-quack-rest-data-edge-design §2.5, §6.3):
  * how expensive is the worst LIKE pattern the caps allow (at most 4 `*` wildcards, values up to 4
  * KiB), measured on the pinned DuckDB? LIKE is core, so in-process DuckDB (the JDBC driver pinned
  * in `Versions.duckdb`) measures the engine the nodes run, without a node.
  *
  * The worst subject is a value made of the repeated letter the pattern's segments match, followed
  * by a final segment that never does, so a backtracking matcher explores every placement before
  * failing.
  *
  * Findings (2026-09-26, 4 cores; DuckDB CLI 1.5.4, and JDBC 1.5.5.1 in this spec: 568 ms ILIKE and
  * 652 ms escaped LIKE at 1 KiB against 0.6 ms plain), per ROW of the given length:
  *   - plain LIKE (only `%` and literal text): linear, about 1 ms at 4 KiB even with 4 wildcards
  *     and 1000-character segments (DuckDB's segment matcher);
  *   - ILIKE, and any LIKE with `ESCAPE` or `_`: DuckDB's backtracking matcher, cost length^(inner
  *     wildcards). `%a%a%b%` under ILIKE: 0.63 s at 1 KiB, 35 s at 4 KiB. With ESCAPE: one wildcard
  *     11-21 ms (length x needle), two short-segment wildcards 30 ms, two wildcards with
  *     1000-character segments 3.9 s, three 42 s, all at 4 KiB. So "at most 2 wildcards with
  *     ESCAPE" would still admit seconds per row;
  *   - `lower(col) LIKE lower(pattern)`, `=`, `starts_with`, `ends_with`, `contains`: about 1 ms at
  *     4 KiB, needles of 2000 to 4000 characters included.
  *
  * Hence the §6.3 rule: `ilike` renders as `lower(col) LIKE lower(pattern)`, a pattern holding a
  * literal `%` or `_` is an equality, prefix, suffix or substring test (`*` only at its ends), and
  * `ESCAPE` is never emitted ([[PatternForm]]). The first test keeps the engine characterization
  * (small subjects, so it stays cheap); the others pin the cost of the worst ADMITTED patterns and
  * the refusal of the shapes that would leave the linear path.
  */
class RestLikePatternCostSpec extends AnyFlatSpec with Matchers:

  import RestPipelineFixture.*

  /** Best of `n` runs, in milliseconds: the least noisy estimate of the engine's own cost. */
  private def bestMillis(conn: Connection, sql: String, n: Int = 3): Double =
    (1 to n).map { _ =>
      val t0 = System.nanoTime()
      query(conn, sql)
      (System.nanoTime() - t0) / 1e6
    }.min

  private def subject(conn: Connection, bytes: Int): String =
    val t = s"subject_$bytes"
    exec(conn, s"CREATE TABLE $t AS SELECT repeat('a', $bytes) AS s")
    t

  /** The edge's own statement for one filter on column `s`, through parse, resolve and render. */
  private def rendered(table: String, filter: String): Either[String, String] =
    RestQuery
      .parse(Seq("select" -> "s", "s" -> filter))
      .flatMap(RestResolver.resolve(_, Vector(ProbedColumn("s", ColumnKind.Text))))
      .map(RestSql.render(RestSql.Target("memory", "main", table, None), _, 100))
      .left
      .map(_.code)

  "the worst four-wildcard pattern" should "be linear as a plain LIKE and superlinear as ILIKE or escaped LIKE (measured, see class comment)" in
    withDuckDb("memory") { conn =>
      val small                          = subject(conn, 512)
      val large                          = subject(conn, 1024)
      def count(t: String, pred: String) = s"SELECT count(*) FROM $t WHERE s $pred"
      val plain                          = "LIKE '%a%a%b%'"
      val ilike                          = "ILIKE '%a%a%b%'"
      val escaped                        = """LIKE '%a%a%b\%%' ESCAPE '\'"""

      val results = for
        (name, pred) <- List("like" -> plain, "ilike" -> ilike, "like+escape" -> escaped)
        t            <- List(small, large)
      yield
        query(conn, count(t, pred)) shouldBe List(List("0"))
        val ms = bestMillis(conn, count(t, pred), n = if name == "like" then 5 else 1)
        info(f"$name%-12s $t%-13s $ms%9.1f ms")
        (name, t) -> ms

      val m = results.toMap
      // Orders of magnitude apart at 1 KiB (measured ~0.5 ms against ~600 ms); 20x leaves
      // ample room for scheduler noise while still failing if ILIKE ever joins the fast path.
      m(("ilike", large)) should be > 20 * math.max(m(("like", large)), 1.0)
      m(("like+escape", large)) should be > 20 * math.max(m(("like", large)), 1.0)
      // Doubling the length multiplies the generic matcher's cost by ~8 (cubic); require > 3.
      m(("ilike", large)) should be > 3 * m(("ilike", small))
    }

  it should "stay on the fast path when rendered as lower(col) LIKE lower(pattern)" in
    withDuckDb("memory") { conn =>
      val t  = subject(conn, 4096)
      val ms = bestMillis(conn, s"SELECT count(*) FROM $t WHERE lower(s) LIKE lower('%a%a%b%')")
      info(f"lower-like   $t%-13s $ms%9.1f ms")
      ms should be < 1000.0
    }

  // The worst patterns §6.3 admits against a 4 KiB value, rendered by the edge itself: four
  // wildcards with the longest segments the value cap allows, like and ilike, and every needle
  // form with a needle the size of the value. Each measured at about 1 ms (see class comment).
  private val a1000         = "a" * 1000
  private val WorstAdmitted = List(
    "like four long segments"  -> s"like.*$a1000*$a1000*${a1000}b*",
    "ilike four long segments" -> s"ilike.*$a1000*$a1000*${a1000}b*",
    "like spike shape"         -> "like.*a*a*b*",
    "ilike spike shape"        -> "ilike.*a*a*b*",
    "contains, literal %"      -> s"ilike.*${"a" * 2000}b%*",
    "ends_with, literal _"     -> s"like.*${"a" * 2000}b_",
    "starts_with, literal %"   -> s"ilike.${"a" * 4000}%*",
    "equality, literal _"      -> s"ilike.${"a" * 4000}_"
  )

  "the worst admitted pattern" should "cost a few milliseconds on a 4 KiB value" in
    withDuckDb("memory") { conn =>
      val t = subject(conn, 4096)
      WorstAdmitted.foreach { (name, filter) =>
        val sql = rendered(t, filter).fold(code => fail(s"$name refused: $code"), identity)
        sql should not include "ILIKE"
        sql should not include "ESCAPE"
        query(conn, sql) shouldBe Nil
        val ms = bestMillis(conn, sql)
        info(f"$name%-26s $ms%9.1f ms")
        // Measured about 1 ms; the refused shapes cost 30 ms to 42 s. 250 ms is a generous
        // margin for a loaded machine that still fails if a form leaves the linear path.
        withClue(name)(ms should be < 250.0)
      }
    }

  "a pattern that would leave the linear path" should "be refused" in
    // An inner wildcard beside a literal % or _ needs ESCAPE, whatever the case mode.
    List(
      "like.*a*a*b%",
      "ilike.*a*a*b%",
      s"like.*$a1000*${a1000}b_",
      "ilike.a*_",
      "like._*a"
    ).foreach { filter =>
      withClue(filter)(rendered("t", filter) shouldBe Left("invalid_filter"))
    }

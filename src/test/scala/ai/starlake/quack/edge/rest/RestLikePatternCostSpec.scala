package ai.starlake.quack.edge.rest

import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers

import java.sql.Connection

/** Spike S8 / E7 of the REST edge design (spec 2026-09-25-quack-rest-data-edge-design §2.5, §6.3):
  * how expensive is the worst LIKE pattern the caps allow (at most 4 `*` wildcards, values up to 4
  * KiB), measured on the pinned DuckDB? LIKE is core, so in-process DuckDB (the JDBC driver pinned
  * in `Versions.duckdb`) measures the engine the nodes run, without a node.
  *
  * The patterns are the §6.3 renderings: `*` becomes `%`, a literal `%`, `_` or `\` is escaped and
  * `ESCAPE '\'` is emitted only when something was escaped. The worst subject is a value made of
  * the repeated letter the pattern's segments match, followed by a final segment that never does,
  * so the matcher explores every placement before failing.
  *
  * Findings (2026-09-26, 4 cores; DuckDB CLI 1.5.4, and JDBC 1.5.5.1 in this spec: 568 ms ILIKE and
  * 652 ms escaped LIKE at 1 KiB against 0.6 ms plain), per ROW of the given length, pattern
  * `%a%a%b%` (4 wildcards):
  *   - plain LIKE, no ESCAPE: linear, under 1 ms at 4 KiB (DuckDB's contains-chain fast path);
  *   - ILIKE: cubic, 0.63 s at 1 KiB, 4.5 s at 2 KiB, 35 s at 4 KiB;
  *   - LIKE with ESCAPE (any escaped literal, e.g. `*a*a*b%*`): cubic too, 0.67 s at 1 KiB, 5.4 s
  *     at 2 KiB;
  *   - five `%` (not allowed by the cap) under ILIKE: 0.56 s at 256 B, 8.7 s at 512 B;
  *   - `lower(col) LIKE lower(pattern)` stays on the fast path (1 ms at 4 KiB).
  * The cost grows as length^(wildcards - 1) once the generic matcher is used, so the cap of 4
  * wildcards lets one request pin a core for minutes on a table of a few rows of 4 KiB text. §6.3
  * must lower the cap for `ilike` and for escaped `like` (two wildcards is quadratic: 25 ms at 4
  * KiB), or render them onto the fast path; a decision for the renderer, reported, not made here.
  * This spec keeps the subjects small (at most 1 KiB) so it stays cheap, and pins the shape: the
  * generic-matcher renderings are orders of magnitude slower than the fast path at equal size.
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

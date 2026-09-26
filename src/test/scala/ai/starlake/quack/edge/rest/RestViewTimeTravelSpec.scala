package ai.starlake.quack.edge.rest

import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers

import java.nio.file.Files
import java.sql.Connection
import scala.util.{Failure, Success, Try}

/** Spike S2 of the REST edge design (spec 2026-09-25-quack-rest-data-edge-design §2.5, §6.6): does
  * DuckLake accept `AT (VERSION => n)` on a VIEW, and if it does, does the view read at that
  * snapshot? The edge's `asOf*` on a view and the E2 pagination check on a view depend on it.
  *
  * Needs the `ducklake` extension, which in-process DuckDB installs from the extension repository.
  * Cancelled where that repository is unreachable (as on the machine that wrote this spec, where
  * the outcome is therefore still open), in the style of DuckLakeInitializerRaceSpec. The metadata
  * catalog is a local DuckDB file, so neither Postgres nor a node is needed.
  *
  * The one outcome that must never pass silently is "accepted but ignored": a view that answers
  * `AT (VERSION => n)` with the CURRENT rows would make the edge's snapshot header a lie. If the
  * clause is refused, the edge serves views at the current snapshot and answers `asOf*` on a view
  * with 400 `invalid_selector` (§2.5 S2); the test records the refusal instead of failing.
  */
class RestViewTimeTravelSpec extends AnyFlatSpec with Matchers:

  import RestPipelineFixture.*

  private def extensionUnavailable(t: Throwable): Boolean =
    val msg = Option(t.getMessage).getOrElse("").toLowerCase
    msg.contains("failed to download extension") ||
    msg.contains("unable to connect to extension repository") ||
    msg.contains("could not establish connection") ||
    (msg.contains("extension") && msg.contains("not found"))

  private def loadDuckLakeOrCancel(conn: Connection): Unit =
    Try {
      exec(conn, "INSTALL ducklake")
      exec(conn, "LOAD ducklake")
    } match
      case Success(_)                            => ()
      case Failure(t) if extensionUnavailable(t) =>
        cancel(s"DuckDB ducklake extension unavailable (${t.getMessage}); skipping")
      case Failure(t) => throw t

  "AT (VERSION => n) on a DuckLake view" should "read the view at that snapshot, or be refused, but never be ignored" in
    withDuckDb("memory") { conn =>
      loadDuckLakeOrCancel(conn)
      val dir = Files.createTempDirectory("qod-s2-")
      exec(
        conn,
        s"ATTACH 'ducklake:${dir.resolve("meta.ducklake")}' AS lake " +
          s"(DATA_PATH '${dir.resolve("data")}/')"
      )
      exec(conn, "CREATE TABLE lake.main.t (id INTEGER)")
      exec(conn, "INSERT INTO lake.main.t VALUES (1)")
      exec(conn, "CREATE VIEW lake.main.v AS SELECT id FROM lake.main.t")
      val snapshot =
        query(conn, "SELECT max(snapshot_id) FROM ducklake_snapshots('lake')").head.head
      exec(conn, "INSERT INTO lake.main.t VALUES (2), (3)")

      // Control: the table itself honours the clause.
      query(conn, s"SELECT count(*) FROM lake.main.t AT (VERSION => $snapshot)") shouldBe
        List(List("1"))

      Try(
        query(conn, s"""SELECT count(*) FROM "lake"."main"."v" AT (VERSION => $snapshot)""")
      ) match
        case Success(rows) =>
          info(s"S2: accepted on a view at snapshot $snapshot -> $rows")
          rows shouldBe List(List("1")) // not 3: the current rows would mean the clause is ignored
        case Failure(t) =>
          info(s"S2: refused on a view: ${t.getMessage}")
          succeed
    }

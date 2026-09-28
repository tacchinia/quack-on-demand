package ai.starlake.quack.ondemand.branch

import com.typesafe.scalalogging.LazyLogging

import java.sql.{Connection, DriverManager}
import scala.util.control.NonFatal

/** Binds a merge's commit to the main snapshot its change set was validated against.
  *
  * The merge validates against main snapshot S, spawns a node and runs one DuckDB transaction.
  * Nothing on the DuckDB side can hold that validation to the commit: a DuckLake transaction pins
  * its snapshot at its first catalog read, not at BEGIN, so a main write in between is simply built
  * upon, and DuckLake's commit-time conflict check does not see a concurrent insert, update, delete
  * or ALTER on a table the merge DROPs (all verified against DuckLake on DuckDB 1.5.5). Either way
  * the main write is silently lost.
  *
  * [[arm]] closes that window where the commit actually happens, in the parent catalog's Postgres
  * transaction: a BEFORE INSERT trigger on `ducklake_snapshot_changes` refuses the row carrying the
  * merge's (unique) commit message whenever any snapshot other than the merge's own landed after S.
  * Postgres serializes snapshot ids through the primary key, so the check and the commit are
  * atomic. The batch sets `ducklake_max_retry_count = 0` (see [[BranchMergeSql.batch]]): once main
  * has moved every retry is refused anyway.
  */
trait MergeFence:

  /** Arms the fence for the commit stamped `message`: it commits only directly on top of
    * `baseSnapshot`. `Left` when the fence cannot be installed; the caller must not merge.
    */
  def arm(
      parentMeta: Map[String, String],
      message: String,
      baseSnapshot: Long
  ): Either[String, Unit]

  /** Removes the fence row for `message`. Best effort: a leftover row only matches that one
    * message.
    */
  def disarm(parentMeta: Map[String, String], message: String): Unit

/** [[MergeFence]] over the parent catalog's Postgres database (metastore `pgHost` / `pgPort` /
  * `pgUser` / `pgPassword` / `dbName`, the credentials DuckLake created its tables with, so they
  * own `ducklake_snapshot_changes`). Objects live in their own schema, [[FenceSchema]], so
  * [[BranchCloner]] (which copies the metadata schema only) never carries them into a branch. The
  * trigger is installed once per catalog and left in place: it is a no-op for every commit whose
  * message has no fence row, and re-creating it on each merge would take a lock that stalls the
  * tenant's commits.
  */
object PostgresMergeFence extends MergeFence with LazyLogging:

  val FenceSchema: String = "qod_merge_fence"

  private def q(ident: String): String = "\"" + ident.replace("\"", "\"\"") + "\""

  private def connect(meta: Map[String, String]): Connection =
    Class.forName("org.postgresql.Driver")
    DriverManager.getConnection(
      s"jdbc:postgresql://${meta.getOrElse("pgHost", "localhost")}:${meta
          .getOrElse("pgPort", "5432")}/${meta.getOrElse("dbName", "")}",
      meta.getOrElse("pgUser", "postgres"),
      meta.getOrElse("pgPassword", "")
    )

  private def metaSchema(c: Connection): Option[String] =
    val ps = c.prepareStatement(
      "SELECT table_schema FROM information_schema.tables WHERE table_name = 'ducklake_metadata' " +
        "ORDER BY (table_schema = 'public') DESC LIMIT 1"
    )
    try
      val rs = ps.executeQuery()
      try if rs.next() then Some(rs.getString(1)) else None
      finally rs.close()
    finally ps.close()

  private def triggerInstalled(c: Connection, schema: String): Boolean =
    val ps = c.prepareStatement(
      "SELECT 1 FROM pg_trigger t JOIN pg_class r ON r.oid = t.tgrelid " +
        "JOIN pg_namespace n ON n.oid = r.relnamespace " +
        "WHERE n.nspname = ? AND r.relname = 'ducklake_snapshot_changes' AND t.tgname = ?"
    )
    try
      ps.setString(1, schema)
      ps.setString(2, FenceSchema)
      val rs = ps.executeQuery()
      try rs.next()
      finally rs.close()
    finally ps.close()

  private def install(c: Connection, schema: String): Unit =
    val fs = q(FenceSchema)
    val ms = q(schema)
    val st = c.createStatement()
    try
      st.execute(s"CREATE SCHEMA IF NOT EXISTS $fs")
      st.execute(
        s"CREATE TABLE IF NOT EXISTS $fs.fence " +
          "(commit_message TEXT PRIMARY KEY, base_snapshot BIGINT NOT NULL)"
      )
      st.execute(
        s"""CREATE OR REPLACE FUNCTION $fs.check_commit() RETURNS trigger AS $$$$
           |DECLARE base BIGINT;
           |BEGIN
           |  SELECT f.base_snapshot INTO base FROM $fs.fence f
           |    WHERE f.commit_message = NEW.commit_message;
           |  IF FOUND AND EXISTS (SELECT 1 FROM $ms.ducklake_snapshot s
           |      WHERE s.snapshot_id > base AND s.snapshot_id <> NEW.snapshot_id) THEN
           |    RAISE EXCEPTION 'qod merge fence: conflict, main moved past snapshot % before the merge committed', base;
           |  END IF;
           |  RETURN NEW;
           |END $$$$ LANGUAGE plpgsql""".stripMargin
      )
      if !triggerInstalled(c, schema) then
        st.execute(
          s"CREATE TRIGGER $fs BEFORE INSERT ON $ms.ducklake_snapshot_changes " +
            s"FOR EACH ROW EXECUTE FUNCTION $fs.check_commit()"
        )
    finally st.close()

  def arm(
      parentMeta: Map[String, String],
      message: String,
      baseSnapshot: Long
  ): Either[String, Unit] =
    try
      val c = connect(parentMeta)
      try
        metaSchema(c) match
          case None         => Left("parent catalog has no DuckLake metadata")
          case Some(schema) =>
            install(c, schema)
            val ps = c.prepareStatement(
              s"INSERT INTO ${q(FenceSchema)}.fence (commit_message, base_snapshot) VALUES (?, ?) " +
                "ON CONFLICT (commit_message) DO UPDATE SET base_snapshot = EXCLUDED.base_snapshot"
            )
            try
              ps.setString(1, message)
              ps.setLong(2, baseSnapshot)
              ps.executeUpdate()
              Right(())
            finally ps.close()
      finally c.close()
    catch case NonFatal(e) => Left(s"cannot arm the merge fence: ${e.getMessage}")

  def disarm(parentMeta: Map[String, String], message: String): Unit =
    try
      val c = connect(parentMeta)
      try
        val ps =
          c.prepareStatement(s"DELETE FROM ${q(FenceSchema)}.fence WHERE commit_message = ?")
        try
          ps.setString(1, message)
          ps.executeUpdate(): Unit
        finally ps.close()
      finally c.close()
    catch
      case NonFatal(e) =>
        logger.warn(s"merge fence: cannot remove the row for '$message': ${e.getMessage}")

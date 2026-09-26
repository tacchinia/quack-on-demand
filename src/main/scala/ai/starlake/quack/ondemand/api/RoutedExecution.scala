package ai.starlake.quack.ondemand.api

import ai.starlake.quack.edge.{FlightSqlRouter, QueryResult, RouterFailure}
import ai.starlake.quack.model.PoolKey
import ai.starlake.quack.ondemand.rbac.EffectiveSet
import cats.effect.IO

import scala.concurrent.duration.FiniteDuration
import java.util.concurrent.TimeUnit

/** The router call at the end of the routed executor (`Main.routedExecutor`), once the caller's
  * effective set is resolved and attenuated: what every [[CatalogPreviewHandlers.PreviewExecutor]]
  * caller (MCP `run_sql`/`describe_table`, preview, data diff, restore, undrop, the REST edge)
  * finally runs.
  *
  * Kept out of `Main` so that what the [[ExecCaller]] carries (patId, `source`, `preferredNode`,
  * the token timeout) is forwarded in one place a spec can drive against a real router, rather than
  * inside a closure only a live manager exercises.
  */
object RoutedExecution:

  def run(
      router: FlightSqlRouter,
      caller: ExecCaller,
      poolKey: PoolKey,
      sql: String,
      effectiveSet: Option[EffectiveSet],
      recordExecution: Boolean
  ): IO[Either[RouterFailure, QueryResult]] =
    val execute = router.execute(
      caller.connectionId,
      caller.identity,
      poolKey,
      sql,
      effectiveSet = effectiveSet,
      preferredNode = caller.preferredNode,
      recordExecution = recordExecution,
      patId = caller.patId,
      // This call is the single choke point every PreviewExecutor caller shares (preview, data
      // diff, restore, undrop, AND the MCP run_sql / describe_table tools). PAT attenuation narrows
      // `effectiveSet` but the SQL admin dialect's own authorization does not know about that
      // ceiling, so a claimed admin statement reaching this path must stay on the pre-dialect
      // routed path (fail-closed denial or normal ACL, unchanged) rather than the dialect's
      // superuser/tenant-admin check.
      adminDispatch = false,
      source = caller.source
    )
    // BOUNDED WAIT, not a cancellation: past this many milliseconds the caller of this executor
    // gets a failure back, but the underlying IO.blocking node call keeps running unobserved and
    // may still complete -- the same caveat as previewTimeoutSec, and the same best-effort gap
    // FlightSqlRouter already has between register and attachCancel. Durable cancellation
    // (bracketing the connection inside QuackHttpClient) is deliberately deferred to a later
    // sub-project; do not describe this branch as killing or aborting the statement.
    // BoundedWait rather than timeoutTo: timeoutTo cannot interrupt IO.blocking, so it sat on the
    // node call past the limit, then dropped the result without closing it (its Arrow reader and
    // its kill-registry entry leaked). A result that arrives late is now closed exactly once.
    caller.restriction.stmtTimeoutMs match
      case Some(ms) if ms > 0 =>
        BoundedWait.closingLate(
          execute,
          FiniteDuration(ms.toLong, TimeUnit.MILLISECONDS),
          RouterFailure.Unavailable(s"statement exceeded this token's ${ms}ms limit"),
          _.close()
        )
      case _ => execute

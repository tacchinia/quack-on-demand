package ai.starlake.quack.ondemand.api

import ai.starlake.quack.edge.adapter.*
import ai.starlake.quack.edge.sql.{Denied, StatementValidator, ValidationContext, ValidationResult}
import ai.starlake.quack.edge.{FlightSqlRouter, RouterFailure, SessionRegistry}
import ai.starlake.quack.model.{
  NodeSpec,
  PoolKey,
  RoleDistribution,
  RunningNode,
  Tenant,
  TenantDbKind
}
import ai.starlake.quack.ondemand.PoolSupervisor
import ai.starlake.quack.ondemand.auth.TokenRestriction
import ai.starlake.quack.ondemand.runtime.QuackBackend
import ai.starlake.quack.ondemand.state.InMemoryControlPlaneStore
import ai.starlake.quack.ondemand.telemetry.EventJournal
import ai.starlake.quack.ondemand.telemetry.testkit.RecordingTelemetryStore
import cats.effect.IO
import cats.effect.unsafe.implicits.global
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers

import java.time.Instant
import java.util.concurrent.{CountDownLatch, TimeUnit}
import scala.collection.concurrent.TrieMap
import scala.concurrent.duration.*

/** The router call behind every [[CatalogPreviewHandlers.PreviewExecutor]] (MCP, preview, diff,
  * restore, undrop, and the REST edge): what the caller value carries must reach the router, and a
  * token timeout must not leak the result it stops waiting for.
  */
class RoutedExecutionSpec extends AnyFlatSpec with Matchers:

  private val poolKey: PoolKey = PoolKey("acme", "acme_default", "sales")

  private final case class Fixture(
      router: FlightSqlRouter,
      journal: EventJournal,
      store: RecordingTelemetryStore,
      nodes: List[RunningNode]
  )

  private def setup(
      nodeCount: Int = 1,
      validator: StatementValidator = StatementValidator.allowAll,
      respond: () => IO[QuackResponse] = () => IO.pure(TestArrow.okResponse())
  ): Fixture =
    val backend = new QuackBackend:
      private val n          = TrieMap.empty[String, RunningNode]
      def start(s: NodeSpec) = IO {
        val r = RunningNode(
          s.nodeId,
          s.poolKey,
          s.role,
          "127.0.0.1",
          21800 + n.size,
          "tok",
          Some(1L),
          None,
          Instant.EPOCH,
          maxConcurrent = s.maxConcurrent
        )
        n.put(s.nodeId, r); r
      }
      def stop(key: PoolKey, id: String) = IO { n.remove(id); () }
      def isAlive(id: String)            = n.contains(id)
      def discoverExisting()             = IO.pure(n.values.toList)
      def cleanup()                      = IO(n.clear())
    val tracker = new NodeLoadTracker
    val sup     = new PoolSupervisor(backend, tracker, new InMemoryControlPlaneStore())
    sup.createTenant(Tenant(poolKey.tenant)).unsafeRunSync()
    sup
      .createTenantDb(poolKey.tenant, poolKey.tenantDb, TenantDbKind.InMemory, Map.empty, "")
      .unsafeRunSync()
    sup.createPool(poolKey, RoleDistribution(0, 0, nodeCount)).unsafeRunSync()
    val store   = new RecordingTelemetryStore
    val journal = new EventJournal(store)
    val client  = new QuackHttpClient(
      TestArrow.sharedAllocator,
      nativeClient = true,
      nodeDisableSsl = true
    ):
      override def query(endpoint: String, token: String, sql: String, session: Option[String]) =
        respond()
    val router = new FlightSqlRouter(
      sup,
      new SessionRegistry,
      tracker,
      new QuackHttpAdapter(client, tracker),
      validator = validator,
      journal = journal
    )
    Fixture(router, journal, store, sup.get(poolKey).get.nodes)

  private def timed(ms: Int): TokenRestriction =
    TokenRestriction.Unrestricted.copy(stmtTimeoutMs = Some(ms))

  "RoutedExecution.run" should "route to the caller's preferred node" in:
    val fx     = setup(nodeCount = 3)
    val target = fx.nodes(2).nodeId
    val caller = ExecCaller.unrestricted("c-1", "alice").copy(preferredNode = Some(target))
    val out    = RoutedExecution
      .run(fx.router, caller, poolKey, "SELECT 1", None, recordExecution = true)
      .unsafeRunSync()
    out.map(_.nodeId) shouldBe Right(target)
    out.foreach(_.close())

  it should "record the caller's source as the audit origin" in:
    val denying = new StatementValidator:
      def validate(ctx: ValidationContext): ValidationResult = Denied("no grant", Set.empty)
    val fx     = setup(validator = denying)
    val caller = ExecCaller.unrestricted("c-2", "alice").copy(source = "rest")
    val out    = RoutedExecution
      .run(fx.router, caller, poolKey, "SELECT * FROM t", None, recordExecution = true)
      .unsafeRunSync()
    out shouldBe a[Left[?, ?]]
    fx.journal.drainNow()
    fx.store.events.map(_.origin) shouldBe List("rest")

  it should "keep the FlightSQL origin for a caller that names no source" in:
    val denying = new StatementValidator:
      def validate(ctx: ValidationContext): ValidationResult = Denied("no grant", Set.empty)
    val fx = setup(validator = denying)
    RoutedExecution
      .run(
        fx.router,
        ExecCaller.unrestricted("c-3", "alice"),
        poolKey,
        "SELECT * FROM t",
        None,
        recordExecution = true
      )
      .unsafeRunSync()
    fx.journal.drainNow()
    fx.store.events.map(_.origin) shouldBe List("flightsql")

  it should "answer within the token's limit and close the result that arrives late" in:
    val release = new CountDownLatch(1)
    // The node call blocks like the real one (IO.blocking, not interruptible by cancellation) and
    // answers after 1.5s whatever happens, so a wait that is not bounded shows up as elapsed time
    // rather than as a hung suite.
    val fx = setup(respond =
      () => IO.blocking { release.await(1500, TimeUnit.MILLISECONDS); TestArrow.okResponse() }
    )
    val caller = ExecCaller("c-4", "alice", timed(50))
    val t0     = System.nanoTime()
    val out    = RoutedExecution
      .run(fx.router, caller, poolKey, "SELECT 1", None, recordExecution = true)
      .unsafeRunSync()
    (System.nanoTime() - t0).nanos should be < 1.second
    out match
      case Left(RouterFailure.Unavailable(m)) => m should include("50ms")
      case other                              => fail(s"expected Unavailable, got $other")
    // The statement is still running on the node and still registered.
    fx.router.registry.list() should have size 1
    release.countDown()
    // Closing the late result deregisters it; a plain timeoutTo dropped it and nothing ever did.
    val deadline = System.nanoTime() + 5.seconds.toNanos
    while fx.router.registry.list().nonEmpty && System.nanoTime() < deadline do Thread.sleep(10)
    fx.router.registry.list() shouldBe empty

  it should "hand a result delivered within the limit to the caller, still open" in:
    val fx  = setup()
    val out = RoutedExecution
      .run(fx.router, ExecCaller("c-5", "alice", timed(5000)), poolKey, "SELECT 1", None, true)
      .unsafeRunSync()
    out shouldBe a[Right[?, ?]]
    fx.router.registry.list() should have size 1
    out.foreach(_.close())
    fx.router.registry.list() shouldBe empty

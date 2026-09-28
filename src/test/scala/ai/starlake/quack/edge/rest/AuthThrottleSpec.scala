package ai.starlake.quack.edge.rest

import ai.starlake.quack.ondemand.telemetry.{AuditActions, AuditRateLimiter, AuditRecorder}
import ai.starlake.quack.ondemand.telemetry.testkit.RecordingTelemetryStore
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers

import java.util.concurrent.atomic.AtomicLong
import scala.collection.mutable.ListBuffer

/** The failed-authentication throttle of the REST data edge (design §7.3, O-1), driven by an
  * injected clock: a sliding window per client key, a block that outlives counter churn, and a
  * global token bucket over failed credential verifications.
  */
class AuthThrottleSpec extends AnyFlatSpec with Matchers:

  private final class Clock(start: Long = 1_000_000L):
    val now                     = new AtomicLong(start)
    def apply(): Long           = now.get
    def advance(ms: Long): Unit = now.addAndGet(ms): Unit

  private def throttle(
      clock: Clock,
      perWindow: Int = 20,
      windowSec: Int = 60,
      blockSec: Int = 300,
      globalPerSec: Int = 50,
      maxEntries: Int = 100000,
      blocks: ListBuffer[String] = ListBuffer.empty
  ) =
    new AuthThrottle(
      failuresPerWindow = perWindow,
      windowSec = windowSec,
      blockSec = blockSec,
      globalPerSec = globalPerSec,
      maxEntries = maxEntries,
      clock = () => clock(),
      onBlock = k => blocks.synchronized(blocks += k): Unit
    )

  "the per-key window" should "block at failure N+1, not at N" in {
    val c = new Clock
    val t = throttle(c)
    (1 to 20).foreach(_ => t.recordFailure("a") shouldBe false)
    t.blockedFor("a") shouldBe None
    t.recordFailure("a") shouldBe true
    t.blockedFor("a") shouldBe Some(300)
    t.blockedFor("b") shouldBe None
  }

  it should "report the seconds left, rounded up, and lift the block when it expires" in {
    val c = new Clock
    val t = throttle(c)
    (1 to 21).foreach(_ => t.recordFailure("a"))
    c.advance(299_500)
    t.blockedFor("a") shouldBe Some(1)
    c.advance(500)
    t.blockedFor("a") shouldBe None
    // The block started a fresh count: one more failure does not re-block.
    t.recordFailure("a") shouldBe false
  }

  it should "not reset on a success, since there is no success call at all" in {
    // A success is simply not recorded; failures around it keep accumulating.
    val c = new Clock
    val t = throttle(c, perWindow = 3)
    (1 to 3).foreach(_ => t.recordFailure("a"))
    t.blockedFor("a") shouldBe None // a request that then succeeds sees no block
    t.recordFailure("a") shouldBe true
  }

  it should "slide: failures older than the window stop counting" in {
    val c = new Clock
    val t = throttle(c, perWindow = 3, windowSec = 60)
    t.recordFailure("a")
    c.advance(30_000)
    t.recordFailure("a")
    t.recordFailure("a")
    c.advance(30_001) // the first failure is now 60.001 s old
    t.recordFailure("a") shouldBe false
    t.blockedFor("a") shouldBe None
    c.advance(1)
    t.recordFailure("a") shouldBe true
  }

  it should "report each block exactly once to the audit hook" in {
    val c      = new Clock
    val blocks = ListBuffer.empty[String]
    val t      = throttle(c, perWindow = 2, blocks = blocks)
    (1 to 3).foreach(_ => t.recordFailure("a"))
    (1 to 3).foreach(_ => t.recordFailure("b"))
    blocks.toList shouldBe List("a", "b")
  }

  "the memory bound" should "never evict an active block under churn of 200000 other keys" in {
    val c = new Clock
    val t = throttle(c, perWindow = 1, maxEntries = 1000)
    t.recordFailure("victim")
    t.recordFailure("victim") shouldBe true
    (1 to 200000).foreach(i => t.recordFailure(s"k$i"))
    t.cleanUp()
    t.counterEntries should be <= 1000L
    t.blockedFor("victim") shouldBe Some(300)
  }

  it should "bound the blocked set, dropping the block that expires first when full" in {
    val c = new Clock
    val t = throttle(c, perWindow = 1, maxEntries = 3)
    List("a", "b", "c").foreach { k =>
      t.recordFailure(k); t.recordFailure(k); c.advance(1000)
    }
    t.blockedCount shouldBe 3
    t.recordFailure("d")
    t.recordFailure("d") shouldBe true
    t.blockedCount shouldBe 3
    t.blockedFor("a") shouldBe None
    List("b", "c", "d").foreach(k => t.blockedFor(k) should not be empty)
  }

  "the global bucket" should "hold globalPerSec tokens and refill at that rate" in {
    val c = new Clock
    val t = throttle(c, globalPerSec = 50)
    (1 to 50).foreach(_ => t.tryGlobal() shouldBe true)
    t.tryGlobal() shouldBe false
    c.advance(20) // 50 per second = one token per 20 ms
    t.tryGlobal() shouldBe true
    t.tryGlobal() shouldBe false
    c.advance(10_000) // refill never exceeds the capacity
    (1 to 50).foreach(_ => t.tryGlobal() shouldBe true)
    t.tryGlobal() shouldBe false
  }

  "counts" should "hold every authentication failure and nothing else" in {
    AuthThrottle.counts(RestError.Unauthorized) shouldBe true
    AuthThrottle.counts(RestError.Forbidden("your token is scoped to tenant 'x'")) shouldBe true
    List(
      RestError.AclDenied("access denied"),
      RestError.NotFound,
      RestError.InvalidFilter("x"),
      RestError.UnknownColumn("x"),
      RestError.OrderRequired,
      RestError.InvalidKind,
      RestError.UnsupportedFormat,
      RestError.PoolUnavailable,
      RestError.StatementTimeout,
      RestError.UpstreamError,
      RestError.TooManyRequests,
      RestError.TooManyAuthFailures(1)
    ).foreach(e => withClue(e.code)(AuthThrottle.counts(e) shouldBe false))
  }

  "auditingBlocks" should "record a block as an anonymous auth denial with its source, rate-limited" in {
    val store   = new RecordingTelemetryStore
    val audit   = new AuditRecorder(store, _ => None)
    val c       = new Clock
    val limiter = new AuditRateLimiter(intervalMillis = 1000, clock = () => c())
    val t       =
      new AuthThrottle(1, 60, 300, 50, 1000, () => c(), AuthThrottle.auditingBlocks(audit, limiter))
    List("198.51.100.1", "198.51.100.2").foreach(k => (1 to 2).foreach(_ => t.recordFailure(k)))
    c.advance(1000)
    (1 to 2).foreach(_ => t.recordFailure("198.51.100.3"))
    store.events.map(_.action).toList shouldBe List.fill(2)(AuditActions.AuthRestThrottled)
    val e = store.events.head
    (e.actor, e.family, e.outcome, e.origin) shouldBe ("anonymous", "auth", "denied", "rest")
    e.detail.get("source") shouldBe Some("198.51.100.1")
    store.events(1).detail.get("source") shouldBe Some("198.51.100.3")
  }

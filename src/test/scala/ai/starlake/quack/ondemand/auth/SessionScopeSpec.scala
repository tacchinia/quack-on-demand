package ai.starlake.quack.ondemand.auth

import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers

/** Pins [[SessionScope.tenantsFilter]], the single implementation of the telemetry tenant-scoping
  * rule shared by the audit/history/usage/active-statement handlers:
  *   - superuser or unresolvable session (static key / open mode): the requested tenant filter
  *     passes through unchanged;
  *   - tenant admin: pinned to `manageableTenants`, a requested tenant narrows WITHIN them, and a
  *     non-manageable request falls back to the full manageable set (no error, no existence leak).
  */
class SessionScopeSpec extends AnyFlatSpec with Matchers:

  private val admin = SessionScope(superuser = false, manageableTenants = Set("acme", "globex"))

  "tenantsFilter" should "pass no filter through for an unresolvable session" in {
    SessionScope.tenantsFilter(None, None) shouldBe None
  }

  it should "pass the requested tenant through for an unresolvable session" in {
    SessionScope.tenantsFilter(None, Some("acme")) shouldBe Some(Set("acme"))
  }

  it should "pass no filter through for a superuser" in {
    SessionScope.tenantsFilter(Some(SessionScope.Superuser), None) shouldBe None
  }

  it should "pass the requested tenant through for a superuser" in {
    SessionScope.tenantsFilter(Some(SessionScope.Superuser), Some("acme")) shouldBe
      Some(Set("acme"))
  }

  it should "pin a tenant admin to the manageable set when nothing is requested" in {
    SessionScope.tenantsFilter(Some(admin), None) shouldBe Some(Set("acme", "globex"))
  }

  it should "narrow within the manageable set when a manageable tenant is requested" in {
    SessionScope.tenantsFilter(Some(admin), Some("acme")) shouldBe Some(Set("acme"))
  }

  it should "fall back to the full manageable set on a non-manageable request (no leak)" in {
    SessionScope.tenantsFilter(Some(admin), Some("initech")) shouldBe Some(Set("acme", "globex"))
  }

  // ---- failClosed: None is reserved for the static key ----

  private val lookup: String => Option[SessionScope] = {
    case "live-admin" => Some(admin)
    case "live-root"  => Some(SessionScope.Superuser)
    case _            => None
  }

  "failClosed" should "answer None for the configured static key only" in {
    val scopeOf = SessionScope.failClosed(Some("k1"), lookup)
    scopeOf("k1") shouldBe None
    scopeOf("k1x") shouldBe Some(SessionScope.NoAccess)
    scopeOf("") shouldBe Some(SessionScope.NoAccess)
  }

  it should "pass resolvable tokens through unchanged" in {
    val scopeOf = SessionScope.failClosed(Some("k1"), lookup)
    scopeOf("live-admin") shouldBe Some(admin)
    scopeOf("live-root") shouldBe Some(SessionScope.Superuser)
  }

  it should "give an expired, revoked or unknown token no privilege, never the static-key arm" in {
    val scopeOf = SessionScope.failClosed(Some("k1"), lookup)
    scopeOf("expired-session") shouldBe Some(SessionScope.NoAccess)
    SessionScope.tenantsFilter(scopeOf("expired-session"), None) shouldBe Some(Set.empty)
    SessionScope.tenantsFilter(scopeOf("expired-session"), Some("globex")) shouldBe Some(Set.empty)
  }

  it should "treat an unset or empty static key as matching nothing" in
    List(None, Some("")).foreach { key =>
      val scopeOf = SessionScope.failClosed(key, lookup)
      scopeOf("") shouldBe Some(SessionScope.NoAccess)
      scopeOf("anything") shouldBe Some(SessionScope.NoAccess)
    }

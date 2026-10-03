package ai.starlake.quack.ondemand.api

import ai.starlake.quack.ondemand.auth.TokenRestriction
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers

/** The caller value the executor takes. It exists so a restriction cannot be dropped silently:
  * adding a call site without deciding on a restriction is a compile error.
  */
class ExecCallerSpec extends AnyFlatSpec with Matchers:

  "ExecCaller.unrestricted" should "carry the identity and no restriction" in {
    val c = ExecCaller.unrestricted("conn-1", "alice")
    c.connectionId shouldBe "conn-1"
    c.identity shouldBe "alice"
    c.restriction shouldBe TokenRestriction.Unrestricted
  }

  it should "default to the FlightSQL source, the MCP OPA edge and no preferred node" in {
    // Every call site that predates the REST data edge keeps its audit origin, OPA tag and routing.
    val c = ExecCaller.unrestricted("conn-1", "alice")
    c.source shouldBe "flightsql"
    c.edge shouldBe "mcp"
    c.preferredNode shouldBe None
  }

  it should "carry no JWT roles, groups or claims and admit a superuser row by default" in {
    // Only a caller holding a verified bearer JWT sets these; every other call site is unchanged.
    val c = ExecCaller.unrestricted("conn-1", "alice")
    (c.jwtRoles, c.jwtGroups, c.jwtClaims) shouldBe (Set.empty, Set.empty, Map.empty)
    c.superuserAdmissible shouldBe true
  }

  "effectiveMaxRows" should "take the smallest of the server cap, the token cap and the request" in {
    val capped = ExecCaller
      .unrestricted("c", "u")
      .copy(restriction = TokenRestriction.Unrestricted.copy(maxRows = Some(20)))
    capped.effectiveMaxRows(serverCap = 500, requested = 100) shouldBe 20
    capped.effectiveMaxRows(serverCap = 10, requested = 100) shouldBe 10
    ExecCaller.unrestricted("c", "u").effectiveMaxRows(500, 100) shouldBe 100
  }

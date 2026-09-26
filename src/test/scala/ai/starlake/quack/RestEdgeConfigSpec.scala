package ai.starlake.quack

import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers
import pureconfig.ConfigSource

// The camelCase ProductHint / ConfigReader givens live in object Main (see RoutingConfigSpec).
import Main.given

/** U6 of the REST data edge design (spec 2026-09-25-quack-rest-data-edge-design §8.2, §8.3, §11.1):
  * the `quack-rest` block, its boot validation, and the banner line with its ACL-disabled variant.
  */
class RestEdgeConfigSpec extends AnyFlatSpec with Matchers:

  private val defaults = ConfigSource.default.at("quack-rest").loadOrThrow[RestEdgeConfig]

  private val others = List("manager REST" -> 20900, "FlightSQL" -> 31338, "Quack" -> 9494)

  "application.conf" should "ship the quack-rest block disabled, on 31339, with TLS on" in {
    defaults.enabled shouldBe false
    defaults.host shouldBe "0.0.0.0"
    defaults.port shouldBe 31339
    defaults.tlsEnabled shouldBe true
    defaults.tlsCertChain shouldBe "certs/server-cert.pem"
    defaults.tlsPrivateKey shouldBe "certs/server-key.pem"
    defaults.defaultLimit shouldBe 1000
    defaults.maxRows shouldBe 100000
    defaults.stmtTimeoutSec shouldBe 60
    defaults.maxConnections shouldBe 512
    defaults.maxHeaderBytes shouldBe 16384
    defaults.headerReceiveTimeoutSec shouldBe 10
    defaults.idleTimeoutSec shouldBe 60
  }

  it should "read camelCase overrides through the Main ProductHint" in {
    val cfg = ConfigSource
      .string("quack-rest { enabled = true, port = 40000, maxRows = 50, defaultLimit = 10 }")
      .withFallback(ConfigSource.default)
      .at("quack-rest")
      .loadOrThrow[RestEdgeConfig]
    (cfg.enabled, cfg.port, cfg.maxRows, cfg.defaultLimit) shouldBe (true, 40000, 50, 10)
  }

  "RestEdgeConfig.validate" should "accept the shipped defaults once enabled" in {
    RestEdgeConfig.validate(defaults.copy(enabled = true), others) shouldBe Right(())
  }

  it should "skip every check while the edge is disabled" in {
    RestEdgeConfig.validate(defaults.copy(port = 20900, defaultLimit = 0), others) shouldBe
      Right(())
  }

  it should "refuse each out-of-range number, naming its env var" in {
    val on    = defaults.copy(enabled = true)
    val cases = List(
      on.copy(port = 0)                    -> "QOD_REST_PORT",
      on.copy(port = 65536)                -> "QOD_REST_PORT",
      on.copy(defaultLimit = 0)            -> "QOD_REST_DEFAULT_LIMIT",
      on.copy(maxRows = 999)               -> "QOD_REST_MAX_ROWS",
      on.copy(stmtTimeoutSec = 0)          -> "QOD_REST_STMT_TIMEOUT_SEC",
      on.copy(maxConnections = 0)          -> "QOD_REST_MAX_CONNECTIONS",
      on.copy(maxHeaderBytes = 1023)       -> "QOD_REST_MAX_HEADER_BYTES",
      on.copy(headerReceiveTimeoutSec = 0) -> "QOD_REST_HEADER_RECEIVE_TIMEOUT_SEC",
      on.copy(idleTimeoutSec = 0)          -> "QOD_REST_IDLE_TIMEOUT_SEC"
    )
    cases.foreach { case (cfg, env) =>
      withClue(env) {
        RestEdgeConfig.validate(cfg, others).left.toOption.getOrElse("") should include(env)
      }
    }
  }

  it should "refuse a port another door already binds" in {
    val msg = RestEdgeConfig
      .validate(defaults.copy(enabled = true, port = 31338), others)
      .left
      .toOption
      .getOrElse("")
    msg should include("31338")
    msg should include("FlightSQL")
  }

  private val meta = Map("pgHost" -> "localhost", "pgPort" -> "5432", "dbName" -> "qod")

  "the banner" should "carry the REST line next to the SQL ACL line" in {
    val b = Banner.startup(
      meta,
      "0.0.0.0",
      20900,
      "0.0.0.0",
      31338,
      tlsEnabled = true,
      rest = Some(("0.0.0.0", 31339, true)),
      aclEnabled = true
    )
    b should include(
      "   REST (data)   : https://localhost:31339/api/v1  (PAT bearer, read-only)"
    )
    b.indexOf("REST (data)") should be < b.indexOf("SQL ACL")
  }

  it should "use http when the edge runs without TLS" in {
    Banner.restLine("127.0.0.1", 40000, tls = false, aclEnabled = true) should include(
      "http://127.0.0.1:40000/api/v1"
    )
  }

  it should "carry the ACL warning on the REST line when the ACL is disabled" in {
    val b = Banner.startup(
      meta,
      "0.0.0.0",
      20900,
      "0.0.0.0",
      31338,
      tlsEnabled = true,
      rest = Some(("0.0.0.0", 31339, true)),
      aclEnabled = false
    )
    b should include(
      "   REST (data)   : https://localhost:31339/api/v1  " +
        "(ACL DISABLED: any PAT of the tenant can read every table)"
    )
  }

  it should "print no REST line while the edge is disabled" in {
    Banner.startup(meta, "0.0.0.0", 20900, "0.0.0.0", 31338, true, aclEnabled = true) should not
    include("REST (data)")
  }

  "the boot warning" should "repeat the REST line only when the edge is on and the ACL off" in {
    Banner.restAclWarning(Some(("0.0.0.0", 31339, true)), aclEnabled = false) shouldBe Some(
      "REST (data)   : https://localhost:31339/api/v1  " +
        "(ACL DISABLED: any PAT of the tenant can read every table)"
    )
    Banner.restAclWarning(Some(("0.0.0.0", 31339, true)), aclEnabled = true) shouldBe None
    Banner.restAclWarning(None, aclEnabled = false) shouldBe None
  }

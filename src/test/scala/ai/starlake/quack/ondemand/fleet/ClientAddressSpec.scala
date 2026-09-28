package ai.starlake.quack.ondemand.fleet

import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers

import java.net.InetAddress

class ClientAddressSpec extends AnyFlatSpec with Matchers:
  private def ip(s: String): InetAddress = Cidr.parseAddress(s).get
  private val proxies                    = Cidr.parseList("192.168.1.0/24").toOption.get

  "resolve" should "use the peer and ignore X-Forwarded-For when the peer is not a trusted proxy" in {
    ClientAddress.resolve(Some(ip("203.0.113.9")), Some("10.0.0.1"), proxies) shouldBe
      Some(ip("203.0.113.9"))
    ClientAddress.resolve(Some(ip("203.0.113.9")), Some("10.0.0.1"), Nil) shouldBe
      Some(ip("203.0.113.9"))
  }

  it should "take the rightmost untrusted hop behind a trusted proxy" in {
    ClientAddress.resolve(Some(ip("192.168.1.1")), Some("10.0.0.7"), proxies) shouldBe
      Some(ip("10.0.0.7"))
    ClientAddress.resolve(
      Some(ip("192.168.1.1")),
      Some("10.0.0.7, 192.168.1.2"),
      proxies
    ) shouldBe Some(ip("10.0.0.7"))
  }

  it should "ignore spoofed entries left of the first untrusted hop" in {
    ClientAddress.resolve(
      Some(ip("192.168.1.1")),
      Some("127.0.0.1, 203.0.113.9"),
      proxies
    ) shouldBe Some(ip("203.0.113.9"))
  }

  it should "leave the address unknown on a malformed, missing or all-trusted header" in {
    ClientAddress.resolve(Some(ip("192.168.1.1")), Some("garbage"), proxies) shouldBe None
    ClientAddress.resolve(Some(ip("192.168.1.1")), Some("10.0.0.7, garbage"), proxies) shouldBe None
    ClientAddress.resolve(Some(ip("192.168.1.1")), None, proxies) shouldBe None
    ClientAddress.resolve(Some(ip("192.168.1.1")), Some(" "), proxies) shouldBe None
    ClientAddress.resolve(Some(ip("192.168.1.1")), Some("192.168.1.2"), proxies) shouldBe None
  }

  it should "be unknown without a peer" in {
    ClientAddress.resolve(None, Some("10.0.0.7"), proxies) shouldBe None
  }

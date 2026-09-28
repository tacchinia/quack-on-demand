package ai.starlake.quack.ondemand.fleet

import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers

import java.net.{Inet4Address, InetAddress}

class CidrSpec extends AnyFlatSpec with Matchers:
  private def ip(s: String): InetAddress = Cidr.parseAddress(s).get
  private def cidr(s: String): Cidr      = Cidr.parse(s).toOption.get

  "parseAddress" should "read IPv4 and IPv6 literals, with or without brackets" in {
    ip("10.0.0.5").getHostAddress shouldBe "10.0.0.5"
    ip("::1") shouldBe InetAddress.getByName("::1")
    ip("[fe80::1]") shouldBe InetAddress.getByName("fe80::1")
  }

  it should "return an IPv4-mapped IPv6 literal as IPv4" in {
    ip("::ffff:10.0.0.5") shouldBe a[Inet4Address]
    ip("::ffff:10.0.0.5").getHostAddress shouldBe "10.0.0.5"
  }

  it should "refuse host names and malformed literals without a DNS lookup" in {
    Cidr.parseAddress("localhost") shouldBe None
    Cidr.parseAddress("example.com") shouldBe None
    Cidr.parseAddress("300.1.1.1") shouldBe None
    Cidr.parseAddress("1.2.3") shouldBe None
    Cidr.parseAddress("") shouldBe None
    // These are shaped like the old Ipv6Literal regex (contains a ':') but start with '.', which
    // is not a hex digit or ':'. InetAddress.getByName only skips the resolver when the first
    // character is a hex digit or ':'; anything else, including these, falls through to a real
    // DNS lookup. The regex anchor below must reject them before getByName is ever called.
    Cidr.parseAddress(".:") shouldBe None
    Cidr.parseAddress(".1::1") shouldBe None
  }

  "parse" should "accept a CIDR or a bare address (a single-host block)" in {
    cidr("10.0.0.0/8").prefix shouldBe 8
    cidr("10.0.0.1").prefix shouldBe 32
    cidr("::1").prefix shouldBe 128
    cidr(" 10.0.0.0/8 ").toString shouldBe "10.0.0.0/8"
  }

  it should "name the bad entry" in {
    Cidr.parse("nope").left.toOption.get should include("'nope'")
    Cidr.parse("10.0.0.0/33").left.toOption.get should include("prefix")
    Cidr.parse("10.0.0.0/x").left.toOption.get should include("prefix")
    Cidr.parse("10.0.0.0/8/1").isLeft shouldBe true
  }

  "contains" should "match by prefix within one family only" in {
    cidr("10.0.0.0/8").contains(ip("10.200.3.4")) shouldBe true
    cidr("10.0.0.0/8").contains(ip("11.0.0.1")) shouldBe false
    cidr("10.0.0.0/12").contains(ip("10.15.255.255")) shouldBe true
    cidr("10.0.0.0/12").contains(ip("10.16.0.0")) shouldBe false
    cidr("0.0.0.0/0").contains(ip("::1")) shouldBe false
    cidr("::/0").contains(ip("::1")) shouldBe true
    cidr("::/0").contains(ip("10.0.0.1")) shouldBe false
    cidr("10.0.0.0/8").contains(ip("::ffff:10.1.2.3")) shouldBe true
  }

  "isEverything" should "be true only for a /0 block" in {
    cidr("0.0.0.0/0").isEverything shouldBe true
    cidr("::/0").isEverything shouldBe true
    cidr("10.0.0.0/8").isEverything shouldBe false
  }

  "parseList" should "drop blanks, so an empty value is the empty list" in {
    Cidr.parseList("") shouldBe Right(Nil)
    Cidr.parseList(" , ") shouldBe Right(Nil)
    Cidr.parseList("0.0.0.0/0,::/0").map(_.map(_.toString)) shouldBe Right(
      List("0.0.0.0/0", "::/0")
    )
  }

  it should "fail on the first bad entry" in {
    Cidr.parseList("10.0.0.0/8, bad, ::/0").left.toOption.get should include("'bad'")
  }

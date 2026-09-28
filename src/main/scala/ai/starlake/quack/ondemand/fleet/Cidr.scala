package ai.starlake.quack.ondemand.fleet

import java.net.InetAddress
import scala.util.Try

/** One CIDR block, IPv4 or IPv6. `contains` compares addresses of the block's own family only; an
  * IPv4-mapped IPv6 address already arrives as IPv4 (see [[Cidr.parseAddress]]), so an IPv4 block
  * matches a dual-stack socket's peer.
  */
final class Cidr private (network: Array[Byte], val prefix: Int, text: String):
  def contains(addr: InetAddress): Boolean =
    val a = addr.getAddress
    if a.length != network.length then false
    else
      val full = prefix / 8
      val rest = prefix % 8
      val head = (0 until full).forall(i => a(i) == network(i))
      if !head || rest == 0 then head
      else
        val mask = (0xff << (8 - rest)) & 0xff
        (a(full) & mask) == (network(full) & mask)

  /** A /0 block: every address of its family. */
  def isEverything: Boolean = prefix == 0

  override def toString: String = text

object Cidr:
  private val Ipv4Literal = """(\d{1,3})\.(\d{1,3})\.(\d{1,3})\.(\d{1,3})""".r
  private val Ipv6Literal = """(?=.*:)[0-9A-Fa-f:][0-9A-Fa-f:.]*""".r

  /** A literal IPv4 or IPv6 address, never a DNS lookup: `InetAddress.getByName` resolves host
    * names, so only strings shaped like a literal reach it, and IPv4 is built from its octets.
    * `InetAddress.getByName` only takes its literal-parsing path when the string starts with a hex
    * digit or ':', so the IPv6 branch requires exactly that (plus a ':' somewhere in the string)
    * before ever calling it; anything else (e.g. a string starting with '.') would otherwise fall
    * through to the system resolver. Surrounding brackets are accepted. The JDK returns an
    * IPv4-mapped IPv6 literal (`::ffff:10.0.0.5`) as an IPv4 address.
    */
  def parseAddress(raw: String): Option[InetAddress] =
    raw.trim.stripPrefix("[").stripSuffix("]") match
      case Ipv4Literal(a, b, c, d) =>
        val octets = List(a, b, c, d).map(_.toInt)
        if octets.forall(_ <= 255) then Some(InetAddress.getByAddress(octets.map(_.toByte).toArray))
        else None
      case s @ Ipv6Literal() => Try(InetAddress.getByName(s)).toOption
      case _                 => None

  /** `address/prefix`, or a bare address as a single-host block. */
  def parse(entry: String): Either[String, Cidr] =
    val t                      = entry.trim
    val (addrPart, prefixPart) = t.split("/", -1) match
      case Array(a)    => (a, None)
      case Array(a, p) => (a, Some(p))
      case _           => ("", None)
    parseAddress(addrPart) match
      case None       => Left(s"'$t' is not an IP address or CIDR")
      case Some(addr) =>
        val bytes = addr.getAddress
        val max   = bytes.length * 8
        prefixPart match
          case None    => Right(new Cidr(bytes, max, t))
          case Some(p) =>
            p.toIntOption.filter(n => n >= 0 && n <= max) match
              case Some(n) => Right(new Cidr(bytes, n, t))
              case None    => Left(s"'$t' has an invalid prefix length (0..$max)")

  /** A comma-separated list; blank entries are dropped, so "" and " , " are both empty. */
  def parseList(csv: String): Either[String, List[Cidr]] =
    csv
      .split(',')
      .toList
      .map(_.trim)
      .filter(_.nonEmpty)
      .foldLeft[Either[String, List[Cidr]]](Right(Nil)) { (acc, e) =>
        acc.flatMap(l => parse(e).map(l :+ _))
      }

  def anyContains(list: List[Cidr], addr: InetAddress): Boolean = list.exists(_.contains(addr))

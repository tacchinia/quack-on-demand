package ai.starlake.quack.edge.rest

import ai.starlake.quack.ondemand.fleet.Cidr

import java.net.{Inet6Address, InetAddress}

/** The client key the REST data edge throttles by (design §7.3,
  * `docs/superpowers/specs/2026-09-25-quack-rest-data-edge-design.md`): an HTTP-layer resource
  * control, never an identity or a grant.
  *
  * The key is the TCP peer. `X-Forwarded-For` is read only when that peer is inside
  * `trustedProxies`: it is then walked from the RIGHT, since every trusted proxy appends the
  * address it saw, and the first hop that is not itself a trusted proxy is the client; anything
  * left of it is whatever the client chose to send, and is never read. When every hop is trusted
  * the left-most one is taken. A header that is empty, or holds an unparsable hop on the way, falls
  * back to the peer. A client outside the trusted set therefore can never choose its key.
  *
  * This departs from the fleet heartbeat's [[ai.starlake.quack.ondemand.fleet.ClientAddress]] on
  * purpose: there the address GRANTS approval, so "unknown" must never match; here it only buckets
  * failures, so the peer (the proxy) is the safe fallback, and it is only reached through a header
  * that the trusted proxy itself wrote badly.
  *
  * Normalisation: an IPv4-mapped IPv6 address is its IPv4 address, and native IPv6 is keyed per /64
  * (a single subscriber usually holds a whole /64, so a per-address key would be free to rotate).
  */
object ClientAddress:

  /** The key of a request with no peer at all (only reachable off the wire, e.g. in a spec). */
  val Unknown = "unknown"

  /** The throttle key of one request. */
  def keyOf(peer: Option[InetAddress], forwardedFor: List[String], trusted: List[Cidr]): String =
    peer.map(normalise).fold(Unknown)(p => key(resolve(p, forwardedFor, trusted)))

  /** The key of one address: dotted IPv4, or the /64 of an IPv6 address as `a:b:c:d::/64`. */
  def key(addr: InetAddress): String =
    normalise(addr) match
      case v6: Inet6Address =>
        val b = v6.getAddress
        (0 until 4)
          .map(i => Integer.toHexString(((b(2 * i) & 0xff) << 8) | (b(2 * i + 1) & 0xff)))
          .mkString("", ":", "::/64")
      case v4 => v4.getHostAddress

  private def resolve(peer: InetAddress, forwardedFor: List[String], trusted: List[Cidr]) =
    if !Cidr.anyContains(trusted, peer) then peer
    else
      val hops = forwardedFor.flatMap(_.split(",", -1).toList).map(_.trim)
      if hops.forall(_.isEmpty) then peer
      else walk(hops.reverse, trusted).getOrElse(peer)

  /** Right to left: the first untrusted hop, else the left-most (all trusted); None as soon as a
    * hop on the way does not parse.
    */
  private def walk(fromRight: List[String], trusted: List[Cidr]): Option[InetAddress] =
    fromRight match
      case Nil       => None
      case h :: rest =>
        Cidr.parseAddress(h).map(normalise) match
          case None                                     => None
          case Some(a) if !Cidr.anyContains(trusted, a) => Some(a)
          case Some(a) if rest.isEmpty                  => Some(a)
          case Some(_)                                  => walk(rest, trusted)

  /** `::ffff:a.b.c.d` as `a.b.c.d`. The JDK already does this for a parsed literal; a socket peer
    * built from raw bytes may still arrive as an `Inet6Address`.
    */
  private def normalise(addr: InetAddress): InetAddress =
    addr match
      case v6: Inet6Address =>
        val b      = v6.getAddress
        val mapped = (0 until 10).forall(b(_) == 0) && b(10) == -1 && b(11) == -1
        if mapped then InetAddress.getByAddress(b.slice(12, 16)) else v6
      case other => other

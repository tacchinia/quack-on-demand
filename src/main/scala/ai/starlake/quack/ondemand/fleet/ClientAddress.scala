package ai.starlake.quack.ondemand.fleet

import java.net.InetAddress

/** The client address of a fleet heartbeat, the input of the auto-approve decision. */
object ClientAddress:

  /** `peer` itself unless it is a trusted proxy. Behind a trusted proxy, `X-Forwarded-For` is
    * walked from the right, skipping trusted hops: every trusted hop appends the address it saw, so
    * the first untrusted entry from the right is the client and anything left of it is whatever the
    * client chose to send. None (unknown, never auto-approved) when there is no peer, or behind a
    * trusted proxy when the header is missing, empty, made only of trusted hops, or holds an
    * unparsable entry before the first untrusted one. Falling back to `peer` there would be
    * exploitable: a deliberately malformed header would be judged by the proxy's own, usually
    * trusted, address.
    */
  def resolve(
      peer: Option[InetAddress],
      forwardedFor: Option[String],
      trusted: List[Cidr]
  ): Option[InetAddress] =
    peer.flatMap { p =>
      if !Cidr.anyContains(trusted, p) then Some(p)
      else
        val hops = forwardedFor.toList
          .flatMap(_.split(',').toList)
          .map(_.trim)
          .filter(_.nonEmpty)
          .reverse
        firstUntrusted(hops, trusted)
    }

  private def firstUntrusted(hops: List[String], trusted: List[Cidr]): Option[InetAddress] =
    hops match
      case Nil       => None
      case h :: rest =>
        Cidr.parseAddress(h) match
          case None                                    => None
          case Some(a) if Cidr.anyContains(trusted, a) => firstUntrusted(rest, trusted)
          case found                                   => found

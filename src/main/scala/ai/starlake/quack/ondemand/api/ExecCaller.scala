package ai.starlake.quack.ondemand.api

import ai.starlake.quack.ondemand.auth.TokenRestriction

/** Who is running a statement through the routed executor.
  *
  * This is a value type rather than three loose parameters so that adding a call site forces an
  * explicit decision about the restriction. The alternative considered was pinning an already
  * attenuated EffectiveSet on a connection context, which needs no signature change but makes the
  * security property rest on remembering to attenuate first. Here, omitting it does not compile.
  *
  * `patId` is carried but unpopulated by this task: a later task threads it into audit and
  * statement-history rows once a PAT-backed caller exists. Defaulting it to `None` here means that
  * later task only has to populate the field, not touch every one of these call sites again.
  *
  * `source` is the audit origin and `SessionOpened` channel the router records (quack-rest design
  * §2.2 constraint 5: the REST edge passes `"rest"`). `preferredNode` is the router's SOFT pin: the
  * REST edge sends its data statement to the node that answered its schema probe (§6.6), and a
  * vanished node falls back to the usual pick. Both default to what every call site had before they
  * existed, so no existing call site changes behaviour.
  */
final case class ExecCaller(
    connectionId: String,
    identity: String,
    restriction: TokenRestriction,
    patId: Option[String] = None,
    source: String = "flightsql",
    preferredNode: Option[String] = None
):
  /** The row cap actually applied: the server cap, the token's cap and the request's, smallest
    * wins. A token can lower the cap and can never raise it.
    */
  def effectiveMaxRows(serverCap: Int, requested: Int): Int =
    (List(serverCap, requested) ++ restriction.maxRows.toList).min.max(1)

object ExecCaller:
  def unrestricted(connectionId: String, identity: String): ExecCaller =
    ExecCaller(connectionId, identity, TokenRestriction.Unrestricted)

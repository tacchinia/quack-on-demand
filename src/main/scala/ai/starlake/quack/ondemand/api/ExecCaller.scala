package ai.starlake.quack.ondemand.api

import ai.starlake.quack.ondemand.auth.TokenRestriction

/** Who is running a statement through the routed executor.
  *
  * This is a value type rather than three loose parameters so that adding a call site forces an
  * explicit decision about the restriction. The alternative considered was pinning an already
  * attenuated EffectiveSet on a connection context, which needs no signature change but makes the
  * security property rest on remembering to attenuate first. Here, omitting it does not compile.
  *
  * `patId` is set for a PAT bearer by [[RestCaller]] (the catalog preview, data-diff, undrop and
  * restore REST endpoints) and by `McpDataTools.callerFor` (MCP). The routed executor
  * (`Main.routedExecutor`) threads it into `FlightSqlRouter.execute`, so the statement-history and
  * audit rows and the `ActiveStatementRegistry` entry carry it: that is what lets a revoke of the
  * token kill the statement. System and session callers carry `None`.
  *
  * `system` is the ONLY privilege signal: a system caller gets the synthetic superuser EffectiveSet
  * in the routed executor (no ACL, no CLS/RLS, never attenuated). It is set only by
  * [[ExecCaller.system]] at trusted internal sites (the static key, the restore dry run, the branch
  * change counter). `identity` is a label for logs and history; user names are not reserved (a
  * tenant may have a user called "superuser"), so nothing may decide privilege from it.
  *
  * `source` is the audit origin and `SessionOpened` channel the router records: `"flightsql"` for
  * every executor caller that predates the REST data edge, `"rest-data"` for that edge, so its
  * statements are attributed to it in statement history and audit the way the native front door's
  * are to `"quack"`. `edge` is the OPA input's `client.edge` only, independent of `source` so that
  * tagging the OPA input never moves metering or audit. `preferredNode` is the router's SOFT pin:
  * the REST edge sends its data statement to the node that answered its schema probe, and a
  * vanished node falls back to the usual pick. All three default to what every call site had before
  * they existed.
  *
  * `jwtRoles`, `jwtGroups` and `jwtClaims` are what a VERIFIED bearer JWT said about the caller,
  * handed to the handshake exactly as the FlightSQL edge hands its own: the roles and groups widen
  * the user's EffectiveSet by name inside the user's tenant, and the claims ride on it to the OPA
  * input. `superuserAdmissible = false` says the credential was validated by a tenant-scoped
  * authority, which cannot speak for a tenant-less superuser row of the same name; the handshake
  * then refuses that row. Empty and `true` are what every caller without a JWT had before.
  */
final case class ExecCaller(
    connectionId: String,
    identity: String,
    restriction: TokenRestriction,
    patId: Option[String] = None,
    system: Boolean = false,
    source: String = "flightsql",
    edge: String = "mcp",
    preferredNode: Option[String] = None,
    jwtRoles: Set[String] = Set.empty,
    jwtGroups: Set[String] = Set.empty,
    jwtClaims: Map[String, String] = Map.empty,
    superuserAdmissible: Boolean = true
):
  /** The row cap actually applied: the server cap, the token's cap and the request's, smallest
    * wins. A token can lower the cap and can never raise it.
    */
  def effectiveMaxRows(serverCap: Int, requested: Int): Int =
    (List(serverCap, requested) ++ restriction.maxRows.toList).min.max(1)

object ExecCaller:

  /** A trusted internal caller: the static key or a system leg. Labelled with
    * [[CatalogPreviewHandlers.SuperuserIdentity]] for logs; the privilege is the flag.
    */
  def system(connectionId: String): ExecCaller =
    ExecCaller(
      connectionId,
      CatalogPreviewHandlers.SuperuserIdentity,
      TokenRestriction.Unrestricted,
      system = true
    )

  def unrestricted(connectionId: String, identity: String): ExecCaller =
    ExecCaller(connectionId, identity, TokenRestriction.Unrestricted)

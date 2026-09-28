package ai.starlake.quack.ondemand.auth

/** Authorization envelope attached to a UI session.
  *
  * `superuser = true` => cross-tenant; `manageableTenants` is ignored. Otherwise the session is
  * confined to the listed tenants by the per-request `tenant_forbidden` guard.
  *
  * `superuser = false && manageableTenants.isEmpty` is intentionally rejected at login
  * (`admin_required`); the type allows it so tests can construct an "empty" scope.
  */
final case class SessionScope(superuser: Boolean, manageableTenants: Set[String])

object SessionScope:
  /** Convenience: a superuser scope (no tenant restriction). */
  val Superuser: SessionScope = SessionScope(superuser = true, manageableTenants = Set.empty)

  /** No privilege at all: not a superuser, no manageable tenant. What [[failClosed]] answers for a
    * token that does not resolve, so every scope gate refuses it and every tenant filter is empty.
    */
  val NoAccess: SessionScope = SessionScope(superuser = false, manageableTenants = Set.empty)

  /** The token-to-scope lookup handed to handlers. Handler gates read `None` as "static key,
    * unrestricted", so `None` must mean exactly that: it is returned only for the configured static
    * key (constant-time compare). Any other token that `lookup` cannot resolve gets [[NoAccess]].
    *
    * Load-bearing because the guard and the handler resolve the token at different instants: a
    * session that expired (or was revoked) after the guard admitted it used to reach the handler as
    * `None`, i.e. as the static key, and a tenant admin got superuser treatment (manifest export,
    * every tenant in the listings, the RBAC tenant-scope gates).
    */
  def failClosed(
      staticKey: Option[String],
      lookup: String => Option[SessionScope]
  ): String => Option[SessionScope] =
    val key = staticKey.filter(_.nonEmpty).map(_.getBytes(java.nio.charset.StandardCharsets.UTF_8))
    token =>
      val isStatic = key.exists(k =>
        java.security.MessageDigest
          .isEqual(token.getBytes(java.nio.charset.StandardCharsets.UTF_8), k)
      )
      if isStatic then None else Some(lookup(token).getOrElse(NoAccess))

  /** The telemetry tenant-scoping rule, shared by the audit / history / usage / active-statement
    * handlers (`None` result = no tenant restriction):
    *   - superuser, or `None` (the static key; see [[failClosed]], which never yields `None` for
    *     any other token): the requested tenant filter passes through unchanged;
    *   - tenant admin: pinned to `manageableTenants`; a requested tenant narrows WITHIN them, and a
    *     non-manageable request falls back to the full manageable set (no error, so a foreign
    *     tenant's existence never leaks through a differential response).
    */
  def tenantsFilter(
      scoped: Option[SessionScope],
      requested: Option[String]
  ): Option[Set[String]] =
    scoped match
      case Some(s) if !s.superuser =>
        Some(
          requested.filter(s.manageableTenants.contains).map(Set(_)).getOrElse(s.manageableTenants)
        )
      case _ =>
        requested.map(Set(_))

# Read-only REST data edge (`quack-rest`): design

- **Issue:** starlake-ai/quack-on-demand#120
- **Status:** DRAFT. This is a design only; nothing is implemented yet. Items marked **PARKED**
  (§2.3) or **SPIKE** (§2.4) must be closed before the implementation phase that depends on them
  (§12).
- **Date:** 2026-09-25. Code references were checked against tree `6c69f55`.

## 0. How to read this document

| Sections | Content |
|---|---|
| §1 | Scope |
| §2 | Every structural decision, who took it, and what is still open |
| §3 to §9 | The design |
| §10 | Threat model |
| §11 | Test design |
| §12 | Phased implementation plan, with its gates |
| §13 | Code-style notes for the implementor |

Every statement about existing code carries a `file:line` reference. Where reading the code could
not settle how something behaves, it is listed as a **SPIKE** rather than assumed. Paths are
relative to `src/main/scala/ai/starlake/quack/` unless stated otherwise.

The draft was reviewed by an independent adversarial security pass and an independent test-design
pass. Their findings are merged into the relevant sections, not appended.

---

## 1. Scope

### Goals

1. Give read-only HTTP access to the tables and views of a tenant-db **of any kind registered on
   QoD** (`ducklake`, `duckdb-file`, `memory`; see §6.7), discovered dynamically with
   no per-endpoint configuration. To expose a curated contract, create a view and grant it.
2. Run every data statement through the **same** policy pipeline as FlightSQL, the Quack door and
   MCP. That pipeline covers ACL, CLS, RLS, filtered metadata, PAT attenuation, pool kill switches
   and hibernation resume. The edge adds no policy and removes none.
3. Be safe to expose directly to the internet:
   - no superuser path;
   - no SQL text controlled by the user;
   - no unbounded use of resources;
   - configuration that fails closed;
   - nothing touched before the caller is authenticated **and** authorized.
4. Stay consistent with the existing doors: a separate port, a config block with `QOD_*`
   overrides, Tapir on Ember, the `ErrorResponse` envelope, and wiring through Main and
   `ShutdownCoordinator`.

### Non-goals (v1)

- **Out of scope for this feature:**
  - any write (POST, PUT, PATCH or DELETE);
  - raw SQL over HTTP (the async statements API the issue mentions belongs in a separate issue);
  - exact counts, ETag or caching;
  - embedded resources, joins and aggregates.
- **Deferred:**
  - **Branch reads** are parked (P-2). The `branch` parameter name is reserved now.
  - **Federated and attached catalogs** are parked (P-5). v1 exposes only the tenant-db's own
    session catalog, whose name depends on the kind (§6.7).
  - **Branch tenant-dbs** (rows with `branchOf` set) cannot be addressed through the `database`
    path segment in v1. That follows from parking branch reads (P-2).
  - **Parquet output** is parked (P-1).

---

## 2. Decisions and open items

The issue's eight open questions are addressed to the upstream maintainer, who **has not answered
yet**. The issue author, who will also implement the feature, took the decisions below during the
design interview. Each stays **proposed** until the maintainer confirms it on #120. If the
maintainer answers differently, only the sections listed under "Impacts" re-open.

### 2.1 Decided by the author, pending maintainer confirmation

| # | Question (issue #120) | Decision | Impacts |
|---|---|---|---|
| Q2 | How a PAT is admitted to REST | **Explicit opt-in.** A new PAT axis, `restAccess`, defaults to `false`, so an existing PAT can never be replayed against the new door. | §5.2, §8.2 |
| Q3 | X-API-Key or HTTP Basic | **Neither.** Only `Authorization: Bearer <PAT or tenant OIDC JWT>`. | §5.1 |
| Q4 | ACL disabled | **Refuse to boot** when `quack-rest.enabled` is set and `acl.enabled=false`. | §7.2 |
| Q5 | Flag masked columns | **No flag.** Table detail shows only what the caller would receive. | §6.3 |
| Q7 | Branch reads | **Later.** `branch` is reserved, and in v1 it returns 400 `unsupported_parameter`. | P-2 |
| Q8 | JSON shape | **Array of objects**, with metadata in headers. | §6.5 |
| - | Abuse protection | **Minimal built-in controls:** a per-IP failed-auth throttle, a per-user concurrency cap and hard size, time and row caps. Volumetric rate limiting belongs to the reverse proxy or WAF, and the operator documentation says so. | §7.3, §7.4 |
| - | Endpoint declaration | **Tapir**, in their own endpoint object, served on the new port, **plus a published OpenAPI** document at `GET /api/v1/openapi.json`. These endpoints are not registered in `EndpointModules`, so the CLI-parity, MCP-coverage and admin-OpenAPI guards do not apply to them. | §6.6 |
| - | Statement attribution | **Add a `source` column** to `qodstate_stmt_history` and a `source` tag to the statement metrics. Values are `rest`, `flightsql`, `quack` and `mcp`. | §8.1 |
| - | Client IP behind a proxy | **A trusted-proxy CIDR list.** `X-Forwarded-For` is honoured only when the socket peer is in `trustedProxies`, which is empty by default. | §7.3 |

### 2.2 Designer defaults (not raised in the interview; reviewer to confirm)

| # | Question | Default | Why |
|---|---|---|---|
| Q1 | Path prefix and versioning | `/api/v1/tenant/{tenant}/database/{tenantDb}/...` on the dedicated port, as the issue proposes. | A separate listener cannot collide with the manager's `/api`, and `v1` leaves room for a breaking change later. |
| Q6 | A column whose name is a reserved parameter | **Cannot be filtered in v1.** This is documented; the workaround is a view that renames the column. | Every escape syntax adds grammar for a rare case, and one can be added later without breaking anything. |
| - | JWT issuers accepted | **Only the path tenant's own OIDC provider** (`TenantOidcRegistry.forTenant`). The global HS256 `jwt` provider and the global OIDC providers are **not** accepted on REST. | A global IdP's usernames are not tenant-scoped, so the path would choose the tenant (§5.3). See P-9. |
| - | Non-DuckLake tenant-dbs (`duckdb-file`, `memory`) | Served, with the same endpoints, grammar, formats and policies. `asOf*` returns 400 `time_travel_unsupported` (only after authorization, §4). No snapshot header is sent, and pagination is only best-effort. The full rules per kind are in §6.7. | These databases have no snapshots, but reads are still useful. |
| - | Unknown query parameter | 400 `unknown_column`, never silently ignored. | A typo in a filter must not return a bigger, unfiltered result. |
| - | Token in the URL (`?access_token=`) | Never accepted. | URLs end up in proxy logs, browser history and `Referer` headers. |
| - | Duplicate column in `select` | 400 `invalid_parameter`. | A JSON object cannot carry two keys with the same name. |

### 2.3 PARKED: explicitly open, each with a default recommendation

| Id | Item | Recommendation | Blocks |
|---|---|---|---|
| P-1 | **Parquet output.** No Parquet writer is on the classpath: `project/Dependencies.scala` has Arrow but not parquet-java. The options are to add parquet-java (a heavy, Hadoop-shaped dependency), to have the node run `COPY ... TO` into a temporary object and stream it back, or to defer. | **Defer to v1.1.** v1 serves `json`, `csv` and `arrow`; `format=parquet` returns 406. | nothing |
| P-2 | Branch reads (`branch=<name>`) | Add in v1.1, using the same resolution as FlightSQL: `BranchService.resolveTarget`, called after authorizing the parent pool (`Main.scala:1198-1219`). | nothing |
| P-3 | Maintainer confirmation of §2.1 and §2.2 | Phases P0, P1 and P3 of §12 do not depend on those decisions and can start now. | P2, P4 and later phases |
| P-4 | **Durable node-side cancellation.** The token timeout in `routedExecutor` is a bounded wait, not a cancellation (`Main.scala:1507-1520`, see the comment at `:1496-1506`). The node cancel handle is attached only once streaming starts (`edge/ActiveStatementRegistry.scala`, `attachCancel`). | Do not add cancellation in this feature. Mitigation: the per-user slot is released only when the node call has really finished (§7.4), so repeated timeouts cannot pile up orphaned work on the node. | nothing |
| P-5 | Exposing federated or attached catalogs (`sql` sources and typed `iceberg_rest` sources, which are attached to a tenant-db's nodes and governed by ACL through their alias) | **Out of scope for v1** (author's decision). Every name is qualified with the session catalog (§6.7), so these catalogs can be neither addressed nor listed. Adding them needs three things. (1) A path level, `.../database/{tenantDb}/catalogs/{alias}/schemas/...`. (2) A filtered listing for attached catalogs: `MetadataFilterRewriter.readGrants` only admits grants on the session catalog (`edge/meta/MetadataFilterRewriter.scala:223-228`), so this needs either an extension there or a listing derived from the `EffectiveSet`. (3) A spike on `AT (VERSION => n)` against Iceberg and on the source's reachability (a source that cannot be reached leaves its catalog absent on a node that is otherwise healthy). Reads would then carry no QoD snapshot unless that spike says otherwise. | nothing |
| P-6 | Throttle state across HA replicas | Keep it per replica, in memory, and document it. With N replicas, the effective failed-auth budget is N times the configured value. | nothing |
| P-7 | A requests-per-second budget per user | Later. The `qodstate_pat.rate_per_min` column is already reserved and unused (`src/main/resources/db/changelog/0033-pat-scope.yaml:24-25`). | nothing |
| P-8 | Advertising the REST port in `/api/config/client` (for the UI connect panel and the CLI) | Only if the UI grows a REST connect snippet. | nothing |
| P-9 | **Binding JWTs to an immutable claim.** Today the username is `preferred_username`, then `email`, then `sub`, and `email_verified` is not checked (`edge/auth/OidcBearerAuthenticator.scala:96-100`). Within the tenant's own IdP this is the trust FlightSQL already places in that IdP. | For v1, accept the tenant IdP as FlightSQL does, and document that `preferred_username` must not be user-editable in that IdP. Later: bind `sub` to the SCIM `external_id` (Liquibase `0036`). Also later: whether global-provider JWTs should ever be accepted, and if so with a mandatory tenant claim. | nothing |

### 2.4 SPIKES: verify in code before the dependent phase

Each spike ends in a test that stays in the suite.

| Id | Question | Why it matters | Blocks |
|---|---|---|---|
| S1 | Do `ColumnPolicyRewriter` and `RowPolicyRewriter` **keep** `AT (VERSION => n)` on a three-part table reference **and** still apply the policy? The ACL parser strips the clause before parsing (`ai/starlake/acl/parser/SqlParser.scala:47-60`); the CLS and RLS parsers have not been checked. | Every `/rows` statement carries `AT` (§6.4). If a rewriter drops the clause, pages become inconsistent. If it cannot parse the clause, the statement is denied fail-closed and no data is returned. The worst case is a rewriter that parses the statement but skips the policy. | P4 |
| S2 | Does DuckLake accept `AT (VERSION => n)` on a **view**? | Consistent pagination over views. If views do not support it, they are served at the current snapshot, `asOf*` on a view returns 400 `time_travel_unsupported`, and no snapshot header is sent. That weakens the contract for exactly the objects §1 recommends exposing, so the maintainer must be told either way. | P4 |
| S3 | After rewriting, does a `WHERE` or `ORDER BY` on a CLS-masked column act on the **raw** value or the **masked** one? | If it acts on the raw value, a filter such as `ssn=like.123*` becomes an oracle on the unmasked data, observable from which rows come back. If so, the renderer places filters and ordering **outside** a derived table whose inner `SELECT *` the CLS rewriter masks, and S3 must prove that the inner `*` really is masked. If that cannot be made safe, masked columns are marked non-filterable in the probe (§6.3) and filtering on them returns 400 `invalid_filter`, while detail still does not reveal the masking (Q5). | P4 |
| S4 | Does `AuthenticationService.authenticateBearer` return `Left` when the chain is empty? How is `exp` exposed? It is flattened as a `Date.toString` in `AuthenticatedProfile.claims`, not as epoch seconds. | `EdgeHandshake` **trusts the client** when no provider is configured (`edge/EdgeHandshake.scala:89`). The JWT and OIDC authenticators accept a token that has no `exp`. The REST edge must not inherit either behaviour. | P5 |
| S5 | Does `MetadataFilterRewriter` narrow the exact `information_schema` queries of §6.2 **for each of the three kinds** (session catalog `<catalogAlias>` or `memory`), with no row leaking from other attached catalogs? | Listings must show only readable objects. The admin REST catalog handlers (`ondemand/api/CatalogHandlers.scala:84,91`) do **not** filter per grant, so they must not be reused here. | P5 |
| S6 | Which Ember settings does http4s 0.23.24 (`project/Versions.scala:4`) provide: `withMaxHeaderSize`, `withRequestHeaderReceiveTimeout`, `withIdleTimeout`, `withMaxConnections`? Is the URI length bounded by the header limit? | Protection against slowloris and oversized requests (§7.1). | P5 |
| S7 | Does the **fully qualified** `"<catalog>"."<schema>"."<name>"` form (§6.7) confine every statement to the tenant catalog, for each kind? That includes `memory`, whose session catalog *is* DuckDB's built-in `memory` catalog (`model/DuckDbCatalogs.scala:13`). Is there no resolution into any other built-in catalog or system schema? | §6.4 relies on the three-part form to confine every statement to the tenant catalog. `USE` only sets a default; it does not constrain resolution. | P4 |
| S8 | What does a pathological LIKE pattern (4 KiB, many wildcards, with and without `ESCAPE`) cost on the pinned DuckDB, measured? | Node CPU is shared by the whole pool, and P-4 means no cancellation. The measurement sets the wildcard cap in §6.4. | P3 |

---

## 3. Architecture

```
                         :31339 (quack-rest, TLS by default)
client --HTTPS--> RestEdgeServer (Ember + security middleware)
                    |  1. method / size / body gates           -> 400/405/414/431
                    |  2. ClientAddress (peer / trusted XFF)
                    |  3. AuthThrottle.check(ip)                -> 429
                    |  4. Tapir decode (RestEdgeEndpoints)       -> 400/404
                    v
                  RestEdgeHandlers
                    |  5. RestAuth.authenticate(bearer, tenant)        -> 401/403
                    |  6. resolve tenant-db + pool (in-memory registry only)
                    |  7. authorize: authorizeHandshake(.., superuserAdmissible=false) -> 403/404
                    |  8. UserLimiter.acquire(tenant, userId)           -> 429
                    |  9. SnapshotSelector (control-plane JDBC)         -> 400/404/410/422
                    | 10. schema probe  (SELECT * ... LIMIT 0)  --+
                    | 11. RowsQuery.parse -> RowsSql.render        |  both through
                    | 12. data statement                         --+  RoutedExecutor (source="rest")
                    v
                  RestResultEncoder (json | csv | arrow), streamed; one finalizer closes the
                  Routed and releases the slot
```

The feature lives in package `ai.starlake.quack.edge.rest`, laid out like `edge/quack/`:

| File | Responsibility | Pure? |
|---|---|---|
| `RestEdgeServer.scala` | Ember shell: TLS, limits, security headers, CORS, request id. `start`/`stop` have the same shape as `QuackFrontDoorServer`. | no |
| `RestEdgeEndpoints.scala` | Tapir endpoint values and the OpenAPI document. | yes |
| `RestEdgeHandlers.scala` | Request orchestration, steps 5 to 12. | no |
| `RestAuth.scala` | Turns a bearer into a `RestPrincipal` (PAT or tenant JWT). | no (I/O injected) |
| `RowsQuery.scala` | Query parameters to an AST, with every check that needs no schema. | **yes** |
| `RowsSql.scala` | AST plus probed schema to SQL text. The only place `/rows` SQL is built. | **yes** |
| `RestResultEncoder.scala` | Encodes an `ArrowReader` into a byte stream per format. | yes (over a reader) |
| `AuthThrottle.scala`, `UserLimiter.scala`, `ClientAddress.scala` | Abuse controls (§7). | yes (clock injected) |

Outside the package:

- `RestEdgeConfig` goes in `Config.scala`, next to `QuackNativeConfig`.
- Wiring goes in `Main.scala`, next to the Quack door (`Main.scala:1230-1268`).
- `routedExecutor` is **extracted** from `Main` into a class, `ondemand/api/RoutedExecutor.scala`
  (§8.1). This is a prerequisite: its security properties are otherwise untestable (§11).

---

## 4. Request lifecycle (normative)

The steps run in this order; each one either continues or answers with the status listed.

**Invariant:** until step 7 has succeeded, the edge makes **no catalog read, no node call, no pool
wake-up and no snapshot lookup**. Steps 5 and 6 touch only the credential store and the in-memory
registry. The table in §10 depends on this ordering.

1. **Gates.**
   - `GET` is accepted. `OPTIONS` is accepted only when CORS is configured. Everything else,
     `HEAD` included, gets 405 with `Allow`.
   - A `GET` carrying `Content-Length > 0` or `Transfer-Encoding` gets 400 with
     `Connection: close`, because an unread body could desynchronise a reused proxy connection.
   - Ember returns 431 for oversized headers and 414 for an oversized URI (§7.1).
2. **Client address.** Determined as in §7.3.
3. **Throttle.** An IP that is currently blocked gets 429 `too_many_auth_failures` with
   `Retry-After`, before any credential is examined.
4. **Decode.** Path segments must match QoD's identifier rule, otherwise 404. A malformed name
   cannot exist, so this is not a 400.
5. **Authenticate** (§5). Failure gives 401 `unauthorized`, or 403 for `tenant_forbidden` /
   `rest_access_denied`. Every one of these counts toward the throttle (§7.3).
6. **Resolve the tenant-db and pool** from the in-memory registry. The pool is the `pool` parameter
   if given, otherwise `PoolPicks.readPoolKey` (`ondemand/api/PoolPicks.scala:26`).
   - A tenant-db that is unknown, outside the PAT `databases` axis, or a branch (`branchOf` set,
     P-2) gets 404 `not_found`.
   - An unknown pool also gets 404 `not_found`.
7. **Authorize**, before doing anything else. The edge calls
   `authorizeHandshake(tenant, pool, username, jwtRoles, jwtGroups, superuserAdmissible = false)`
   (`ondemand/PoolSupervisor.scala:3221`) and checks the PAT `pools` axis.
   - A user that is not provisioned or is disabled, a disabled tenant or pool, or a superuser row
     gets 403 `forbidden`, with one fixed message.
   - The resulting `EffectiveSet` is passed on to the executor, so it is not authorized a second
     time (§8.1).
8. **Acquire the per-user slot** (§7.4). If none is free, 429 `too_many_requests`.
9. **Resolve the snapshot**. This applies only to DuckLake, on `/rows` and on table detail.
   - Resolution uses `SnapshotSelector.resolve` with `DuckLakeCatalogReader`
     (`ondemand/catalog/DuckLakeCatalogReader.scala:241`) over control-plane JDBC. No node is
     involved.
   - Errors are mapped by `SnapshotSelector.httpError`, except that a tag-not-found message never
     echoes the tag.
   - This step runs after authorization, so it reveals nothing to a caller who has no access to the
     pool.
10. **Probe the schema** (§6.3). A missing object and an unreadable object both get 404
    `not_found`, with the same body and headers.
11. **Parse and render** (§6.4). Invalid input gets 400.
12. **Execute and stream** (§6.5).
    - A resuming pool gets 503 `pool_resuming` with `Retry-After`.
    - A timeout gets 504 `statement_timeout`.
    - A node error gets 502 `upstream_error`, with a generic message and the request id.

Every response carries `X-Request-Id`, a freshly generated UUID. That includes errors generated by
Ember and Tapir themselves. A request id supplied by the client is never echoed. The error envelope
is the existing `ErrorResponse(error, message)` (`ondemand/api/Dtos.scala:299`).

---

## 5. Authentication

### 5.1 Credential intake

- Only the `Authorization` header is read.
  - The scheme must be `Bearer` (matched case-insensitively), followed by one space and a token of
    1 byte to 8 KiB.
  - A request with more than one `Authorization` header gets 401.
  - Cookies, `X-API-Key`, `Basic` and any token in the query string are never read.
- A token beginning with `qod_pat_` (`PatStore.TokenPrefix`, `ondemand/state/PatStore.scala:425`)
  takes the **PAT path**. Any other token takes the **JWT path**. The prefix is checked before any
  I/O, and there is no fallback from one path to the other.
- Every 401 has the same body: `{"error":"unauthorized","message":"missing or invalid credential"}`.
  It is the same for an unknown tenant, a bad, expired or revoked token, a disabled owner and a
  superuser token.
- **Accepted residual:** the JWT path answers faster for an unknown tenant, because no provider
  runs. Tenant names are not secret: they appear in URLs and connection strings. Nimbus rate-limits
  JWKS refetches per provider on an unknown `kid` (at most once every 30 s by default), so random
  `kid` values cannot cause a fetch storm.

### 5.2 PAT path

1. `PatAuthenticator.resolve(token)` (`ondemand/auth/PatAuthenticator.scala:72`) checks the hash,
   revocation, expiry and that the owner is enabled. If it returns `None`, the answer is 401.
2. **No superuser.** An owner with `user.tenant == None` gets 401, not 403, so a caller cannot tell
   that they hold a valid superuser token.
3. **Tenant binding.** The owner's tenant must equal the path tenant after the lower-casing the
   registry applies. Otherwise the answer is 403 `tenant_forbidden`. The same answer is given for a
   path tenant that does not exist.
4. **Opt-in.** A PAT with `restriction.restAccess == false` gets 403 `rest_access_denied`.
5. The resulting principal is:
   `RestPrincipal(userId, username, tenant, patId = Some(id), restriction, jwtRoles = ∅, jwtGroups = ∅)`.

**`last_used_at` write amplification.** `PatStore.verify` runs an `UPDATE ... RETURNING` on every
call (`PatStore.scala:207-221`). The change is to stamp `last_used_at` only when the stored value is
more than 60 s old, using one conditional `UPDATE`, or a `SELECT` followed by a conditional
`UPDATE`. This benefits MCP as well. Without it, a valid token can drive a row lock and a WAL write
per request on the control-plane pool.

**New PAT axis `restAccess: Boolean = false`** on `TokenRestriction`:

- Liquibase `00NN-pat-rest-access.yaml` adds `rest_access BOOLEAN NOT NULL DEFAULT false` to
  `qodstate_pat`. Existing rows read `false`, which is what makes the axis opt-in.
- `TokenRestriction.narrow`: a child may be `true` only when its parent is `true`. Any other
  combination returns `Left("restAccess")`. This is the opposite direction to `branchOnly`, because
  `restAccess` widens what a token can do.
- **Root mints.** A PAT minted from a login session has `TokenRestriction.Unrestricted` as its
  parent (`ondemand/api/PatHandlers.scala:176-183`). Under the rule above such a PAT could never
  carry `restAccess`.
  - Add a dedicated constant, `TokenRestriction.SessionRoot = Unrestricted.copy(restAccess = true)`,
    used **only** as the parent of session-rooted mints.
  - `Unrestricted` keeps `restAccess = false`, so no other code that builds `Unrestricted` is opted
    in silently.
- **Surfaces:**
  - REST `POST /api/auth/pat/create`: a new `restAccess` field (DTO in `ondemand/api/Dtos.scala`).
  - The MCP PAT-minting tool (`mcp/McpPlatformTools.scala:445`, next to `branch_only`).
  - The CLI: `qod auth pat create --rest-access` (`cli/src/qod_cli/commands/auth.py:137,170`, next
    to `--branch-only`).
  - The UI PAT form, if it exposes scope axes.
  - PAT listing shows the flag.
  - Regenerate `cli/tests/resources/openapi.yaml` with `GenOpenApi`, and keep the CLI parity test
    green.

### 5.3 JWT path

1. Resolve the path tenant with `sup.getTenant`. An unknown tenant gets 401, with the same body as
   §5.1.
2. **Only the tenant's own OIDC provider** (`TenantOidcRegistry.forTenant(tenantId)`,
   `edge/auth/TenantOidcRegistry.scala:36-66`) validates the token. If the tenant has none, the
   answer is 401.
   - `AuthenticationService.bearerChainFor(Tenant)` is **not** reused: it keeps every global
     provider (the HS256 `jwt` provider and the global OIDC ones) and prepends or swaps in the
     tenant provider (`edge/auth/AuthenticationService.scala:127-145`).
   - A global IdP's `alice` is not tenant B's `alice`, and the URL path must not be what binds
     them (P-9).
   - `EdgeHandshake` is not used either, because of its trust-the-client branch (S4).
3. **REST-only hardening**, applied on top of the provider's checks:
   - An `exp` claim is required. A token without one gets 401, even though the authenticators
     accept such tokens (S4 decides how `exp` is read).
   - Signature, algorithm, JWKS, issuer and audience checks are exactly those of
     `OidcBearerAuthenticator`: Nimbus, with `aud` required to contain the clientId.
   - Nimbus also enforces `exp`/`nbf` with its default 60 s clock skew. `iat` is not checked, and
     REST does not add a check for it.
4. The principal is the validated username together with the provider's `jwtRoles`/`jwtGroups`,
   with `patId = None` and `restriction = TokenRestriction.Unrestricted`. A JWT has no PAT axes, and
   the ACL still applies in full.
5. **Tenant binding** comes from §4 step 7: `authorizeHandshake` looks the user up **inside the
   path tenant**, with `superuserAdmissible = false`. As with FlightSQL, the IdP user must be
   provisioned in QoD, through SCIM or `user/create`.

---

## 6. Endpoints

Every endpoint is a `GET` under `/api/v1/tenant/{tenant}/database/{tenantDb}`.

### 6.1 Parameter vocabulary

- **Reserved names:** `select`, `order`, `limit`, `offset`, `asOf`, `asOfTag`, `asOfTs`, `pool`,
  `format` and `branch` (reserved for P-2).
  - Reserved names are matched **exactly and case-sensitively**, after the HTTP layer has
    percent-decoded them once. `LIMIT` is therefore a column filter, which fails unless such a
    column exists.
  - A reserved parameter given twice gets 400 `invalid_parameter`.
- Any other parameter name is a column filter.
  - A column filter may repeat, and the repeats are combined with AND.
  - Parameter names that appear in error messages or logs are sanitised: printable ASCII only,
    truncated to 64 characters.
- Percent-decoding happens exactly once, in the HTTP layer. `+` is a literal plus, not a space.
  An invalid `%` sequence gets 400. Values are never decoded a second time.

### 6.2 `GET .../schemas` and `GET .../schemas/{schema}/tables`

- Both endpoints run a fixed-template `information_schema` SELECT through `RoutedExecutor`. The only
  values interpolated into it are the catalog alias and the schema name, each passed through
  `SqlLiterals.duckdbLiteral` (`model/SqlLiterals.scala`).
- `MetadataFilterRewriter` narrows the rows to what the principal can read (S5).
- `schemas` returns `[{"name": ...}]`, excluding `information_schema` and `pg_catalog`.
- `tables` returns `[{"name": ..., "type": "table" | "view"}]`.
  - A schema with no visible object gets **404**, not `[]`.
  - `information_schema` and `pg_catalog` as a path schema get 404 on every endpoint.
- Each listing is capped at `maxRows`. A listing that hits the cap sets `X-QoD-Truncated: true`.

### 6.3 `GET .../schemas/{schema}/tables/{table}` (detail) and the schema probe

The **schema probe** is `SELECT * FROM "<catalog>"."<schema>"."<table>" [AT (VERSION => n)] LIMIT 0`.
It runs through `RoutedExecutor`, and its Arrow schema is the column contract **after policy**:

- columns dropped by CLS are absent;
- masked columns carry their masked type;
- an ACL denial, `NotFound` and `BadRequest` all collapse to 404 `not_found`, with one body and the
  same number of executor and store calls on every branch.

The detail endpoint returns `{"name", "type", "columns": [{"name", "type"}]}`, where `type` is the
DuckDB type name mapped from the Arrow field. There is no masked flag (Q5).

For `/rows`, the probe runs first and its schema is the **only** source of identifiers, both for
output and for validation. The probe and the data statement carry **the same** `AT (VERSION => id)`,
and that id is the `X-QoD-Snapshot` value.

This costs one extra node round trip per `/rows`, which is accepted for v1. A later optimisation
could cache the probe for a short time, keyed by
`(tenant, userId, tenantDb, schema, table, snapshot)`.

### 6.4 `GET .../rows`: grammar and SQL rendering

**Grammar** (applied to values already percent-decoded once):

```
select   := col ("," col)*                     -- default: all probed columns, probe order
order    := term ("," term)*
term     := col ["." ("asc"|"desc")] ["." ("nullsfirst"|"nullslast")]
filter   := <col> "=" ["not."] op "." value
op       := eq | neq | gt | gte | lt | lte | like | ilike | in | is
value    := raw text (eq..lte, like, ilike) | "(" item ("," item)* ")" (in) | null|true|false (is)
item     := bare | '"' quoted '"'              -- quoted: \" and \\ escapes, allows ',' and ')'
limit    := [1-9][0-9]{0,9} and <= 2^31-1     offset := "0" | [1-9][0-9]{0,9} and <= 2^31-1
```

**Validation.** Validation fails closed: every failure gets 400, and the message names the parameter
but never its value.

- **Column matching** uses ASCII lower-casing only (`toLowerCase(Locale.ROOT)` on ASCII input)
  against the probed schema. It is not Unicode `equalsIgnoreCase`, which folds characters such as
  U+212A that DuckDB does not.
  - The identifier rendered into the SQL is always the probed spelling.
  - An unknown or ambiguous column gets `unknown_column`.
- **Typing** is checked against the probed Arrow type:
  - **Integers:** the value is parsed as a plain decimal integer, range-checked against the column
    type, and rendered from the validated text.
  - **Decimal and floating-point:** the grammar is `-?digits[.digits]` with **no exponent**, and the
    precision and scale must fit. This stops `1e999999999`, which `BigDecimal` would accept and
    `toPlainString` would expand to about 1 GB.
  - **Booleans:** `true` or `false` only.
  - **Dates and timestamps:** ISO-8601, parsed with `java.time`.
  - **Strings:** passed through as-is.
  - **NUL:** a NUL byte anywhere gets 400.
- **Operator applicability:**
  - `like` and `ilike` apply only to string columns.
  - Struct, list, map, union, blob and interval columns are **not filterable or orderable** in v1
    (`invalid_filter`), but they can be selected.
- **`like` and `ilike` patterns:**
  - `*` maps to `%`.
  - A literal `%`, `_` or `\` is escaped, and `ESCAPE '\'` is rendered **only when something was
    escaped**.
  - At most 4 wildcards are allowed per pattern. This value is provisional until S8 has been
    measured.
- **Other rules:**
  - `offset > 0` without `order` gets `order_required`.
  - More than one of `asOf`, `asOfTag` and `asOfTs` gets `invalid_selector`.
  - A duplicate column in `select` gets `invalid_parameter`.
- **Hard caps.** Each gets 400 `invalid_parameter`. These are constants, not configuration: they
  bound parser work and SQL size, they do not shape results.
  - at most 32 filters;
  - at most 64 `in` items;
  - at most 16 order terms;
  - at most 256 selected columns;
  - at most 4 KiB per value.

**Rendering** (`RowsSql.render`, a pure function):

```
SELECT <probed idents> FROM "<catalog>"."<schema>"."<table>" AT (VERSION => <id>)
 WHERE <p1> AND <p2> ...
 ORDER BY <terms>
 LIMIT <effectiveLimit + 1> OFFSET <offset>
```

- **Invariant.** No byte of user input reaches the SQL text except inside a string literal produced
  by `SqlLiterals.duckdbLiteral`.
  - Identifiers come only from the probe and pass through `SqlLiterals.duckdbIdent`.
  - Values are rendered as `CAST(<duckdbLiteral> AS <probed type>)`. Numbers, booleans and dates
    are therefore rendered as quoted literals too.
- **Catalog confinement.** Every name is **fully qualified** with the tenant-db's session catalog,
  `<catalog>`. How that name is resolved depends on the kind (§6.7).
  - The router's `wrapWithDefaultSchema` (`edge/FlightSqlRouter.scala:864-895`) prefixes `USE`
    with the tenant-db's *default* schema, not the path schema.
  - `USE` sets a default. It does **not** stop name resolution from leaving the catalog, so the
    three-part form is what confines the statement (S7).
  - The two-part form used by the admin preview (`ondemand/api/CatalogPreviewHandlers.scala:139`)
    is not copied.
- **Operator rendering:**
  - `is.null`, `is.true` and `is.false` render as `IS [NOT] NULL|TRUE|FALSE`.
  - `neq` renders as `<>`.
  - `not.` wraps the predicate in `NOT (...)`.
- **S3 outcome.** If S3 shows that `WHERE` sees raw values, the rendered shape becomes
  `SELECT ... FROM (SELECT * FROM "<catalog>"."<schema>"."<table>" AT (...)) AS q WHERE ... ORDER BY ... LIMIT ...`.
  S3 decides this once for all requests; it is never chosen per request.
- **Limit.** `effectiveLimit = min(limit ?: defaultLimit, maxRows, pat.maxRows)`, computed by
  `ExecCaller.effectiveMaxRows` (`ondemand/api/ExecCaller.scala:25`). The query fetches
  `effectiveLimit + 1` rows so the edge can tell whether more exist.

**Snapshot.** On DuckLake, the data statement **always** carries `AT (VERSION => id)`.

- `id` is the resolved selector when one was given. Otherwise it is
  `DuckLakeCatalogReader.maxSnapshotId()`.
- The response always carries `X-QoD-Snapshot: <id>`. Passing that value back as `asOf` yields
  consistent pages.
- Views follow the outcome of S2.

### 6.5 Response formats and headers

(Section 6.7 gives the rules that differ per database kind.)

The format is chosen from the `format` query parameter first, then from `Accept`, and falls back to
JSON. An unsupported value gets 406 `unsupported_format`. `parquet` gets 406 until P-1 is resolved.
Negotiation lives in its own small pure object; it is not part of `RowsQuery`.

| format | Content-Type |
|---|---|
| `json` | `application/json` |
| `csv` | `text/csv; charset=utf-8` |
| `arrow` | `application/vnd.apache.arrow.stream` |

**`json`:**

- The body is an array of objects, with keys in select order.
- Integers up to 64 bits are JSON numbers. HUGEINT and UHUGEINT are strings.
- DECIMAL is an exact JSON number built from `BigDecimal`, never from a double.
- FLOAT and DOUBLE NaN and ±Inf are strings.
- Dates and timestamps are ISO-8601, with `Z` when the type carries a time zone.
- BLOB is base64.
- Struct, list and map become nested JSON.
- The encoder is **not** `ArrowRowsDecoder`, whose `toString` fallback is lossy
  (`ondemand/api/ArrowRowsDecoder.scala`).

**`csv`:**

- RFC 4180: a header row, CRLF line endings, fields quoted when needed.
- Nested values are written as their JSON text.
- Values are **not** rewritten to defuse spreadsheet formulas. Data integrity comes first, and the
  risk is documented for consumers.

**`arrow`:** the node's batches, re-framed with `ArrowStreamWriter`. The row cap is enforced by
slicing the last batch.

**Headers on a successful `/rows` response:**

- `X-QoD-Snapshot`, on DuckLake only.
- `Content-Range: <offset>-<offset+n-1>/*`, or `*/*` when `n = 0`.
- `X-QoD-Truncated: true`, only when a **server or token cap** cut the page below what the client
  asked for **and** more rows exist. A page cut by the client's own `limit` is ordinary pagination.

Header values are built only from integers and fixed strings, so no response splitting is possible.

**Streaming and cleanup:**

- The body is an fs2 stream over the `ArrowReader`, read in `IO.blocking`.
- **One** finalizer runs exactly once, on completion, on error or on client disconnect. It calls
  `Routed.close()`, which also deregisters the statement from `ActiveStatementRegistry`, and then
  releases the per-user slot. Both operations are idempotent.
- A failure **after the first byte** cannot change the status. The edge then aborts the connection,
  either through a reset or an incomplete chunked encoding. It never ends a JSON array or CSV
  cleanly after such a failure.
- The full result is never buffered.

### 6.6 `GET /api/v1/openapi.json`

- Generated by Tapir's `OpenAPIDocsInterpreter` over `RestEdgeEndpoints`.
- Unauthenticated: the document is static and contains no tenant data.
- The dynamic column filters are described as a free-form query-parameter map, and the grammar is
  given in the description.
- The document is a **contract**. It is pinned by a golden-file test (H15), so any change to it has
  to be deliberate.

---

### 6.7 Database kinds

v1 serves **every tenant-db kind QoD registers** (`model/TenantDbKind.scala`): `ducklake`,
`duckdb-file` and `memory`. All three kinds share the endpoints, grammar, formats, authentication,
policies and limits. The table below lists the only points where they differ, each checked against
the code:

| Aspect | `ducklake` | `duckdb-file` (incl. encrypted) | `memory` |
|---|---|---|---|
| Session catalog `<catalog>` | `TenantDb.catalogAlias(metastore)`: `catalogAlias`, else `dbName` | the same | **`memory`**. `catalogAlias` returns `""` here because the metastore is empty (`model/TenantDb.scala:80-85`), so calling it directly would render `""."s"."t"`. |
| Default schema | `schemaName` | `schemaName` | `main` |
| Snapshot / `AT (VERSION => n)` | always rendered; `X-QoD-Snapshot` sent | never; no header | never; no header |
| `asOf`, `asOfTag`, `asOfTs` | served (`SnapshotSelector`) | 400 `time_travel_unsupported` | 400 `time_travel_unsupported` |
| Consistency across pages | **guaranteed** by echoing the snapshot back as `asOf` | best-effort: rows committed between pages can shift them | best-effort. With several nodes, **each node holds its own in-memory data**, so which node the router picks decides what a page sees. This is the same on every door today. |
| Encryption at rest | transparent (per-file keys in the DuckLake catalog) | transparent (the node attaches with `encryptionKey`) | n/a |
| Branch tenant-dbs | 404 in v1 (P-2) | n/a | n/a |

Rules that follow from this table:

- **One resolver for the session catalog.** The router already maps a kind to its session catalog
  and default schema inline (`perKindDb`/`perKindSchema`, `edge/FlightSqlRouter.scala:419-428`).
  Extract that logic into one pure helper, for example `SessionCatalog.of(kindWire, metastore)` in
  `model/`, and have both the router and the REST edge call it. The REST edge must **never** call
  `TenantDb.catalogAlias` directly. If the two ever disagreed, the ACL would validate against one
  catalog while the node executed against another.
- **Confinement per kind.** The only catalog a REST statement may name is `<catalog>`. For `memory`
  that is the built-in `memory` catalog itself, so S7 must prove that naming it does not open any
  other built-in catalog.
- **The probe and its data statement run on one node.** On DuckLake, the shared `AT` id makes them
  consistent on any node. The other kinds have no such pin, and a `memory` pool's nodes can even
  hold different tables. So the data statement is sent to the probe's node (`Routed.nodeId`),
  through the router's existing `preferredNode` (`FlightSqlRouter.execute`), threaded through
  `ExecCaller` (§8.1). If that node is no longer routable, the router falls back as usual, and a
  schema that has since changed shows up as a 502 `upstream_error`, never as unvalidated SQL.
- **Listing is kind-agnostic.** `MetadataFilterRewriter` keys its filter on the session catalog it
  is given (`defaultDatabase`, `edge/meta/MetadataFilterRewriter.scala:225`), which the router
  derives per kind. The `information_schema` templates in §6.2 filter on the same `<catalog>` value.
  S5 covers all three kinds.
- **Time-travel errors are uniform.** For a non-DuckLake tenant-db, `asOf*` returns
  `time_travel_unsupported` only after authorization (§4 step 7). The kind is therefore never
  revealed to a caller who cannot read the database.

## 7. Hardening for internet exposure

### 7.1 Transport and HTTP layer

- **TLS is on by default.**
  - It uses the same PEM paths as the other doors, plus `CertGen.ensureCertFiles` and `PemKeyStore`,
    as `QuackFrontDoorServer` does (`edge/quack/QuackFrontDoorServer.scala:69-77`).
  - `tlsEnabled=false` with a non-loopback `host` produces a boot WARN: the door then expects a
    TLS-terminating proxy in front of it. That proxy must also cap request rate and must not use
    h2c upgrades toward the edge.
- **Ember limits** (S6). All of them are configurable with safe defaults:
  - header size: 16 KiB, which also bounds the URI;
  - request-header receive timeout: 10 s (slowloris);
  - idle timeout: 60 s;
  - maximum connections: 512;
  - shutdown timeout: 1 s.
- **Security headers** go on every response, including 4xx and 5xx responses produced by Ember and
  Tapir:
  - `X-Content-Type-Options: nosniff`
  - `Content-Security-Policy: default-src 'none'; frame-ancestors 'none'`
  - `Referrer-Policy: no-referrer`
  - `Cache-Control: no-store`
  - `Strict-Transport-Security: max-age=31536000` when TLS is on
  - no `Server` version header.

  The middleware wraps the whole `HttpApp`, not individual routes, so that framework-generated
  responses get the headers too.
- **CORS is off by default** (`corsAllowedOrigins = ""`).
  - The setting takes exact origins (`scheme://host[:port]`) or `*`. A malformed origin fails
    config load.
  - When an origin matches, the edge echoes it back with `Vary: Origin`.
  - `Access-Control-Allow-Credentials` is **never** sent. The edge uses bearer tokens and no
    cookies, so there is no CSRF surface.
  - Allowed method: `GET`.
  - Allowed headers: `Authorization`, `Accept`.
  - Exposed headers: `X-QoD-Snapshot`, `X-QoD-Truncated`, `Content-Range`, `X-Request-Id`,
    `Retry-After`.
- **Error hygiene.**
  - Exception text from the node, JDBC or the parser never reaches the client. It is logged at
    WARN with the request id.
  - Client messages are fixed strings for each error code. The only exception is 400 messages,
    which name the sanitised parameter (§6.1).
- **Logging.**
  - `Authorization` is never logged.
  - Access logs record the method, the route template, the sanitised parameter **names** and the
    status. Parameter values are never logged, because filter values are often personal data
    (`email=eq.x`).
  - Statement history keeps the rendered SQL. This is the existing behaviour for every door, and
    only admins can see history.

### 7.2 Configuration that fails closed (refused at load)

`RestEdgeConfig.validate(acl, …): Either[String, Unit]` follows the pattern of
`HaPreconditions.validate` (`ondemand/ha/HaPreconditions.scala:12-39`), and Main applies it with
`.left.foreach(sys.error)`. Each refusal message names the environment variable and the fix. The
cases are:

- `enabled && !acl.enabled`. Message: *"quack-rest requires ACL: set QOD_ACL_ENABLED=true or
  QOD_REST_ENABLED=false"*.
- `enabled && !acl.filteredMetadata`. Without it, listings would leak the names of objects the
  caller cannot read. The message names `QOD_ACL_FILTERED_METADATA`.
- `defaultLimit < 1`, `maxRows < defaultLimit`, `stmtTimeoutSec < 1`, or a non-positive
  concurrency value or limit.
- A `trustedProxies` CIDR or a CORS origin that cannot be parsed.
- A `port` equal to another enabled door's port.

### 7.3 Failed-auth throttle (`AuthThrottle`, `ClientAddress`)

- **Client IP.**
  - By default the client IP is the TCP peer.
  - When the peer falls inside `trustedProxies`, the edge walks `X-Forwarded-For` **from the
    right** and takes the first hop that is not a trusted proxy. A malformed header falls back to
    the peer, so a client outside the trusted set cannot spoof an address.
  - IPv4-mapped IPv6 addresses (`::ffff:a.b.c.d`) are normalised to IPv4.
  - Native IPv6 addresses are keyed by their /64.
- **What counts as a failure.** Every 401, plus the authentication-type 403s
  (`tenant_forbidden`, `rest_access_denied`), counts as one failure per IP in a sliding window.
  403s from authorization (§4 step 7) and 404s do **not** count, by design: they come from an
  already-authenticated principal, who is bounded by §7.4.
- **Budget.**
  - Once an IP exceeds `authFailuresPerWindow` (default 20 per 60 s), every request from it gets
    429 `too_many_auth_failures` with `Retry-After`, for `authBlockSec` (default 300).
  - The block applies **before** any credential check, which also caps load on the PAT and JWKS
    lookups.
  - A success does not reset the counter.
- **Global backstop.** A token bucket over *all* credential verifications that end in failure
  (`authFailuresGlobalPerSec`, default 50). Once it is empty, a request whose credential fails gets
  429 instead of 401. This stops an attacker who controls many addresses, such as an IPv6 /48 or a
  botnet, from turning the per-IP table into unlimited attempts.
- **Memory bound.**
  - Counters live in a Caffeine cache capped at `authThrottleMaxEntries` (default 100 000), using
    `Caffeine`, which is already a dependency (`project/Dependencies.scala:103-104`).
  - **Blocked** entries live in a separate map, bounded by the same cap, that is never evicted
    early. When that map is full, the oldest block that has expired is dropped first. If none has
    expired, the new offender is still refused through the global bucket.
  - Evicting a counter can therefore only forget partial progress, never an active block.
- **Known collateral.** Clients behind the same NAT, CGNAT or /64 share a budget. The operator
  documentation recommends a WAF and `trustedProxies` for that case.
- **Audit.** Failures are audited with `origin = "rest"` through the existing `AuditRateLimiter`
  (`ondemand/telemetry/AuditRecorder.scala:115`). The password lockout does not apply, because
  bearer failures are not password failures.

### 7.4 Per-user concurrency (`UserLimiter`) and timeouts

- **Slots.**
  - The key is **(tenant, userId) on both the PAT and the JWT path**. It is never the PAT id,
    because minting more PATs must not buy more slots.
  - Each user gets at most `maxConcurrentPerUser` (default 4) in-flight statements, and the edge as
    a whole at most `maxConcurrentTotal` (default 64).
  - Past either limit, the edge answers 429 `too_many_requests` with `Retry-After: 1`.
  - A probe and the data statement that follows it share one slot.
- **Timeout without leaks.**
  - The execute is started as a fiber (`.start`). The handler races that fiber against
    `min(stmtTimeoutSec, pat.stmtTimeoutMs)` and answers 504 `statement_timeout` if the timer wins.
  - The fiber keeps its own `guaranteeCase` finalizer, which closes any `Routed` that arrives late
    and **only then** releases the slot. A client that got a 504 therefore keeps its slot until the
    node has really finished (P-4), so timeouts cannot be used to fan work out.
  - The same finalizer is what closes a late result that would otherwise leak today through
    `routedExecutor`'s `timeoutTo` (`Main.scala:1507-1520`), where a late `Routed` is simply
    discarded. §8.1 fixes this in `RoutedExecutor` for every caller.
  - The streaming phase is bounded by the same deadline. When it expires mid-body, the connection
    is aborted (§6.5).
- **Router session.** The connection id passed to the router is `rest-<tenant>-<userId>`. It is
  deliberately stable, because `executeWith` opens a session per connection id
  (`edge/FlightSqlRouter.scala:406-411`). A fresh id per request would grow `SessionRegistry`
  without bound.

### 7.5 Hibernated pools

- The router's `resolveSnapshot` resumes a suspended pool and holds the request for up to
  `resumeHoldTimeoutSec` (`edge/FlightSqlRouter.scala:904`).
- `Unavailable("pool is resuming…")` maps to 503 `pool_resuming` with `Retry-After: 5`. Any other
  `Unavailable` maps to 503 `pool_unavailable`.
- Because of the invariant in §4, only an authenticated and **authorized** caller can wake a pool.

---

## 8. Changes outside `edge/rest`

### 8.1 `RoutedExecutor` extraction and hardening (P1, a prerequisite)

**Why.** `routedExecutor` is a local `def` inside `Main.scala` (`:1384-1522`), so nothing can test
it. It is also the chokepoint that every non-FlightSQL caller shares. The changes:

1. **Extract** it into `ondemand/api/RoutedExecutor.scala`, with the same `PreviewExecutor`
   signature. `Main` constructs it, and the existing call sites (preview, undrop, restore, MCP)
   stay unchanged.
2. **Make privileged callers explicit.** The synthetic superuser `EffectiveSet` used by the
   in-process call sites (preview, undrop, restore dry-run, branch counts and the MCP static key) is
   selected through a typed `ExecCaller.systemCaller: Boolean` flag, set only at those call sites.
   The new internet-facing caller can then never reach that branch, whatever identity it carries.
   Defence in depth: the internal identity those call sites use
   (`CatalogPreviewHandlers.SuperuserIdentity`) also becomes a reserved username.
3. **Thread fields through `ExecCaller`** (all defaulted, so existing call sites do not change):
   - `source: String = "flightsql"`, forwarded to `FlightSqlRouter.execute`. `execute` gains the
     same parameter and forwards it to `executeWith`, which today hard-codes `"flightsql"`
     (`edge/FlightSqlRouter.scala:356-368`). MCP call sites pass `"mcp"`.
   - `preferredNode: Option[String] = None`, forwarded to `FlightSqlRouter.execute`'s existing
     `preferredNode`. It pins the data statement to the probe's node (§6.7).
   - `preAuthorized: Option[EffectiveSet] = None`. The REST edge authorizes in §4 step 7 and hands
     the result over, so the executor skips its own `authorizeHandshake`. Attenuation by the
     restriction and the `pools`-axis check **still run** on it.
   - `superuserAdmissible: Boolean = true` for the executor's own `authorizeHandshake` call, which
     today uses the 3-argument form. REST never reaches that call (it passes `preAuthorized`), but
     it sets the flag to `false` anyway, as defence in depth.
4. **Close late results.** In the token-timeout branch, a `Routed` that arrives after the deadline
   is closed by the fiber's finalizer (§7.4) instead of being dropped.
5. **Record the source.**
   - `StatementRecord` gains `source: Option[String]`.
   - Liquibase `00NN-stmt-history-source.yaml` adds a nullable `source` column to
     `qodstate_stmt_history`. Existing rows stay `NULL`.
   - The history REST DTO and the admin UI list show it.
   - `StatementInstruments` adds a `source` tag to `statements_total` and
     `statement_duration_seconds`.

### 8.2 Config block

```hocon
quack-rest {
  enabled = false
  enabled = ${?QOD_REST_ENABLED}
  host = "0.0.0.0"
  host = ${?QOD_REST_HOST}
  port = 31339
  port = ${?QOD_REST_PORT}
  tlsEnabled = true
  tlsEnabled = ${?QOD_REST_TLS_ENABLED}
  tlsCertChain = "certs/server-cert.pem"
  tlsCertChain = ${?QOD_REST_TLS_CERT_CHAIN}
  tlsPrivateKey = "certs/server-key.pem"
  tlsPrivateKey = ${?QOD_REST_TLS_PRIVATE_KEY}
  defaultLimit = 1000
  defaultLimit = ${?QOD_REST_DEFAULT_LIMIT}
  maxRows = 100000
  maxRows = ${?QOD_REST_MAX_ROWS}
  stmtTimeoutSec = 60
  stmtTimeoutSec = ${?QOD_REST_STMT_TIMEOUT_SEC}
  corsAllowedOrigins = ""
  corsAllowedOrigins = ${?QOD_REST_CORS_ALLOWED_ORIGINS}
  # hardening (section 7)
  trustedProxies = ""                       # comma-separated CIDRs
  trustedProxies = ${?QOD_REST_TRUSTED_PROXIES}
  authFailuresPerWindow = 20
  authFailuresPerWindow = ${?QOD_REST_AUTH_FAILURES_PER_WINDOW}
  authWindowSec = 60
  authWindowSec = ${?QOD_REST_AUTH_WINDOW_SEC}
  authBlockSec = 300
  authBlockSec = ${?QOD_REST_AUTH_BLOCK_SEC}
  authFailuresGlobalPerSec = 50
  authFailuresGlobalPerSec = ${?QOD_REST_AUTH_FAILURES_GLOBAL_PER_SEC}
  authThrottleMaxEntries = 100000
  authThrottleMaxEntries = ${?QOD_REST_AUTH_THROTTLE_MAX_ENTRIES}
  maxConcurrentPerUser = 4
  maxConcurrentPerUser = ${?QOD_REST_MAX_CONCURRENT_PER_USER}
  maxConcurrentTotal = 64
  maxConcurrentTotal = ${?QOD_REST_MAX_CONCURRENT_TOTAL}
  maxConnections = 512
  maxConnections = ${?QOD_REST_MAX_CONNECTIONS}
  maxHeaderBytes = 16384
  maxHeaderBytes = ${?QOD_REST_MAX_HEADER_BYTES}
  headerReceiveTimeoutSec = 10
  headerReceiveTimeoutSec = ${?QOD_REST_HEADER_RECEIVE_TIMEOUT_SEC}
  idleTimeoutSec = 60
  idleTimeoutSec = ${?QOD_REST_IDLE_TIMEOUT_SEC}
}
```

- `RestEdgeConfig` lives in `Config.scala`, with `@field @ConfigField(envVar = …, description = …)`
  on each field, like `QuackNativeConfig` (`Config.scala:937-979`).
- Main needs a `ProductHint` and a `deriveReader` for it (`Main.scala:104,134`).
- `ConfigRegistry.rootsFor` needs an entry (`ondemand/api/ConfigRegistry.scala:39,47`) so the admin
  Config page shows the block.

### 8.3 Boot, shutdown, deployment

- **Main:** `Option.when(cfg.enabled)(new RestEdgeServer(...))`, chained into `dataPlaneIO` after
  the Quack door, with `adaptError("REST edge init failed")`. A bind failure aborts boot.
- **Banner:** a REST line in `Banner.startup`.
- **ShutdownCoordinator:** a new `restEdge: Option[RestEdgeServer]`, stopped in both the JVM hook
  and `gracefulShutdown`, before the drain.
- **Deployment:**
  - `Dockerfile`: `EXPOSE 31339`.
  - `docker-compose.yml`: a port mapping.
  - Helm: the same six places as `quack`:
    - `service.rest`;
    - a `rest.enabled` toggle;
    - `containerPort`;
    - the configmap environment variables;
    - the network policy;
    - `NOTES.txt`.
  - Nothing is exposed by default.
  - The operator documentation covers ingress for the internet: TLS passthrough, or TLS termination
    together with `trustedProxies`.

---

## 9. Observability

- **Micrometer meters,** named the existing way (snake_case, `_total` and `_seconds` suffixes, no
  prefix):
  - `rest_requests_total{endpoint,format,status}`
  - `rest_request_duration_seconds{endpoint,format}`
  - `rest_auth_throttled_total`
  - `rest_user_rejected_total`

  The `endpoint` label is the route **template**, which keeps its cardinality bounded.
- **History and metering:** statement history and metering carry `source="rest"` and `patId`.
- **Audit:** denials are audited with `origin="rest"`, through the router's existing audit.

---

## 10. Threat model (internet-facing)

| # | Threat | Control | Tests (§11) |
|---|---|---|---|
| T1 | SQL injection through a value, a column, an order term or a path segment | AST parser. Identifiers come only from the probe. Values appear only inside `duckdbLiteral` plus `CAST`. Path segments are checked against the identifier rule. | U3, U4, H6 |
| T2 | A superuser or system path reachable from the internet | Bearer only. A tenant-null PAT gets 401. `superuserAdmissible=false`. The privileged `EffectiveSet` is selected by a typed flag that only in-process call sites set (§8.1). | R1, R2, H3 |
| T3 | Replay of an existing PAT | `restAccess` opt-in with default `false`, a narrowing lattice and an explicit `SessionRoot` | U7, H2 |
| T4 | Cross-tenant access | PAT owner's tenant must equal the path tenant. JWTs are validated only by the tenant's own OIDC provider. The user is looked up inside the path tenant. | R3, H16, E3 |
| T5 | Enumerating tenants, databases, schemas, tables, tags or snapshots | A single 401 body. 404 whether the object is missing or not granted. Nothing touches the catalog before authorization (§4). Tags are never echoed. | H4, H5 |
| T6 | Credential brute force, lookup DoS, control-plane write amplification | Per-IP throttle plus a global backstop, applied before credential work. `X-Forwarded-For` is trusted only from listed proxies. PAT `last_used_at` is written at most once a minute. | U8, U9, H7 |
| T7 | Resource exhaustion by an authenticated caller | Row, time, filter, size and wildcard caps. Per-user and global slots, held until the node finishes. | U10, H8 |
| T8 | Slowloris, oversized headers or URI, request desync | Ember receive timeout, header limit, connection cap. A GET with a body is refused. | H9 |
| T9 | Policy bypass (RLS, CLS, masked-column oracle) | One pipeline (`RoutedExecutor`). S1 and S3 are pinned by tests. | P1, P2 |
| T10 | Metadata leak through listings or built-in catalogs | Filtered `information_schema` (S5). Boot is refused without filtered metadata. Three-part names only (S7). System schemas get 404. | P4, P8, U11 |
| T11 | Unauthenticated wake-up of a hibernated pool | The ordering in §4 | H5, E6 |
| T12 | Tokens or personal data in logs | `Authorization` and query values are never logged. No tokens in URLs. Parameter names are sanitised. | H10 |
| T13 | Cross-origin abuse from a browser | CORS allow-list, credentials never allowed, bearer tokens only | H11 |
| T14 | Internal error text reaching the client | A fixed message per error code, details in server logs under the request id | H12 |
| T15 | JWT that is unsigned, expired, has no `exp`, is foreign, or comes from a global provider | Only the tenant's own OIDC provider, plus a mandatory `exp` (S4) | H13, H16 |
| T16 | Resource leak on timeout or disconnect | A single idempotent finalizer. Late results are closed. | U10, H8, H17 |

---

## 11. Test design

**Conventions.** Frameworks and style follow the repo:

- ScalaTest `AnyFlatSpec with Matchers`.
- One spec per class, mirroring the package (`src/test/scala/ai/starlake/quack/edge/rest/`).
- Cases named for behaviour, e.g. `"RowsSql" should "never place user text outside a literal" in`.
- **No ScalaCheck** is added, since the repo has none. Tests over generated input use a seeded
  `scala.util.Random` loop and print the seed when they fail.
- Tests that need `duckdb` or embedded Postgres use `assume`/`cancel`, as `QuackCompatibilitySpec`
  and `PostgresFixture` do.

**Write each phase's tests before its implementation, red first.** The pure-core specs are the
contract.

### 11.1 Unit (U): pure, no network

| Id | Spec | Cases (each is one `it should`) |
|---|---|---|
| U1 | `RowsQuerySpec` | **Accepts:** every operator; `not.`; `in` with quoted items containing `,` `)` `"` `\`; `is.null`, `is.true` and `is.false`; every combination of order suffixes; the default select. **Rejects:** an unknown operator; an empty value where one is required; unbalanced `in`; a duplicated reserved parameter; `branch`; a duplicate column in `select`; each hard cap at N+1 but not at N; NUL in a value; an invalid `%` sequence; `offset>0` without `order`; several `asOf*` at once. **Integers:** `limit`/`offset` values of `+1`, `01`, `1e3` and `2147483648` are rejected. |
| U2 | `RowsQuerySpec` (column resolution) | ASCII case-insensitive match. An ambiguous match gives `unknown_column`. The Kelvin sign `K` (U+212A) does **not** match `k`. `LIMIT=5` is treated as a filter on a column named LIMIT. A reserved name is never read as a column (Q6). An unknown column is never ignored. |
| U3 | `RowsSqlSpec` (golden) | Golden SQL for a matrix: every operator with `not`/`in`/`is`; LIKE escaping, with `ESCAPE` present only when something was escaped; the wildcard cap; order with nulls; `LIMIT n+1 OFFSET`; `AT` present and absent; the three-part name always; identifiers that need quoting (`"we""ird"`). **Numbers:** `1e999999999` and out-of-range values for each integer width are rejected, and DECIMAL precision and scale are enforced. |
| U4 | `RowsSqlInjectionSpec` | **Inputs:** a fixed corpus (quotes, `\`, `;`, `--`, `/* */`, `$$`, CR/LF, Unicode look-alikes, a 4 KiB value, SQL keywords) plus 10 000 seeded random strings. Each input is tried as a **value**, and separately as a probed column **name**. **Three oracles, none of them the ACL parser.** That parser strips `AT` before parsing and does not lex exactly as DuckDB does. (1) *Structural:* a scanner that mirrors the quoting of `duckdbLiteral`/`duckdbIdent` removes every literal and quoted identifier; what remains must consist only of a closed set of tokens. (2) *Semantic:* the rendered SQL runs in in-process DuckDB (`edge/adapter/TestArrow.scala`) against a table holding the random value and a sentinel table. `eq` returns exactly that row, `not.eq` returns the rest, and the sentinel survives. (3) *Single statement:* `json_serialize_sql` on the rendered text yields exactly one statement. |
| U5 | `RestResultEncoderSpec` | Arrow input comes from `TestArrow.readerFor(sql)`. JSON and CSV for each type family in §6.5: DECIMAL(38,10) exact; BIGINT minimum and maximum; HUGEINT as a string; NaN and ±Inf; timestamps with and without a time zone; date; BLOB as base64; nested struct, list and map; NULL at every depth. CSV quoting with embedded CRLF, and the header row. Arrow re-framing, with the row cap slicing the last batch. |
| U6 | `RestEdgeConfigSpec` | Every refusal in §7.2, with a message that names the environment variable. A valid config passes. Edge cases in parsing CORS origins and CIDRs. |
| U7 | `TokenRestrictionSpec` (extended) | `narrow` over `restAccess`: a child cannot be `true` under a `false` parent. `Unrestricted.restAccess == false`. `SessionRoot` allows a root mint to be `true`. |
| U8 | `ClientAddressSpec` | Untrusted peer with an XFF header: the peer is used. Trusted peer: the right-most untrusted hop. All hops trusted: the left-most. Malformed header: the peer. `::ffff:1.2.3.4` is keyed as `1.2.3.4`. IPv6 is keyed by /64. |
| U9 | `AuthThrottleSpec` | Uses an injected clock and Caffeine built with `executor(Runnable::run)` plus `cleanUp()`, so the test is not flaky. Blocks at N+1. A success does not reset the counter. The block expires after `authBlockSec`. The window slides. **A blocked entry survives churn of 200 000 other keys.** The global bucket takes over once it is drained. 403s from authentication count as failures; 403s from authorization do not. |
| U10 | `UserLimiterSpec` | Enforces both the per-user and the global cap. Release is **idempotent**: after two releases the cap still holds. 10 000 seeded random sequences of acquire, release and fail leave no leaked slot. |
| U11 | `FormatNegotiationSpec` | `format` wins over `Accept`. `parquet` and unknown values give 406. JSON is the fallback. |

### 11.2 `RoutedExecutor` (R): the extracted class from §8.1

The specs use the router fixtures of `FlightSqlRouterExecuteWithSpec.setup` and an in-memory
supervisor.

| Id | Cases |
|---|---|
| R1 | Only `systemCaller=true` yields the privileged set. Every other caller goes through `authorizeHandshake`, whatever its identity. Reserved internal identity names are refused by `user/create`, SCIM and manifest import. |
| R2 | `superuserAdmissible=false` refuses a tenant-null row. `preAuthorized` skips the handshake but still applies attenuation and the `pools` axis. |
| R3 | `jwtRoles`/`jwtGroups` reach the 6-argument `authorizeHandshake`. `source` reaches history, audit and metrics. |
| R4 | Token timeout: a stub whose result arrives late has its `Routed.close` called exactly once, and the statement is deregistered. |
| R5 | Every existing caller (preview, undrop, restore, branch counts, MCP) behaves exactly as before. Existing specs stay green. MCP now records `source="mcp"`. |

### 11.3 HTTP (H): the real `RestEdgeServer`, stubbed collaborators

**Harness.** Copied from `QuackFrontDoorServerSpec`:

- `freePort()`, `withServer`, the JDK `HttpClient`;
- a trust-all `SSLContext` with `CertGen` for TLS;
- **raw `java.net.Socket`** wherever the JDK client would normalise the request away (H9, H17).

**Stubs:**

- **Executor:** a recording `PreviewExecutor`, modelled on `McpDataToolsSpec.capturingExecutor`
  (`src/test/.../mcp/McpDataToolsSpec.scala:247`). It has call counters and can be gated on a
  `CountDownLatch` or `Deferred` so concurrency tests are deterministic.
- **Logs:** a logback `ListAppender` testkit, new; logback-classic is already on the classpath.
- **JWT:** `security/MockOidcServer` plus `JwtTestSigner`, which gains a `mintRaw(claims, header)`
  helper to produce tokens with no `exp` and with `alg=none`.

| Id | Cases |
|---|---|
| H1 | **Happy path** for each endpoint and format: status, body and headers. The captured calls show that the probe and data SQL carry the same `AT` id, which equals `X-QoD-Snapshot`. The captured `ExecCaller` has `source="rest"`, `systemCaller=false`, `preAuthorized` set and the right `patId`. Non-DuckLake `asOf` gives `time_travel_unsupported`, with no `AT` and no header. A listing capped at `maxRows` sets `X-QoD-Truncated`. |
| H2 | **PAT mapping** (stubbed store). A PAT without `restAccess` gets 403 `rest_access_denied`. A resolver returning `None` gets the uniform 401. One case gated on Postgres goes through the real `PatAuthenticator.resolve` for revoked, expired and disabled-owner tokens. |
| H3 | **Credentials.** A superuser PAT gets a 401 byte-identical to a bad token's, body and headers. `X-API-Key` alone, `Basic`, a cookie, or two `Authorization` headers: 401. A token of exactly 8 KiB is accepted; 8 KiB+1 gets 401. A lower-case `bearer` scheme is accepted; two spaces after the scheme get 401. `?access_token=` with no bearer gets 401; with a valid bearer it gets 400 `unknown_column`, and the value is never used as a credential. |
| H4 | **Uniform responses.** Unknown tenant vs. known tenant with a garbage token: byte-identical 401s. Missing table vs. denied table: byte-identical 404s, with the same number of executor and store calls (a structural check, not timing). Missing database vs. a database outside the `databases` axis: byte-identical 404s. An empty schema listing gets 404. A tag-not-found message never echoes the tag. |
| H5 | **Nothing before authorization.** On every 401, 403, 429 and step-7 denial, the executor, the catalog reader and the snapshot selector are called **zero** times. |
| H6 | Path segments containing `..`, `%2F`, quotes, too many characters, `information_schema` or `pg_catalog` get 404, and the stub sees nothing. |
| H7 | **Throttle.** After 20 bad tokens, the 21st request gets 429 even with a valid token. XFF from an untrusted peer is ignored. With `trustedProxies=127.0.0.1/32`, different XFF clients get **separate** budgets. A flood of 403 authorization denials and 404s from a valid PAT is not throttled. |
| H8 | **Concurrency.** The stub latches 4 calls; the 5th request gets 429. Two PATs belonging to the same user share one budget. **Timeout:** an injected `FiniteDuration` seam produces a 504, and the slot stays held until the stub completes, then is freed exactly once. |
| H9 | **Framing, over a raw socket.** A 17 KiB header gets 431 (or whatever Ember returns, pinned by S6). A slow header with `headerReceiveTimeoutSec=1` has its connection closed. `GET` with `Content-Length: 1000000000` and no body gets 400 immediately, with `Connection: close`. `POST`, `PUT`, `DELETE` and `HEAD` get 405. |
| H10 | **Logs.** The `ListAppender` shows that no token and no parameter value appears in any log line during H1 to H9. For example, a bad operator on `email=eq.secret@x.io` leaves `secret` out of both the log and the 400 body. Hostile parameter names (CRLF, 10 KiB) are sanitised. |
| H11 | **CORS.** No CORS headers when disabled. An allowed origin is echoed with `Vary: Origin`; a disallowed one gets no allow-origin header. `Access-Control-Allow-Credentials` never appears. Preflight works. |
| H12 | **Errors.** A stub that throws with a secret-looking message produces a 502 whose body omits the message but carries the request id; the log contains the message. Security headers and `X-Request-Id` are present on 4xx and 5xx responses generated by Ember and Tapir themselves (431, 405, decode failure, malformed-segment 404). A client-supplied `X-Request-Id` is not echoed. |
| H13 | **JWT**, using the real `AuthenticationService` and a tenant `MockOidcServer`. Valid: 200. No `exp`: 401. Expired: 401. Wrong `aud` or `iss`: 401. `alg=none`: 401. A tenant with no OIDC provider: 401 (S4). |
| H14 | **TLS.** The handshake succeeds with generated certificates. HSTS appears on every response, errors included. |
| H15 | `GET /api/v1/openapi.json` equals the golden file `src/test/resources/rest/openapi-v1.json`. |
| H16 | **Global providers refused.** A token signed by the global HS256 `jwt` provider, and one from a global OIDC provider, both get 401 on REST while still working on FlightSQL. |
| H17 | **Disconnect and abort.** A client that closes its socket mid-stream causes `Routed.close` to run exactly once and frees the slot. A reader that throws after the first batch leaves the client with an incomplete chunked stream or a reset, never a parseable JSON array or CSV. |

### 11.4 Pipeline (P): the real policy rewriters, in two tiers

- **Text tier.** `FlightSqlRouterSpec.setupWithRewriter` (`src/test/.../edge/FlightSqlRouterSpec.scala:942`)
  captures the SQL after policy has been applied. Policy fixtures: `effWithPolicies`,
  `effWithRowPolicies`, `maskCustomerEmail`, `rowPolicyCustomer` (`:934`, `:1123-1145`).
- **Semantic tier.** The captured, rewritten SQL is executed in in-process DuckDB over seeded tables,
  which needs no Postgres.

| Id | Cases |
|---|---|
| P1 | **S1.** In the text tier, `AT (VERSION => n)` on a three-part reference survives both the CLS and the RLS rewriter, and the policy is still applied. In the semantic tier, RLS filters the rows. CLS: a masked column carries the masked value, and a column dropped by CLS gives `unknown_column` whether it appears in `select`, `order` or a filter. |
| P2 | **S3, the masked-column oracle.** Use a mask that **keeps partial information** (`left(ssn,3)||'***'`), and data whose raw ordering and raw `LIKE` matches differ from the masked ones. Then `like`, `gt`, `is.null` and `order` on the masked column must produce exactly what the same operations produce over the masked values, or the request must get 400. With a constant mask the test would pass trivially, so this data is required. The test is written **before** S3 is resolved: it starts red and forces the decision. |
| P4 | **S5.** Listings return only granted schemas and tables. A view is reported as `view`. An attached federated catalog is never listed. |
| P8 | **S7.** The rendered three-part name stays inside the tenant catalog, for each kind (including `memory`), even for a principal holding a schema-wide `*` grant. |
| P9 | **Session-catalog parity.** For every kind and metastore shape (`catalogAlias` set, only `dbName`, empty for `memory`), the router's `ValidationContext.defaultDatabase` equals the REST `<catalog>`. Both come from `SessionCatalog.of`, and this test keeps them from drifting apart. |

### 11.5 End-to-end (E): real node and real DuckLake

Gated on `duckdb` being present and on embedded Postgres. The spec, `RestEdgeEndToEndSpec`, builds
on:

- `PreviewEndToEndSpec.withRouter` (`src/test/.../it/PreviewEndToEndSpec.scala:84`);
- `PostgresFixture.withCatalog` and `runSqlOnCatalog`;
- a `RestEdgeServer` on a free port.

| Id | Cases |
|---|---|
| E1 | The happy path in all three formats, against a real DuckLake table and a real view. |
| E2 | **S2 and consistent pagination.** Read page 1. Commit an insert that sorts **before** the page-2 cursor, and a delete. Read page 2 with `asOf=<X-QoD-Snapshot>`. The two pages together must equal the ordered set as it was before the changes. Repeat on a view, following the outcome of S2. |
| E3 | Cross-tenant: a tenant-B PAT or JWT on a tenant-A path is refused. |
| E4 | With `pat.maxRows` smaller than `maxRows` smaller than `limit`, the smallest cap wins, and `X-QoD-Truncated` is set. |
| E5 | History and metrics: the history row has `source='rest'` and the `pat_id`. `statements_total{source="rest"}` goes up. |
| E6 | **Hibernation, split so each case is deterministic.** (a) A suspended pool whose node becomes routable within the hold: 200. (b) A hold that expires: 503 `pool_resuming` with `Retry-After`. (c) An unauthorized request leaves the pool suspended. |
| E8 | **Every kind.** E1 and the listing endpoints run against a `ducklake`, an encrypted `duckdb-file` and a `memory` tenant-db. On the two non-DuckLake kinds, `asOf` gives `time_travel_unsupported`, and there is no `AT` and no `X-QoD-Snapshot`. The `memory` statement is qualified `"memory"."main"."t"`. The probe and data statement land on the same node (captured `nodeId`). A branch tenant-db addressed by its name gets 404. |
| E7 | **S8 measurement.** Time the worst-case pattern allowed by the wildcard cap, and record it in §2.4. |

### 11.6 Cross-surface and regression

- The existing `FlightSqlRouter*`, `QuackFrontDoor*`, MCP and `Pat*` specs stay green, changed only
  by the new `source` expectations.
- `cli/tests/test_rest_parity.py` stays green once `openapi.yaml` is regenerated (the new
  `--rest-access` flag). `McpCoverageSpec` is not affected.
- Liquibase: the embedded-Postgres migration spec applies both new changesets, to an empty schema
  and to one that already has rows.
- `SqlLiteralsSpec` is extended with NUL, CR/LF and backslash cases.
- If the operator skill is edited: `test_skill_freshness.py`, after refreshing the bundled copy.

### 11.7 Security checklist before release (manual, not CI)

- An OWASP ZAP baseline scan against a docker-compose deployment with a seeded tenant, run with a
  valid PAT and with no credential.
- `testssl.sh` against the TLS listener.
- A review of every WARN and ERROR line the H suite produces, looking for data that should not be
  there.

---

## 12. Implementation plan

Each phase is a PR-sized series of commits. A phase lands together with its tests, keeps `sbt test`
green, and starts only once its gate is closed.

| Phase | Content | Gate |
|---|---|---|
| **P0** | Spikes S1 to S8. Each ends in a committed test that pins the finding, plus an update to §2.4. | none |
| **P1** | §8.1: extract `RoutedExecutor`; typed `systemCaller`; reserve internal identity names; thread `source`, `preAuthorized` and `superuserAdmissible`; close late results; history column and metrics tag. PAT `last_used_at` throttling (§5.2). Tests: R1 to R5, 11.6. | none. Independent of the decisions, and useful even without REST. |
| **P2** | PAT `restAccess`: Liquibase, `TokenRestriction` plus `SessionRoot`, `PatStore`, the REST DTO, MCP, CLI, UI, and regenerating `openapi.yaml`. Tests: U7, CLI parity. | Q2 confirmed (P-3) |
| **P3** | Pure core: `RowsQuery`, `RowsSql`, `RestResultEncoder`, format negotiation. Tests: U1 to U5, U11. | S8 for the wildcard cap. The S3 shape switch lands in P4. |
| **P4** | Integration of snapshot, probe and render, plus the `SessionCatalog.of` extraction and the probe-node pin (§6.7). Tests: P1, P2, P4, P8, P9. | S1, S2, S3, S7 |
| **P5** | Server shell: `RestEdgeConfig` and its validation, `RestEdgeServer`, `RestAuth`, `AuthThrottle`, `ClientAddress`, `UserLimiter`, the endpoints, OpenAPI, wiring, shutdown and banner. Tests: U6, U8 to U10, H1 to H17, E1 to E8. | S4, S5, S6. Q1, Q3 and Q4 confirmed. |
| **P6** | Deployment and documentation: Dockerfile, compose, Helm. README: ports, a new section, the architecture diagram, the config table. CLAUDE.md: four sockets become five. CHANGELOG: a 0.9.5 heading and entry. Operator skill plus its bundled copy, including the deployment guidance from §7.1 and §7.3 (WAF, `trustedProxies`, NAT collateral) and P-9's IdP note. | P5 |

---

## 13. Code-style notes for the implementor

These notes come from the surrounding code, not from general preference.

- **Syntax and formatting.** Scala 3 indentation syntax, `final case class` / `final class`, and
  scalafmt 3.10 (`maxColumn = 100`, `align.preset = more`). Run `sbt scalafmtAll` before every
  commit.
- **Comments.**
  - Give every class and object a Scaladoc that explains **why** it exists. Where a decision is not
    obvious, cite this spec (`docs/superpowers/specs/2026-09-25-quack-rest-data-edge-design.md`), as
    the Quack door and `PatHandlers` do.
  - Use inline `//` comments for rationale and for what must not change. Do not narrate the code.
  - Existing examples of the expected density: the comment blocks in `routedExecutor`
    (`Main.scala:1391-1400`, `1496-1506`).
- **Errors.**
  - The edge's own failures are an `enum` ADT (`enum RestFailure(val code: String, val status:
    StatusCode)`), shaped like `RouterFailure` (`edge/RouterFailure.scala`).
  - Validation returns `Either[String, _]`.
  - At the Tapir boundary, errors are `(StatusCode, ErrorResponse)`, with error codes as
    snake_case slugs.
  - Only boot code throws.
- **Handlers** return `type Out[A] = IO[Either[(StatusCode, ErrorResponse), A]]`, with the error
  values built once as `val`s, as `PatHandlers` does (`ondemand/api/PatHandlers.scala:83-119`).
  Blocking and JDBC work goes in `IO.blocking`.
- **Test seams** are constructor function parameters with safe defaults, such as
  `clock: () => Instant = () => Instant.now()`, `executor: PreviewExecutor`, or a timeout
  `FiniteDuration`. Do not use mocking libraries.
- **Reuse instead of duplicating.**
  - Use `SqlLiterals.duckdbIdent` and `SqlLiterals.duckdbLiteral`. Do **not** add a sixth private
    `quoteIdent`: copies already exist in `CatalogPreviewHandlers`, `CatalogUndropHandlers`,
    `CatalogRestoreHandlers`, `DuckLakeInitializer` and `QuackFrontDoor`.
  - Reuse `SnapshotSelector`, `PoolPicks.readPoolKey`, `ExecCaller.effectiveMaxRows` and
    `PatAuthenticator.resolve`.
- **Keep `Main.scala` thin.** Only wiring goes there. Logic belongs in `edge/rest/` and in the
  extracted `RoutedExecutor`.
- **Keep files small.** Feature files in the repo run 100 to 400 lines. If the Tapir endpoint
  object grows toward the 64 KB `<clinit>` limit, split it (`ondemand/api/PatEndpoints.scala:9-11`).
- **Commits** follow Conventional Commits (`feat(rest): …`, `test(rest): …`, `refactor(exec): …`),
  with a subject under 70 characters and a body that explains why (`CONTRIBUTING.md`). Each commit
  is one logical unit.
- **Config.**
  - Every new scalar gets an `${?QOD_REST_*}` override and an `@ConfigField` annotation.
  - Never tell users to edit the bundled `application.conf`.
- **Respect CLAUDE.md "Things to avoid".** In particular, refresh the bundled copies whenever
  `scripts/` or the operator skill changes.

# Read-only REST data edge (`quack-rest`): design

- **Issue:** starlake-ai/quack-on-demand#120
- **Status:** DESIGN, revised after the maintainer's answers on #120. Implementation is split into
  **two PRs** (§12). An item marked **OPEN** (§2.4) or **SPIKE** (§2.5) must be closed before the
  phase that depends on it starts.
- **Date:** 2026-09-25, revision 2. Code references were checked against upstream `main` at
  `0.9.7-SNAPSHOT`, which includes the 0.9.6 positional-reference mask fix (#114). Paths are
  relative to `src/main/scala/ai/starlake/quack/` unless stated otherwise.

## 0. How to read this document

| Section | Contents |
|---|---|
| §1 | Framing: what this door adds over MCP, and scope |
| §2 | The maintainer's answers, the two slices, open items and spikes |
| §3 to §9 | The design |
| §10 | Threat model |
| §11 | Tests |
| §12 | PR plan |
| §13 | Code-style notes for the implementor |
| Appendix A | Earlier author decisions this revision replaces, and why |

---

## 1. Framing and scope

### 1.1 What this door adds over MCP

The **governance** half is already solved. A stateless `POST /mcp` carrying a PAT bearer and a
`run_sql` call goes through `routedExecutor`, with RBAC, RLS/CLS, audit, metering and PAT scoping
applied, and `QOD_MCP_MAX_ROWS` as the row cap.

What MCP lacks is an **HTTP contract**:

- GET resources that can be cached;
- no SQL on the client side;
- bodies that are not JSON.

That gap is the whole reason for `quack-rest`. The edge is therefore a **thin translator**: it
turns one HTTP GET into **one SELECT** and hands it to `routedExecutor`, the same way MCP `run_sql`
does.

**Design rule:** anything that looks like policy (grants, masking, row filters, quotas, pool
authorization) belongs in the existing pipeline, not in the edge. When a spike (§2.5) exposes a
policy gap, the fix goes into the validator or the rewriter, never into a special case in the edge.

### 1.2 Scope

- **In scope:** read-only access to the tables and views of a tenant-db of **every kind QoD
  registers** (`ducklake`, `duckdb-file` and `memory`, see §6.6). Objects are discovered
  dynamically, with no per-endpoint configuration. To expose a curated contract, create a view and
  grant it.
- **Out of scope:**
  - writes;
  - raw SQL over HTTP;
  - exact counts, joins and aggregates;
  - federated or attached catalogs (O-3);
  - branch reads (later, per Q7). A branch tenant-db returns 404.

---

## 2. Decisions

### 2.1 The maintainer's answers (binding)

| # | Question | Answer | Where |
|---|---|---|---|
| Q1 | Path prefix and versioning | `/api/v1/...` on its own port. The admin API stays unversioned. | §6 |
| Q2 | PAT scoping | **Reuse the existing `tools` axis** with the reserved tool name **`rest`**, checked the same way `McpRoutes` calls `allowsTool` (`mcp/McpRoutes.scala:120,143`). No new column and no Liquibase change. A PAT with no `tools` restriction is admitted. A PAT restricted to a list that does not include `rest` is refused. | §5 |
| Q3 | Credentials | **PATs only** in slice 1. **Never** the static `X-API-Key` on this edge. **No HTTP Basic**: it would put database passwords into n8n workflow JSON, and PATs already cover the low-code case. **OIDC bearer is deferred to slice 2**: the external JWT providers live on the FlightSQL edge's `AuthenticationService`, and nothing on the HTTP side validates them today. | §5, §12 |
| Q4 | ACL disabled | **A boot warning, not a refusal**, shown on the banner in the same style as the existing `SQL ACL : ENABLED/DISABLED` line (`Banner.scala`, `aclLine`). | §8.3 |
| Q5 | Masked columns | Shown as ordinary columns. Advertising which columns are masked leaks the shape of the policy for no functional gain. | §6.2 |
| Q6 | Reserved parameter names | Acceptable for this phase. Document the limitation, and return **400 `reserved_column`** when a filter names a reserved parameter. Never ignore it silently. | §6.1 |
| Q7 | Branches | Later. The `branchOnly` restriction and the MCP `branchTarget` seam make this a small follow-up. Meanwhile a `branchOnly` token **is** admitted, because the edge never writes. | §5 |
| Q8 | JSON body shape | An array of objects, with metadata in headers. | §6.4 |

### 2.2 The maintainer's implementation constraints (binding)

1. **SQL generation goes through the existing validator and rewriter path.**
   - Build the statement as text and hand it to `routedExecutor` exactly as MCP does. That way
     `StatementValidator`, the RLS/CLS rewriter and the router all see an ordinary statement.
   - **Do not add a second quoting or identifier helper.** Use `model/SqlLiterals.scala`.
   - A fresh SQL builder is how the positional-reference mask bypass fixed in 0.9.6 (#114) would
     come back.
2. **Discovery fails closed.** With `acl.filteredMetadata` on, the schema, table and column
   listings are filtered by the caller's grants. An object without a grant returns **the same 404
   as a missing one**. A spec in the style of `RbacTenantScopeSpec` pins this behaviour.
3. **No superuser fallback anywhere.** A missing or unresolvable credential gets 401.
4. **Every generated statement is a SELECT, routed as READ.**
5. **History and audit attribute the statement to the REST edge the same way the native front
   door does.** The edge passes `source = "rest"` to `FlightSqlRouter.executeWith`. That sets the
   audit origin and `SessionOpened` (`edge/FlightSqlRouter.scala:390-409`). History rows carry no
   source today, and this design does not add one.
6. **OpenAPI.** The endpoints are declared with Tapir, so `GenOpenApi` and `OpenApiFreshnessSpec`
   pick them up. They are excluded from the CLI parity gate (`cli/tests/test_rest_parity.py`) the
   same way the SCIM routes are, as a machine-to-machine surface.
7. **Errors** use the existing `ErrorResponse` shape and the codes listed in the issue (§4.2).
8. **Tests:**
   - a pure spec for the parser and the renderer;
   - an edge spec over the routed-executor seam, without the wire;
   - one end-to-end spec against a live pool, in the style of the existing front-door specs.

   All of them must stay **green under `sbt test` while a manager is running on `:20900`**
   (§11.4).

### 2.3 The two slices

**Slice 1 (PR 1):**

- **Authentication and routing:** PAT bearer auth. The path tenant is checked against the token's
  tenant, mirroring `McpToolArgs.tenantOf`. `pool` is checked against the `pools` axis. The default
  pool is chosen as in `run_sql`.
- **Endpoints:** the four endpoints, **JSON and CSV** only.
- **Query parameters:** `select`, the filter operators and the `not.` prefix, `order`, `limit`,
  `offset`, and `asOf`/`asOfTag`/`asOfTs` with the preview endpoint's rules.
- **Headers and errors:** `X-QoD-Snapshot`, `Content-Range` and `X-QoD-Truncated`, plus
  `order_required` when `offset > 0` comes without `order`.
- **Config:** a `quack-rest` block with `QOD_REST_*` overrides, modelled on `quack-native`, on
  port 31339.
- **Limits:**
  - row cap `min(maxRows, PAT maxRows, limit)`;
  - timeout `min(stmtTimeoutSec, PAT stmtTimeoutMs)`;
  - a cold-start hold that reuses `resumeHoldTimeoutSec`, then 503 with `Retry-After`.
- **Helm:** the new port and a Service.
- **Docs:** the block in the configuration reference.

**Slice 2 (PR 2):**

- OIDC bearer (§5.3).
- **Arrow IPC and Parquet**, streamed.
- CORS from `corsAllowedOrigins`.
- Prometheus metrics.
- *(Proposed, O-1)* abuse controls.

### 2.4 OPEN items (each needs an answer; a recommendation is given)

**O-1. Abuse controls for direct internet exposure.** Blocks: the scope of slice 2.

The maintainer's slices do not include a per-IP failed-auth throttle or a per-user concurrency cap.
Both are HTTP-layer resource controls, not data policy. Without them, slice 1 relies on the
deployment for rate limiting.

Guessing a PAT is infeasible: tokens are 32 random bytes (`ondemand/state/PatStore.scala:138-141`).
What remains is load:

- every bad token costs one indexed lookup on the control plane;
- a valid token can run heavy statements concurrently, bounded only by the row and time caps;
- the time cap is a bounded wait, not a cancellation (O-4).

*Recommendation:* propose these controls for slice 2, in the form sketched in §7.3. Until they
land, the operator documentation states that an internet-facing `quack-rest` must sit behind a
reverse proxy or WAF that rate-limits per client and per `Authorization` value.

**O-2. Where the configuration reference lives.** Blocks: the slice 1 docs task.

`.gitignore` says the docs site moved to the `starlake-docs` repo. `genConfigDocs` generates the
reference from `@ConfigField` annotations (`GenConfigDocsSpec`).

*Recommendation:* slice 1 adds the `@ConfigField` annotations and the `ConfigRegistry` entry, so
the generated reference contains the block. Open a companion PR on `starlake-docs` only if the
maintainer wants a hand-written section there.

**O-3. Federated or attached catalogs (`sql`, `iceberg_rest`).** Blocks: nothing.

*Recommendation:* keep them out of scope. Adding them later needs three things:

- a `catalogs/{alias}` path level;
- a filtered listing for attached catalogs, because `MetadataFilterRewriter.readGrants` only admits
  grants on the session catalog (`edge/meta/MetadataFilterRewriter.scala:230-235`);
- a spike on Iceberg time travel.

**O-4. Durable node-side cancellation.** Blocks: nothing.

The token timeout in `routedExecutor` is a bounded wait, not a cancellation (`Main.scala:1509-1520`
and the comment above it).

*Recommendation:* leave this unchanged and out of this feature. §7.2 at least guarantees that the
edge closes a late result.

### 2.5 SPIKES (verify in code before the dependent phase; each ends in a test that stays)

All seven spikes block slice 1.

| Id | Question | What happens if the answer is bad |
|---|---|---|
| S1 | Do `ColumnPolicyRewriter` and `RowPolicyRewriter` keep `AT (VERSION => n)` on a three-part table reference **and** still apply the policy? The ACL parser strips the clause before parsing (`ai/starlake/acl/parser/SqlParser.scala:47-60`). | Fix it in the rewriter (constraint 1). The edge does not work around it. |
| S2 | Does DuckLake accept `AT (VERSION => n)` on a **view**? | Serve views at the current snapshot. `asOf*` on a view gets 400 `invalid_selector` and no snapshot header. Tell the maintainer. |
| S3 | After rewriting, does a `WHERE` or `ORDER BY` on a masked column see the **raw** value or the masked one? A FlightSQL client can already write this SQL by hand. | It is a rewriter issue, fixed in the rewriter (constraint 1). The pinned test (P2) blocks slice 1 until it passes. |
| S5 | Does `MetadataFilterRewriter` narrow the §6.2 `information_schema` queries for **every kind**? The session catalog is `<alias>` for most kinds and `memory` for the `memory` kind. | Fix it in the rewriter. |
| S6 | Which Ember settings exist in the pinned http4s: `withMaxHeaderSize`, `withRequestHeaderReceiveTimeout`, `withIdleTimeout`, `withMaxConnections`? | Use the closest equivalents and document the gap. |
| S7 | Does the fully qualified `"<catalog>"."<schema>"."<name>"` keep name resolution inside the tenant catalog for every kind? That includes `memory`, whose session catalog is DuckDB's built-in `memory` (`model/DuckDbCatalogs.scala:13`). | Report it to the maintainer and fix it in the validator (constraint 1). |
| S8 | How expensive is the worst LIKE pattern the caps allow, measured on the pinned DuckDB? | Lower the wildcard cap (§6.3). |

**Outcomes (phase 1a, 2026-09-26).** Tests live in `src/test/.../edge/rest/`. A gap is pinned by an
active characterization plus an ignored `KNOWN GAP` test holding the correct expectation (the
precedent of `ColumnPolicyRewriterSpec`); no production code was changed by the spikes.

- **S1: GAP, fails closed.** `ColumnPolicyRewriter` and `RowPolicyRewriter` parse the raw text,
  jsqlparser rejects `AT (VERSION => n)`, and the router denies: a principal with any column or row
  policy cannot read at a snapshot. Without the clause both policies apply. Fix in the rewriters
  (strip and re-attach the clause, as the ACL parser and the metadata filter already do) before
  the edge sends `AT`. `RestTimeTravelPolicySpec`.
  **Fixed** by `edge/policy/TimeTravelCarrier`: both rewriters now strip the clause with the ACL
  scanner and re-attach it to the same base table after rewriting, failing closed when it cannot.
  Residual, outside the edge: `MetadataFilterRewriter` drops the pin when it substitutes an
  `information_schema` reference in a statement that also pins a table (current data is read;
  nothing leaks). The edge never mixes the two.
- **S2: BLOCKED on this machine** (the `ducklake` extension cannot be downloaded). The test
  cancels here; when it runs it fails only if `AT` on a view is accepted but ignored.
  `RestViewTimeTravelSpec`.
- **S7: OK.** Under a schema-wide grant the validator denies three-part names of any other
  catalog, and DuckDB never resolves a three-part name outside its catalog; a two-part name can
  fall back to the session's `temp` catalog, which is why the edge always qualifies fully. Note: on
  a `memory` tenant-db a `*` catalog grant admits nothing, because the validator's tenant catalog
  set holds tenant-db names, not `memory` (fails closed; usability only). `RestCatalogConfinementSpec`.
- **S8: GAP in the cap.** Plain LIKE is linear (about 0.6 ms per KiB). ILIKE, and LIKE rendered with
  `ESCAPE`, grow as length^(wildcards-1): with 4 wildcards one 4 KiB value costs about 35 s. Two
  wildcards cost about 25 ms at 4 KiB, and `lower(col) LIKE lower(p)` stays linear. Consequence for
  §6.3: render `ilike` as `lower(col) LIKE lower(pattern)`, and allow at most 2 wildcards whenever
  the pattern needs `ESCAPE`. `RestLikePatternCostSpec`. Superseded by the §6.3 rule: two inner
  wildcards under `ESCAPE` with long segments still cost 3.9 s on a 4 KiB value, so `ESCAPE` is
  never emitted.

---

## 3. Architecture

```
client --HTTP(S)--> RestEdgeServer (Ember, :31339)          transport limits, headers, request id
                      |
                      v
                    RestEdgeHandlers
                      1. PAT bearer -> principal (tools axis 'rest', tenant check)   401 / 403
                      2. tenant-db + pool  (McpDataTools.poolKeyFor rules)            404 / 403
                      3. snapshot selector (preview rules)                             400/404/410/422
                      4. schema probe      SELECT * ... LIMIT 0  --+
                      5. RestQuery.parse -> RestSql.render          |  PreviewExecutor = routedExecutor
                      6. one SELECT                                --+  (ExecCaller, source = "rest")
                      7. encode JSON | CSV (slice 2: Arrow, Parquet), close Routed
```

The code lives in a new package, `edge/rest/`, laid out like `edge/quack/`:

| File | Responsibility |
|---|---|
| `RestEdgeServer.scala` | The Ember shell: TLS, transport limits, security headers and the request id. Its `start`/`stop` follow the same shape as `QuackFrontDoorServer`. |
| `RestEdgeEndpoints.scala` | Tapir endpoint values, registered in `ondemand/api/EndpointModules.scala` (§8.4). |
| `RestEdgeHandlers.scala` | Steps 1 to 7. Takes a `CatalogPreviewHandlers.PreviewExecutor` as a constructor parameter; that parameter is the seam the edge spec stubs. |
| `RestQuery.scala` | **Pure.** Parses query parameters into an AST and runs every check that does not need the schema. |
| `RestSql.scala` | **Pure.** Renders the AST plus the probed schema into SQL text, using `SqlLiterals` only. |
| `RestResultEncoder.scala` | Turns an `ArrowReader` into JSON or CSV. Slice 2 adds Arrow and Parquet. |

The edge deliberately has **no** authorization step of its own, no second quoting helper and no
policy. Those already exist elsewhere:

- `routedExecutor` (`Main.scala:1384`) runs `authorizeHandshake`, the `pools` axis, `branchOnly`,
  attenuation and the PAT timeout.
- `FlightSqlRouter.execute` runs ACL, CLS, RLS, filtered metadata and hibernation resume.

---

## 4. Request lifecycle

### 4.1 Steps, in normative order

1. **Transport gates** (`RestEdgeServer`):
   - Only `GET` is accepted; everything else gets 405. Slice 2's CORS adds `OPTIONS`.
   - A `GET` that carries `Content-Length > 0` or `Transfer-Encoding` gets 400 with
     `Connection: close`. The body is never read.
   - Ember's header and URI limits apply (§7.1).
2. **Authenticate** (§5). Failures get 401 `unauthorized` or 403 `forbidden`.
3. **Resolve the target** with the same rules as MCP `run_sql` (`mcp/McpDataTools.scala:169-184`):
   - A tenant-db that is unknown, outside the PAT `databases` axis, or a branch (`branchOf` set,
     Q7) gets 404 `not_found`.
   - A `pool` that does not exist, or that serves another database, gets 404 `not_found`.
   - With no `pool`, the default is `PoolPicks.readPoolKey`.
   - The `pools` axis itself is enforced inside `routedExecutor` (`Main.scala:1403-1421`), which
     returns 403 `acl_denied`.
4. **Snapshot**, following the preview endpoint's rules (`ondemand/api/CatalogPreviewHandlers.scala`):
   - Time travel requires a DuckLake tenant-db. `asOf*` on any other kind gets 400 `invalid_kind`,
     the same code the preview uses.
   - On DuckLake, the snapshot comes from `SnapshotSelector.resolve`, and errors are mapped by
     `SnapshotSelector.httpError`. With no selector, `DuckLakeCatalogReader.maxSnapshotId()` is
     used.
   - One deliberate difference from the preview: a tag-not-found message never echoes the tag.
5. **Probe the schema** (§6.2). A missing object and an object without a grant get the same 404.
6. **Parse and render** (§6.3). Invalid input gets 400.
7. **Execute and encode**, with the timeout (§7.2), the cold-start hold (§7.4) and node errors.

Every response carries `X-Request-Id`, a fresh UUID. That includes responses generated by Ember and
Tapir themselves. A client-supplied request id is never echoed back.

### 4.2 Error codes

All errors use `ErrorResponse(error, message)` (`ondemand/api/Dtos.scala:299`).

| Status | Code(s) |
|---|---|
| 400 | `invalid_filter`, `unknown_column`, `reserved_column`, `order_required`, `invalid_selector`, `invalid_kind`, `invalid_parameter` |
| 401 | `unauthorized`, with one body for every cause: a missing, malformed, unknown, revoked or expired PAT, or a superuser PAT |
| 403 | `forbidden` (the PAT does not allow tool `rest`, or its tenant is not the path tenant); `acl_denied` (from the executor: pool not permitted, handshake refused) |
| 404 | `not_found`. A missing object and an object without a grant get identical responses. |
| 406 | `unsupported_format` (in slice 1, anything other than `json` or `csv`) |
| 410 / 422 | from `SnapshotSelector.httpError` |
| 503 | `pool_resuming` (with `Retry-After`) or `pool_unavailable` |
| 504 | `statement_timeout` |
| 502 | `upstream_error`, with a generic message and the request id. Node exception text is never passed through. |

---

## 5. Authentication (slice 1: PATs only)

1. **Intake.** Only `Authorization: Bearer <token>` is read. The scheme is case-insensitive and the
   token is at most 8 KiB.
   - Everything else gets 401: a missing header, several `Authorization` headers, `Basic`,
     `X-API-Key`, a cookie, or `?access_token=`.
   - A token that does not start with `qod_pat_` (`PatStore.TokenPrefix`) gets 401 in slice 1.
     Slice 2 sends those tokens to OIDC.
2. **Resolve.** `PatAuthenticator.resolve(token)` (`ondemand/auth/PatAuthenticator.scala:72`)
   checks the hash, revocation, expiry and that the owner is enabled. If it returns `None`, the
   answer is 401.
3. **No superuser.** An owner with `user.tenant == None` gets **401**, with the same body as an
   invalid token (constraint 3). This is where the edge departs from `McpToolArgs.tenantOf`, which
   accepts a superuser PAT when the tenant is given explicitly.
4. **Tenant binding**, mirroring `McpToolArgs.tenantOf` (`mcp/McpToolArgs.scala:45-59`).
   - The path tenant must equal the owner's tenant. Otherwise the answer is 403 `forbidden`, with
     the message "your token is scoped to tenant '<own>'".
   - An unknown path tenant gets that same answer, so a PAT holder cannot probe whether a tenant
     exists.
5. **Tools axis.** If `restriction.allowsTool("rest")` is false, the answer is 403 `forbidden`.
   - This is the same check `McpRoutes` applies to MCP tools. A PAT with no `tools` restriction is
     admitted.
   - `rest` becomes a **reserved tool name**. Add it to the list of names that PAT minting and the
     MCP tools registry know about, so that no MCP tool can ever be called `rest`.
6. **`branchOnly`.** These tokens are admitted. Every statement is a SELECT classified as READ,
   which `routedExecutor`'s `writeOnMain` check (`Main.scala:1423-1429`) lets through.
7. **`ExecCaller`.** It is built exactly as `McpDataTools` builds one for a PAT principal
   (`mcp/McpDataTools.scala:88-98`):
   - `connectionId = s"rest-${patId}"`. This stays stable per PAT, because `executeWith` opens a
     router session per connection id.
   - `identity` is the owner's username.
   - `restriction` is the PAT's restriction, and `patId` is set.
   - `source = "rest"` is new (§8.1).

   The edge **never** uses `ExecCaller.unrestricted`.

### 5.3 Slice 2: OIDC bearer (outline, detailed in PR 2)

Recorded now so that slice 1 does not rule anything out:

- **Validate** through the **path tenant's own** OIDC provider (`TenantOidcRegistry.forTenant`).
  - Do not use `AuthenticationService.bearerChainFor(Tenant)`: it keeps the global providers
    (`edge/auth/AuthenticationService.scala:127-145`), and usernames from a global IdP are not
    scoped to a tenant.
  - Never use `EdgeHandshake`: it trusts the client when no provider is configured
    (`edge/EdgeHandshake.scala:89`).
- **Require `exp`.** The current authenticators accept a token without one.
- **Provision the user** in the tenant, as for FlightSQL.
- **Pass the claims through:** thread `jwtRoles` and `jwtGroups` into `routedExecutor`'s
  `authorizeHandshake` call.

---

## 6. Endpoints

All endpoints are `GET`, under `/api/v1/tenant/{tenant}/database/{tenantDb}`:

| Path | Returns |
|---|---|
| `/schemas` | `[{"name": ...}]`: the schemas the caller can read |
| `/schemas/{schema}/tables` | `[{"name": ..., "type": "table"\|"view"}]`: the objects the caller can read |
| `/schemas/{schema}/tables/{table}` | `{"name", "type", "columns": [{"name", "type"}]}` |
| `/schemas/{schema}/tables/{table}/rows` | the rows (§6.3, §6.4) |

Path segments must follow QoD's identifier rule. A segment that breaks it gets 404, because no
object can have that name.

### 6.1 Parameter vocabulary

- **Reserved names:** `select`, `order`, `limit`, `offset`, `asOf`, `asOfTag`, `asOfTs`, `pool`,
  `format` and `branch`.
  - They are matched exactly and case-sensitively, after the query string has been percent-decoded
    once. `+` is a literal plus. An invalid `%` sequence gets 400.
  - A reserved parameter given twice gets 400 `invalid_parameter`.
  - `branch` gets 400 `invalid_parameter` in slice 1 (Q7).
- **Column filters.** Every other name is a column filter.
  - Repeating a filter ANDs the conditions.
  - An unknown column gets 400 `unknown_column`. It is never ignored.
- **`reserved_column` (Q6).** The edge returns 400 `reserved_column` when both of these hold:
  - a reserved parameter's value parses as a filter expression (`[not.]<op>.<value>`);
  - the probed table has a column with that reserved name.

  For example, `limit=eq.5` on a table that has a column named `limit`. Such a column cannot be
  filtered in this phase; the request is neither ignored nor guessed at. The operator
  documentation gives the workaround: a view that renames the column.
- **Parameter names in messages and logs** are sanitised: printable ASCII only, truncated to 64
  characters. Parameter values never appear.

### 6.2 Discovery (fails closed, constraint 2)

**Listings.** `schemas` and `tables` run a fixed `information_schema` SELECT through the executor.

- The only interpolated values are the session catalog and the schema, both rendered with
  `SqlLiterals.duckdbLiteral`.
- With `acl.filteredMetadata` on, `MetadataFilterRewriter` narrows the rows to the caller's
  grants (S5).
- With it off, reading `information_schema` needs an explicit grant (see the `filteredMetadata`
  comment in `application.conf`). Without that grant the statement is denied, which becomes a 404.
  Discovery therefore fails closed in both modes.
- `information_schema` and `pg_catalog` are never valid path schemas (404).
- A schema with nothing visible in it gets **404**, not `[]`.

**Detail and `/rows`** both use the probe
`SELECT * FROM "<catalog>"."<schema>"."<table>" [AT (VERSION => n)] LIMIT 0`, run through the
executor. Its Arrow schema is the column contract **after policy**:

- columns dropped by CLS are absent;
- masked columns look like ordinary columns (Q5).

An `acl_denied`, a not-found or a bad-request from the probe all become one 404 `not_found`, with
the same body, the same headers and the same number of executor calls.

**What the edge interpolates.** Nothing beyond what constraint 1 already allows:

- the session catalog, from the registry;
- schema and table names that passed the identifier rule;
- column names, taken from the probe;
- values, through `SqlLiterals`.

### 6.3 `/rows`: grammar and rendering

**Grammar.** Values are percent-decoded once.

```
select   := col ("," col)*                     -- default: every probed column, probe order
order    := term ("," term)*
term     := col ["." ("asc"|"desc")] ["." ("nullsfirst"|"nullslast")]
filter   := <col> "=" ["not."] op "." value
op       := eq | neq | gt | gte | lt | lte | like | ilike | in | is
value    := raw text | "(" item ("," item)* ")" (in) | null|true|false (is)
item     := bare | '"' quoted '"'              -- quoted: \" and \\ escapes
limit    := [1-9][0-9]{0,9} (<= 2^31-1)       offset := "0" | [1-9][0-9]{0,9} (<= 2^31-1)
```

**Validation.** Every failure gets 400. The message names the parameter, never its value.

- **Columns** are matched against the probe with ASCII-only lower-casing, not Unicode
  `equalsIgnoreCase`. The SQL always uses the probe's spelling.
  - An unknown or ambiguous column gets `unknown_column`.
  - A duplicate in `select` gets `invalid_parameter`.
- **Values** are type-checked against the probed type:
  - integers are range-checked;
  - decimals must match `-?digits[.digits]`, with **no exponent**, and fit the precision and scale.
    The exponent ban blocks `1e999999999`, whose plain-string rendering is enormous;
  - booleans are `true` or `false`;
  - dates and timestamps are ISO-8601;
  - strings are passed through as-is;
  - a NUL byte anywhere gets 400.
- **Operators and column types:**
  - `like` and `ilike` apply to string columns only.
  - Nested, blob and interval columns can be selected, but not filtered or ordered.
- **LIKE patterns** (S8: every admitted pattern stays on a linear matcher; `ILIKE`, `_` and
  `ESCAPE` would put DuckDB on its backtracking one, seconds per 4 KiB value):
  - `*` is the only wildcard, at most 4 per pattern. `\` is an ordinary character: DuckDB's LIKE
    has no default escape.
  - Without a literal `%` or `_`, the value is a LIKE pattern with `*` mapped to `%`:
    `<col> LIKE CAST('<pattern>' AS VARCHAR)`.
  - With a literal `%` or `_`, `*` may only start or end the value; any inner `*` gets
    `invalid_filter`. The value without its edge `*`s is a needle, rendered as `<col> = <needle>`
    (no `*`), `starts_with(<col>, <needle>)` (`x*`), `ends_with(<col>, <needle>)` (`*x`) or
    `contains(<col>, <needle>)` (`*x*`), the needle as `CAST('<needle>' AS VARCHAR)`.
  - `ilike` renders the same forms over `lower(<col>)` and `lower(CAST(... AS VARCHAR))`, never
    `ILIKE`. On DuckDB 1.5.4 this equals ILIKE for ASCII and for every BMP character checked;
    neither does full case folding (`ß` does not match `ss`).
  - `ESCAPE` is never emitted. The worst admitted pattern costs about 1 ms on a 4 KiB value
    (`RestLikePatternCostSpec`).
- **Paging and snapshots:**
  - `offset > 0` without `order` gets `order_required`.
  - More than one of the `asOf*` parameters gets `invalid_selector`.
- **Hard caps** (constants): 32 filters, 64 `in` items, 16 order terms, 256 selected columns, and
  4 KiB per value.

**Rendering.** `RestSql.render` is pure, and produces one statement that is handed to the executor:

```
SELECT <probed idents> FROM "<catalog>"."<schema>"."<table>" [AT (VERSION => <id>)]
 WHERE <p1> AND ...  ORDER BY <terms>  LIMIT <effectiveLimit + 1> OFFSET <offset>
```

- **Identifiers** go through `SqlLiterals.duckdbIdent`.
- **Values** go through `CAST(<SqlLiterals.duckdbLiteral> AS <probed type>)`. No byte of user input
  reaches the SQL text outside such a literal.
- **Names are fully qualified** with the session catalog (§6.6). `USE` only sets a default; it does
  not confine name resolution (S7).
- **No positional references, ever.** `ORDER BY` uses column names, and the data statement lists
  its columns explicitly. Positional references are the class of statement behind #114. The
  rewriter handles them since 0.9.6, and the edge never produces them in the first place.
- **Operator mapping:** `is.*` becomes `IS [NOT] NULL|TRUE|FALSE`, `neq` becomes `<>`, and `not.`
  becomes `NOT (...)`.
- **Limit:** `effectiveLimit = min(limit ?: defaultLimit, maxRows, PAT maxRows)`, computed by
  `ExecCaller.effectiveMaxRows` (`ondemand/api/ExecCaller.scala:25`). The statement fetches one
  extra row so the edge can detect truncation.
- **Snapshot (DuckLake):** the probe and the data statement carry the **same** `AT` id, and that
  id is the `X-QoD-Snapshot` value.

### 6.4 Response formats and headers (slice 1)

The format is taken from the `format` parameter first, then from `Accept`, and falls back to JSON.
Anything else gets 406 `unsupported_format`. Slice 2 adds `arrow` and `parquet`, streamed.

**`json`** (`application/json`):

- An array of objects, with keys in select order.
- Integers up to 64 bits are JSON numbers. HUGEINT is a string.
- DECIMAL is an exact number, built from `BigDecimal` and never through a double.
- NaN and ±Inf are strings.
- Dates and timestamps are ISO-8601, with `Z` when a zone is present.
- BLOB is base64.
- Nested types become nested JSON. The encoder does **not** reuse `ArrowRowsDecoder`, whose
  `toString` fallback loses information on nested types.

**`csv`** (`text/csv; charset=utf-8`):

- RFC 4180, with a header row and CRLF line endings.
- Nested values are written as JSON text.
- Values are not rewritten to defuse spreadsheet formulas. The operator documentation notes this.

**Headers on `/rows`:**

- `X-QoD-Snapshot`, on DuckLake only.
- `Content-Range: <offset>-<offset+n-1>/*`, or `*/*` when no row is returned.
- `X-QoD-Truncated: true`, only when a server or token cap cut the page below what the client asked
  for and more rows exist.

**Caching** is what this edge is for (§1.1), and it must never leak across principals:

- Every response carries `Cache-Control: private` and `Vary: Authorization`. A shared cache or CDN
  must never serve one principal's rows, which may be RLS-filtered, to another.
- A DuckLake request pinned by `asOf` or `asOfTag` has immutable content, so it gets
  `max-age=300`. The value is short so that a revoked grant stops being served quickly.
- Everything else gets `no-cache`.
- ETag is out of scope.

Header values are built only from integers and fixed strings.

**Encoding** reads the `ArrowReader` in `IO.blocking`. **One** idempotent finalizer calls
`Routed.close()`, which also deregisters the statement from `ActiveStatementRegistry`. It runs on
success, on error and on client disconnect.

- In slice 1 the body is bounded by the row cap and may be buffered.
- Slice 2's streaming formats must abort the connection on an error after the first byte, and must
  never finish a body that looks valid.

### 6.5 Truncation of listings

Listings are capped at `maxRows`. When the cap is hit, the response sets `X-QoD-Truncated: true`.

### 6.6 Database kinds

Every kind QoD registers (`model/TenantDbKind.scala`) is served. The table lists the only
differences between them:

| | `ducklake` | `duckdb-file` (including encrypted) | `memory` |
|---|---|---|---|
| Session catalog `<catalog>` | `TenantDb.catalogAlias(metastore)` | same | **`memory`**. `catalogAlias` returns `""` here (`model/TenantDb.scala:80-85`). |
| `AT` clause and `X-QoD-Snapshot` | always | never | never |
| `asOf*` | served | 400 `invalid_kind` (the preview rule) | 400 `invalid_kind` |
| Consistency across pages | guaranteed by sending `asOf` back | best-effort | best-effort. Each node holds its own in-memory data, as on every door. |
| Encryption at rest | transparent | transparent | n/a |

Two rules follow from the table:

- **One resolver for the session catalog.** The session catalog comes from the router's per-kind
  logic (`perKindDb`/`perKindSchema`, `edge/FlightSqlRouter.scala:419-428`).
  - That logic is extracted into one pure helper in `model/`, which both the router and the edge
    call. This refactors existing code; it does not add a new quoting helper.
  - The edge never calls `TenantDb.catalogAlias` directly. If the router and the edge disagreed,
    the ACL would validate one catalog while the node read another.
- **The probe and the data statement run on the same node.** Without a snapshot pin, the data
  statement is sent to the probe's node (`Routed.nodeId`), through the router's existing
  `preferredNode`, threaded through `ExecCaller` (§8.1).
  - If that node is gone, the router falls back as usual.
  - If the schema changed in between, the result is a 502, never SQL that skipped validation.

---

## 7. Transport hardening and residual risk

These are HTTP-layer defaults, not data policy.

### 7.1 Slice 1 transport

- **TLS** is on by default. It reuses `CertGen.ensureCertFiles` and `PemKeyStore`, like
  `QuackFrontDoorServer`. With TLS off on a non-loopback host, boot logs a WARN.
- **Ember limits** (S6):
  - 16 KiB of headers, which also bounds the URI;
  - a 10 s timeout for receiving request headers;
  - a 60 s idle timeout;
  - 512 connections.
- **Security headers** go on every response, including errors generated by Ember or Tapir:
  - `X-Content-Type-Options: nosniff`;
  - `Content-Security-Policy: default-src 'none'; frame-ancestors 'none'`;
  - `Referrer-Policy: no-referrer`;
  - HSTS when TLS is on;
  - no `Server` version header.
- **Error hygiene.** Node, JDBC and parser messages are logged at WARN with the request id, and
  are never sent to the client.
- **Log hygiene.**
  - The `Authorization` header is never logged.
  - Access logs record the route template and the sanitised parameter **names**, never their
    values, because filter values are often personal data.

### 7.2 Timeout without leaks

- The edge applies `timeoutTo(min(stmtTimeoutSec, PAT stmtTimeoutMs))` over the executor.
  `routedExecutor` already enforces the PAT's own bound.
- A `Routed` that arrives **late** must be closed, not dropped. The edge runs the executor as a
  fiber whose `guaranteeCase` finalizer closes any late result.
- The same fix belongs in `routedExecutor`'s own `timeoutTo` branch (`Main.scala:1509-1520`), which
  currently drops the late result.
- The node keeps running either way (O-4).

### 7.3 Proposed for slice 2 (O-1): minimal abuse controls

These are not in the maintainer's slices; they are offered for agreement.

- **Per-IP throttle on failed authentication:**
  - The client IP is the TCP peer. `X-Forwarded-For` is honoured only when the peer is in
    `trustedProxies`, and is read from the right.
  - IPv4-mapped addresses are normalised. IPv6 addresses are keyed per /64.
  - After N 401s within the window, the IP gets 429 before any credential lookup.
  - A global budget catches distributed attempts. Entries that are currently blocking are never
    evicted.
- **Per-user in-flight cap**, keyed by `(tenant, userId)` so that minting more PATs does not buy
  more slots. A slot is released only when the node call has actually finished.

Until then, the operator documentation requires a reverse proxy or WAF for internet exposure (O-1).

### 7.4 Hibernated pools

The router's `resolveSnapshot` resumes a suspended pool and holds the request for up to
`resumeHoldTimeoutSec` (`edge/FlightSqlRouter.scala:904`). The resulting
`Unavailable("pool is resuming…")` becomes 503 `pool_resuming` with `Retry-After: 5`.

Only a request that has passed PAT authentication and tenant binding ever reaches the router, and
pool authorization runs inside `routedExecutor` before `execute`. An anonymous caller therefore
never wakes a pool.

---

## 8. Changes outside `edge/rest`

### 8.1 `ExecCaller`, `routedExecutor` and `FlightSqlRouter.execute`

The changes are minimal. Every new field has a default, so no existing call site changes.

- `ExecCaller` gains `source: String = "flightsql"` and `preferredNode: Option[String] = None`.
- `routedExecutor` forwards both to `fsRouter.execute`.
- `FlightSqlRouter.execute` gains `source: String = "flightsql"` and forwards it to `executeWith`.
  Today `execute` hard-codes the value (`edge/FlightSqlRouter.scala:363`).
- MCP can then pass `source = "mcp"`. That is optional, and a one-line change.

### 8.2 Config (`quack-rest`, modelled on `quack-native`)

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
  maxConnections = 512
  maxConnections = ${?QOD_REST_MAX_CONNECTIONS}
  maxHeaderBytes = 16384
  maxHeaderBytes = ${?QOD_REST_MAX_HEADER_BYTES}
  headerReceiveTimeoutSec = 10
  headerReceiveTimeoutSec = ${?QOD_REST_HEADER_RECEIVE_TIMEOUT_SEC}
  idleTimeoutSec = 60
  idleTimeoutSec = ${?QOD_REST_IDLE_TIMEOUT_SEC}
  # slice 2: corsAllowedOrigins (QOD_REST_CORS_ALLOWED_ORIGINS)
}
```

- **Config class.** `RestEdgeConfig` lives in `Config.scala`, with
  `@field @ConfigField(envVar, description)` on every field, like `QuackNativeConfig`.
- **Main.** Add a `ProductHint` and a `deriveReader`.
- **`ConfigRegistry.rootsFor`.** Add an entry. That is what makes the block appear in the generated
  configuration reference (O-2).
- **Boot validation** covers only the numbers (`defaultLimit >= 1`, `maxRows >= defaultLimit`, and
  so on) and a port that clashes with another door. It follows the `Either[String, Unit]` pattern
  of `HaPreconditions.validate`.

### 8.3 Boot, banner, shutdown, Helm

- **Main.** `Option.when(cfg.enabled)(new RestEdgeServer(...))`, chained into `dataPlaneIO` after
  the Quack door (`Main.scala:1230-1268`). A bind failure aborts boot.
- **Banner (Q4).** Add one REST line next to the existing `SQL ACL` line. `Banner.startup` gains a
  `rest: Option[(String, Int, Boolean)]` parameter, like `quack`:
  ```
     REST (data)   : https://localhost:31339/api/v1  (PAT bearer, read-only)
  ```
  When the ACL is disabled, the same line carries the warning, in the same style:
  ```
     REST (data)   : https://localhost:31339/api/v1  (ACL DISABLED: any PAT of the tenant can read every table)
  ```
  The same text is also logged with `logger.warn`, next to `BootFactories`' "SQL ACL disabled"
  warning.
- **ShutdownCoordinator.** Add `restEdge: Option[RestEdgeServer]`, stopped both in the JVM hook
  and in `gracefulShutdown`.
- **Helm (slice 1):**
  - `service.rest`, and a `{{ fullname }}-rest` Service gated by `rest.enabled`;
  - `containerPort: 31339`;
  - `QOD_REST_ENABLED` and `QOD_REST_TLS_ENABLED` in the configmap;
  - a NetworkPolicy ingress rule;
  - a `NOTES.txt` block.

  The Dockerfile `EXPOSE` and the docker-compose port come along with these.

### 8.4 Endpoint registration and repo guards

- **Registration.** Add `RestEdgeEndpoints` to `EndpointModules.all`, so that `GenOpenApi` emits
  the endpoints and `OpenApiFreshnessSpec` pins them.
  - Regenerate `cli/tests/resources/openapi.yaml` in the same commit.
  - `ManagerServer` **must not** mount these endpoints. Only `RestEdgeServer` serves them.
  - In the OpenAPI description, tag them `rest-edge` and name their port.
- **`cli/tests/test_rest_parity.py`.** Add the four paths to `EXCLUSIONS`, with a
  "machine-to-machine" comment, as for SCIM.
- **`TenantScopeCompletenessSpec`.** This spec reflects over `EndpointModules` and asserts that
  `TenantScopeGuard.extractTenant` resolves every `{tenant}` path. The REST paths are not behind
  `apiKeyGuard`: their tenant binding is §5 step 4. Two options:
  - **A:** add an explicit, commented exclusion for the `rest-edge` module in the spec.
  - **B:** teach `extractTenant` the new pattern, which does no harm.

  Prefer A: the spec should not claim that a guard applies when it does not.
- **`McpCoverageSpec`** checks only mutating methods, so it is unaffected.

---

## 9. Observability

- **Slice 1:**
  - The audit origin is `rest` on denials and on `SessionOpened` (constraint 5).
  - Statement history and usage metering go through the router, as for every door, with `patId`
    carried by `ExecCaller`.
- **Slice 2:** Micrometer meters, named like the existing ones:
  - `rest_requests_total{endpoint,format,status}`;
  - `rest_request_duration_seconds{endpoint,format}`.

  `endpoint` is the route template, which keeps cardinality bounded.

---

## 10. Threat model

| # | Threat | Control | Tests |
|---|---|---|---|
| T1 | SQL injection through a value, column, order term or path segment | Pure parser. Identifiers only from the probe. Values only through `SqlLiterals` + `CAST`. Path identifier rule. One statement, through the ordinary validator. | U3, U4, H6 |
| T2 | Superuser or static-key access | PAT bearer only. A tenant-null PAT gets 401. Never `X-API-Key`, never `ExecCaller.unrestricted`. | H3 |
| T3 | Cross-tenant access | The owner's tenant must equal the path tenant (the `tenantOf` mirror), and `routedExecutor` looks the user up inside that tenant | H4, E3 |
| T4 | Enumerating databases, schemas, tables or tags | The same 404 for missing and for not granted. An empty schema gets 404. Tags are never echoed. Discovery is filtered by grants. | H5, D1-D4 |
| T5 | Policy bypass (RLS, CLS, the masked-column oracle, positional references) | One pipeline. No positional references are generated. S1 and S3 are pinned, and fixed in the rewriter if needed. | P1, P2 |
| T6 | Leaving the tenant catalog | Three-part names from a single catalog resolver. S7. | P8, P9 |
| T7 | Resource exhaustion | Row, time, filter, size and wildcard caps. Ember limits. Late results are closed. O-1 for rate limiting. | U1, H8, H9 |
| T8 | Cache leakage across principals | `Cache-Control: private` and `Vary: Authorization` everywhere | H1 |
| T9 | Tokens or personal data in logs and errors | Neither `Authorization` nor parameter values are logged. Fixed error messages. Sanitised names. | H10, H12 |
| T10 | Request desync, slowloris | A GET with a body is refused. A header receive timeout. | H9 |
| T11 | ACL accidentally off | A banner warning (Q4). Discovery still fails closed through filtered metadata. | U6 |

---

## 11. Tests

**Conventions:**

- ScalaTest `AnyFlatSpec with Matchers`, with one spec per class under
  `src/test/scala/ai/starlake/quack/edge/rest/`.
- Test names describe behaviour.
- No ScalaCheck. Generated inputs come from a seeded `scala.util.Random` loop that prints its seed.

**Write each spec red first.** The three specs the maintainer asked for are U, H and E below. D is
the discovery spec required by constraint 2.

### 11.1 Pure specs (U): `RestQuerySpec`, `RestSqlSpec`, `RestSqlInjectionSpec`

**U1: operators and rejections.**

- Every operator (`eq neq gt gte lt lte like ilike in is`), with and without `not.`.
- `in` with quoted items containing `,`, `)`, `"` and `\`.
- `is.null`, `is.true` and `is.false`.
- Every `order` suffix, and the default select.
- **Every rejection, each with its error code:**
  - an unknown operator, an empty value, an unbalanced `in`;
  - a reserved parameter given twice, `branch`, a duplicate `select` column;
  - each cap at N+1 (and not at N);
  - a NUL byte, an invalid `%` sequence;
  - `offset>0` without `order` (`order_required`), and several `asOf*` at once (`invalid_selector`);
  - `limit` values `+1`, `01`, `1e3` and `2147483648`;
  - an exponent in a decimal, and out-of-range integers;
  - `like` on a non-string column, and a filter or `order` on a nested column.

**U2: column resolution.**

- Matching is ASCII case-insensitive, and the Kelvin sign does not match `k`.
- Ambiguous or unknown columns give `unknown_column`.
- `reserved_column`:
  - `limit=eq.5` on a table with a `limit` column gives `reserved_column`;
  - `limit=5` stays a limit either way;
  - `order=eq.asc` on a table with a column named `eq` is an ordinary order.

**U3: golden SQL.**

- The operator matrix, and the LIKE forms of §6.3 (never `ILIKE`, never `ESCAPE`).
- The three-part name for each kind (`"memory"."main"."t"` for `memory`).
- `AT` present and absent, and `LIMIT n+1 OFFSET`.
- **Never** an ordinal in `ORDER BY`, and never `*` in the data statement.

**U4: injection.** A corpus plus 10 000 seeded strings, each used both as a value and as a probed
column name, checked by three oracles:

1. A structural scanner that mirrors `SqlLiterals` quoting leaves only a closed set of tokens.
2. Executed in in-process DuckDB (`edge/adapter/TestArrow.scala`), `eq` returns exactly the
   matching row, `not.eq` returns the rest, and a sentinel table survives.
3. `json_serialize_sql` sees exactly one statement.

**U5: encoder.**

- JSON and CSV for each type family: DECIMAL(38,10) exact, BIGINT bounds, HUGEINT as a string,
  NaN/±Inf, timestamps with and without a zone, date, BLOB as base64, nested types, and NULL at
  every depth.
- CSV quoting with CRLF.
- Inputs come from `TestArrow.readerFor(sql)`.

**U6: config.**

- Numeric validation and the port clash.
- The banner line and the WARN when the ACL is disabled.

**U7: format negotiation.**

- `format` wins over `Accept`.
- `arrow` and `parquet` get 406 in slice 1.
- The fallback is JSON.

### 11.2 The edge over the executor seam, without the wire (H): `RestEdgeHandlersSpec`

**Harness.** `RestEdgeHandlers` is built with a **recording `PreviewExecutor`** stub, modelled on
`McpDataToolsSpec.capturingExecutor` (`src/test/.../mcp/McpDataToolsSpec.scala:247`).

- The stub counts calls, can block on a latch, and returns canned Arrow data built with
  `TestArrow`.
- The PAT resolver and the supervisor are the in-memory fixtures that the MCP specs use.
- A second, small spec, `RestEdgeServerSpec`, covers what only the wire can show (H9, H11, H12). It
  uses the harness pattern of `QuackFrontDoorServerSpec`: `freePort()`, and raw sockets wherever
  the JDK client would normalise the request.

**H1: happy path**, for each endpoint and format.

- The captured `ExecCaller` has `source="rest"`, the right `patId` and
  `connectionId = rest-<patId>`, and is never `unrestricted`.
- The probe and the data SQL carry the same `AT` id, which equals `X-QoD-Snapshot`.
- The pinned `preferredNode` equals the probe's node.
- `Cache-Control: private` and `Vary: Authorization` are present. `max-age` appears only when
  `asOf` is pinned.

**H2: tools axis.**

- `tools=None` is admitted, and `tools={rest}` is admitted.
- `tools={run_sql}` gets 403.
- A `branchOnly` token is admitted, and its statement is classified READ.

**H3: credentials.**

- A superuser PAT gets a 401 that is **byte-identical** to the 401 for a garbage token.
- `X-API-Key`, `Basic`, a cookie, two `Authorization` headers, `?access_token=` alone, and a
  non-PAT bearer (slice 1) all get 401.
- A token of 8 KiB is accepted; one byte more is refused.
- The scheme is matched case-insensitively.

**H4: tenant.** A PAT from another tenant and an unknown path tenant get the same 403, and the
executor is never called.

**H5: identical 404s.** In each case the body, the headers and the executor call count are
identical:

- a missing table versus an ungranted one (the stub returns `NotFound` or `AccessDenied`);
- a missing database versus one outside the `databases` axis;
- a branch tenant-db;
- an empty schema.

A tag-not-found message never echoes the tag.

**H6: path segments.** `..`, `%2F`, quotes, over-long names, `information_schema` and `pg_catalog`
all get 404, and the stub sees nothing.

**H7: limits.**

- Cap precedence between `maxRows`, the PAT's `maxRows` and `limit`. `X-QoD-Truncated` is set only
  when a cap cut the page.
- `order_required`.
- A non-DuckLake `asOf` gets `invalid_kind`, and the SQL carries no `AT`.

**H8: timeouts and resuming pools.**

- An injected `FiniteDuration` seam produces 504, and a late `Routed` is closed exactly once.
- A resuming pool gets 503 `pool_resuming` with `Retry-After`.

**H9: wire, over a raw socket.**

- A 17 KiB header gets 431 (or whatever S6 pins).
- A slow header gets the connection closed.
- A GET with `Content-Length: 1000000000` gets 400 with `Connection: close`.
- `POST` and `HEAD` get 405.

**H10: logs**, captured with a logback `ListAppender` testkit.

- No token and no parameter value appears anywhere.
- A bad operator in `email=eq.secret@x.io` keeps `secret` out of both the log and the body.
- Hostile parameter names are sanitised.

**H11: headers.** Security headers and `X-Request-Id` are present on errors generated by Ember and
Tapir too. A client's `X-Request-Id` is not echoed.

**H12: upstream errors.** A stub that throws a secret-looking message produces a 502 that carries
the request id but not the message.

### 11.3 Discovery fails closed (D): `RestDiscoveryScopeSpec`, in the style of `RbacTenantScopeSpec`

**Harness.** The `security/ManagerServerHarness` fixtures (`InMemoryControlPlaneStore`, grants).
The executor is wired to the router's real text pipeline (`FlightSqlRouterSpec.setupWithRewriter`,
which captures the post-rewrite SQL), and in-process DuckDB provides the semantics.

- **D1.** For each of the three kinds (S5), the `schemas`, `tables` and table-detail listings
  contain exactly the objects the principal holds a Read-covering grant on.
- **D2.** For each of `/tables/{t}`, `/tables/{t}/rows` and `/schemas/{s}/tables`, an ungranted
  object and a missing object get byte-identical 404s.
- **D3.** CLS:
  - a dropped column is absent from the detail;
  - naming a dropped column in `select`, `order` or a filter gets `unknown_column`;
  - a masked column is listed as an ordinary column.
- **D4.** With `filteredMetadata=false` and no grant on `information_schema`, the listings return
  404, not an unfiltered list.

### 11.4 Pipeline and end-to-end (P, E)

**Pipeline specs** use the router's text pipeline plus in-process DuckDB:

- **P1 (S1).** `AT` on a three-part reference survives CLS and RLS, and the policy still applies.
  RLS filters `/rows` in combination with filters and ordering.
- **P2 (S3).** Use a mask that keeps partial information (`left(ssn,3)||'***'`), and data whose raw
  and masked sort orders and LIKE matches differ. `like`, `gt`, `is.null` and `order` on the masked
  column must match what the same operations give on the masked values. The test is written first
  and stays red until the rewriter satisfies it.
- **P8 (S7).** For every kind, including `memory`, a three-part name stays inside the tenant
  catalog, even under a schema-wide `*` grant.
- **P9.** For every kind and metastore shape, the router's `defaultDatabase` equals the edge's
  `<catalog>`, because both come from one resolver.

**`RestEdgeEndToEndSpec`** (E) follows the style of `QuackCompatibilitySpec` and
`PreviewEndToEndSpec.withRouter` (`src/test/.../it/PreviewEndToEndSpec.scala:84`). It runs a live
pool spawned through `LocalQuackBackend`, with a `RestEdgeServer` on a free port.

It **must stay green while a developer manager is running on `:20900`**, so:

- every listener binds `freePort()`, never 31339, 9494, 31338 or 20900;
- node ports come from a range outside the manager's `21900-22500`, or from `freePort()`;
- the control plane and the catalogs use `PostgresFixture` or embedded Postgres with their own
  database names, never the live `qod` database or its tenant-db databases;
- the spec never calls `scripts/kill-quack-nodes.sh`, and tears down only the nodes it spawned;
- the spec is cancelled when `duckdb` is missing.

End-to-end cases:

- **E1.** Every endpoint, in JSON and CSV, against a real DuckLake table and a real view.
- **E2 (pagination).** Read page 1. Commit an insert that sorts **before** the page-2 cursor, plus
  a delete. Page 2, read with `asOf=<X-QoD-Snapshot>`, still matches the original set. The same
  check on a view, subject to S2.
- **E3.** A tenant-B PAT on a tenant-A path is refused.
- **E4.** Cap precedence.
- **E5.** A denial is audited with origin `rest`, and history shows the statement with the PAT id.
- **E6 (cold start).** A suspended pool either resumes within the hold (200) or answers 503
  `pool_resuming` once the hold expires.
- **E7.** The S8 measurement, recorded in §2.5.
- **E8.** Every kind: `ducklake`, an encrypted `duckdb-file`, and `memory` (qualified
  `"memory"."main"`). A non-DuckLake `asOf` gets `invalid_kind`.

### 11.5 Repo guards that must stay green

- `OpenApiFreshnessSpec`, with `openapi.yaml` regenerated.
- `cli/tests/test_rest_parity.py`, with the new exclusions.
- `TenantScopeCompletenessSpec`, per the choice made in §8.4.
- `McpCoverageSpec`.
- `GenConfigDocsSpec`.
- Every existing `FlightSqlRouter*`, `QuackFrontDoor*`, MCP and PAT spec. The new `source` and
  `preferredNode` fields default to today's behaviour.

---

## 12. PR plan

**PR 1 (slice 1):**

| Phase | Content | Gate |
|---|---|---|
| 1a | Spikes S1-S3 and S5-S8 as committed tests (P1, P2, P8, P9, E7). Any policy gap is fixed **in the rewriter or validator**, in a commit of its own. | none |
| 1b | The §8.1 plumbing (`source`, `preferredNode`), extraction of the catalog resolver, and closing late results in `routedExecutor`. | none |
| 1c | The pure core: `RestQuery`, `RestSql`, the encoder, format negotiation. Tests U1-U7. | S8 |
| 1d | `RestEdgeHandlers`, `RestEdgeServer`, config, banner, wiring, shutdown, and endpoint registration with the guards (§8.4). Tests H1-H12, D1-D4, E1-E8. | S1-S3, S5-S7 |
| 1e | Helm port and Service; Dockerfile and compose; the configuration-reference block (O-2); README ports and section; CLAUDE.md (four sockets become five); a CHANGELOG entry; the operator skill and its bundled copy, including the O-1 deployment requirement. | 1d |

**PR 2 (slice 2):**

| Phase | Content | Gate |
|---|---|---|
| 2a | OIDC bearer (§5.3), with its own tests: a valid token, no `exp`, expired, wrong `aud`/`iss`, `alg=none`, and a global provider refused. | PR 1 merged |
| 2b | Arrow IPC and Parquet, streamed. Parquet needs either a writer dependency or a node-side `COPY`; decide in the PR. Abort the connection on an error after the first byte. | PR 1 merged |
| 2c | CORS (`corsAllowedOrigins`): exact origins or `*`, never credentials, `Vary: Origin`. | PR 1 merged |
| 2d | Prometheus metrics (§9). | PR 1 merged |
| 2e | The O-1 abuse controls, **only if the maintainer agrees**. | maintainer |

---

## 13. Code-style notes for the implementor

- **Syntax and formatting.** Scala 3 indentation syntax, `final case class` and `final class`,
  scalafmt 3.10 (`maxColumn = 100`). Run `sbt scalafmtAll` before every commit.
- **Comments.** Every class and object gets a Scaladoc that explains **why** it exists, citing this
  spec where a decision is not obvious, as the Quack door does. Inline `//` comments state
  rationale and invariants, not narration; the comment blocks in `routedExecutor` show the expected
  density.
- **Errors:**
  - an `enum` ADT for the edge's own failures, shaped like `RouterFailure`;
  - `Either[String, _]` for validation;
  - `(StatusCode, ErrorResponse)` at the Tapir boundary, with snake_case codes;
  - throw only at boot.
- **Handlers** return `IO[Either[(StatusCode, ErrorResponse), A]]`, with pre-built error `val`s as
  in `PatHandlers`. Blocking work runs in `IO.blocking`.
- **Test seams** are constructor function parameters with safe defaults (the executor, the clock,
  the timeout). No mocking libraries.
- **Reuse; never add a parallel helper** (constraint 1). Reuse `SqlLiterals.duckdbIdent` and
  `duckdbLiteral`, `SnapshotSelector`, `PoolPicks.readPoolKey`, `ExecCaller.effectiveMaxRows`,
  `PatAuthenticator.resolve` and the `McpToolArgs.tenantOf` semantics. Do not add a sixth private
  `quoteIdent`.
- **Keep the edge thin.** No policy code in `edge/rest/`. When a test exposes a policy gap, fix the
  validator or rewriter, in a commit of its own.
- **Keep files small.** `Main.scala` gets wiring only, and feature files run 100-400 lines.
- **Commits** follow Conventional Commits (`feat(rest): …`, `test(rest): …`): a subject under 70
  characters, a body that explains why, and one logical unit per commit.
- **Config.** Every scalar gets an `${?QOD_REST_*}` override and an `@ConfigField`. Never edit the
  bundled `application.conf` for local tweaks.
- **Local suite.** Run `sbt test` with a live manager on `:20900` before pushing (§11.4).

---

## Appendix A: earlier author decisions replaced by the maintainer's answers

| Earlier (revision 1) | Now | Reason |
|---|---|---|
| A new PAT column `rest_access` (opt-in, via Liquibase) | The `tools` axis with the reserved name `rest`. Unrestricted PATs are admitted. | Q2: no new column, no Liquibase |
| OIDC in v1, validated by the tenant's own provider | Slice 2 | Q3: nothing on the HTTP side validates JWTs today |
| Boot refused when the ACL or `filteredMetadata` is off | A banner warning; discovery still fails closed | Q4 |
| Reserved names treated as filters silently | 400 `reserved_column` | Q6 |
| `asOf` on a non-DuckLake database gave `time_travel_unsupported` | `invalid_kind` | Follows the preview endpoint's rules |
| The edge authorized before resolving the snapshot (a `preAuthorized` path and an extracted `RoutedExecutor`) | Authorization stays inside `routedExecutor`; the snapshot follows the preview rules | Keep the edge a thin translator |
| A `source` column in history and a `source` metrics tag | `source` passed to `executeWith` only, as the native door does | Constraint 5 |
| A standalone `/api/v1/openapi.json` | Endpoints registered in `EndpointModules` for `GenOpenApi` | Constraint 6 |
| A per-IP throttle and a per-user cap in v1 | Proposed for slice 2 (O-1); a WAF is required until then | Not in the maintainer's slices |
| Arrow in v1, Parquet parked | Arrow and Parquet in slice 2, streamed | Slicing |
| `Cache-Control: no-store` | `private` + `Vary: Authorization`, with `max-age` only for pinned snapshots | Cacheable GET resources are the point of this door (§1.1) |

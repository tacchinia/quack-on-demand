package ai.starlake.quack.edge.rest

import ai.starlake.quack.ondemand.api.Dtos.given
import ai.starlake.quack.ondemand.api.ErrorResponse
import sttp.model.{Header, StatusCode}
import sttp.tapir.*
import sttp.tapir.generic.auto.*
import sttp.tapir.json.circe.*
import sttp.tapir.model.ServerRequest

/** The REST data edge's four `GET` routes as Tapir values (design §6, §8.4 of
  * `docs/superpowers/specs/2026-09-25-quack-rest-data-edge-design.md`).
  *
  * Registered in `EndpointModules.all` so `GenOpenApi` documents them, and served ONLY by
  * [[RestEdgeServer]] on its own port: `ManagerServer` must never mount them, since the manager's
  * `apiKeyGuard` (static key, session cookie) is exactly the credential set this edge refuses.
  *
  * The query string and the headers are read RAW through one `extractFromRequest` input rather than
  * as typed `query`/`header` inputs: the form decoding behind those turns `+` into a space and
  * answers a duplicated parameter or a second `Authorization` header with a decode failure of its
  * own, whereas §6.1 wants `+` literal and a duplicate reserved parameter as `invalid_parameter`,
  * and §5 wants two `Authorization` headers as the one 401. The parameter vocabulary is therefore
  * documented in each description instead of as OpenAPI parameters.
  */
object RestEdgeEndpoints:

  /** The request-id header the server mints for every request (§4.1) and the handlers read. */
  val RequestIdHeader = "X-Request-Id"

  private val Tag = "rest-edge"

  private val PortNote =
    "Served by the REST data edge on its own port (quack-rest, default 31339), never on the " +
      "manager port. Auth: `Authorization: Bearer <PAT>` only; the token's tools axis must allow " +
      "`rest`. Parameters: `pool` (default: a read-capable pool of the database), `format` " +
      "(`json` or `csv`; else `Accept`, else JSON)."

  private val TimeTravelNote =
    " DuckLake only: at most one of `asOf` (snapshot id), `asOfTag`, `asOfTs` (ISO-8601); the " +
      "pinned snapshot comes back in `X-QoD-Snapshot`."

  /** The raw query string as the client sent it, before any decoding. The http4s interpreter's
    * request keeps it unparsed; any other interpreter falls back to the re-rendered URI.
    */
  private def rawQuery(req: ServerRequest): String = req.underlying match
    case r: org.http4s.Request[?] => r.uri.query.renderString
    case _                        =>
      val s = req.uri.toString
      val q = s.indexOf('?')
      if q < 0 then "" else s.substring(q + 1).takeWhile(_ != '#')

  private def values(req: ServerRequest, name: String): List[String] =
    req.headers.filter(_.is(name)).map(_.value).toList

  /** Where [[RestEdgeServer]] parks the client's `Accept` before Tapir sees the request. Tapir's
    * own content negotiation would answer `Accept: text/csv` with a 406 of its own, since the
    * endpoints' body is declared as a plain string; negotiation is the handlers' job (§6.4).
    */
  private[rest] val AcceptAttribute: org.typelevel.vault.Key[String] =
    org.typelevel.vault.Key.newKey[cats.effect.SyncIO, String].unsafeRunSync()

  private def accept(req: ServerRequest): Option[String] =
    val parked = req.underlying match
      case r: org.http4s.Request[?] => r.attributes.lookup(AcceptAttribute)
      case _                        => None
    parked.orElse(Option(values(req, "Accept")).filter(_.nonEmpty).map(_.mkString(",")))

  /** Everything the handlers read from the request, raw (see the object Scaladoc). */
  val request: EndpointInput[RestRequest] = extractFromRequest { req =>
    RestRequest(
      authorization = values(req, "Authorization"),
      accept = accept(req),
      rawQuery = rawQuery(req),
      requestId = values(req, RequestIdHeader).headOption.getOrElse("")
    )
  }

  private val base = endpoint.get
    .in("api" / "v1" / "tenant" / path[String]("tenant") / "database" / path[String]("tenantDb"))
    .errorOut(statusCode.and(headers).and(jsonBody[ErrorResponse]))
    .out(headers)
    .out(
      stringBody.description(
        "application/json (an array of objects) or text/csv, per `format` then `Accept`; the Content-Type header says which"
      )
    )
    .tag(Tag)

  type Error = (StatusCode, List[Header], ErrorResponse)

  val listSchemas
      : PublicEndpoint[(String, String, RestRequest), Error, (List[Header], String), Any] =
    base
      .in("schemas")
      .in(request)
      .description(
        s"""List the schemas of a database the caller can read: `[{"name"}]`. $PortNote"""
      )

  val listTables
      : PublicEndpoint[(String, String, String, RestRequest), Error, (List[Header], String), Any] =
    base
      .in("schemas" / path[String]("schema") / "tables")
      .in(request)
      .description(
        "List the tables and views of a schema the caller can read: " +
          s"""`[{"name", "type": "table"|"view"}]`; a schema with nothing visible is a 404. $PortNote"""
      )

  val describeTable: PublicEndpoint[
    (String, String, String, String, RestRequest),
    Error,
    (List[Header], String),
    Any
  ] =
    base
      .in("schemas" / path[String]("schema") / "tables" / path[String]("table"))
      .in(request)
      .description(
        """Describe one table or view as the caller sees it after policy: `{"name", "type", """ +
          s""""columns": [{"name", "type"}]}`. $PortNote$TimeTravelNote"""
      )

  val readRows: PublicEndpoint[
    (String, String, String, String, RestRequest),
    Error,
    (List[Header], String),
    Any
  ] =
    base
      .in("schemas" / path[String]("schema") / "tables" / path[String]("table") / "rows")
      .in(request)
      .description(
        "Read rows as one SELECT through the caller's grants and policies. `select=a,b`; " +
          "filters `<col>=[not.]<op>.<value>` with op eq, neq, gt, gte, lt, lte, like, ilike, " +
          "in, is (repeat to AND); `order=col[.asc|.desc][.nullsfirst|.nullslast],...`; `limit`, " +
          "`offset` (needs `order`). Reserved names (select, order, limit, offset, asOf, asOfTag, " +
          "asOfTs, pool, format, branch) cannot filter a column of that name: 400 " +
          "`reserved_column`. Headers: `Content-Range`, `X-QoD-Truncated` when a server or token " +
          s"cap cut the page. $PortNote$TimeTravelNote"
      )

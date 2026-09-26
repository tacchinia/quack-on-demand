package ai.starlake.quack.edge.rest

import ai.starlake.quack.ondemand.api.QueryParams

import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer
import java.nio.charset.{CharacterCodingException, CodingErrorAction, StandardCharsets}
import java.time.Instant

/** Filter operators of the `/rows` grammar (design §6.3), keyed by their wire spelling. */
enum FilterOp(val wire: String):
  case Eq    extends FilterOp("eq")
  case Neq   extends FilterOp("neq")
  case Gt    extends FilterOp("gt")
  case Gte   extends FilterOp("gte")
  case Lt    extends FilterOp("lt")
  case Lte   extends FilterOp("lte")
  case Like  extends FilterOp("like")
  case ILike extends FilterOp("ilike")
  case In    extends FilterOp("in")
  case Is    extends FilterOp("is")

object FilterOp:
  private val byWire: Map[String, FilterOp] = values.map(o => o.wire -> o).toMap
  def fromWire(s: String): Option[FilterOp] = byWire.get(s)

/** The comparison operators and their SQL spelling (`neq` is `<>`, §6.3). */
enum Comparison(val sql: String):
  case Eq  extends Comparison("=")
  case Neq extends Comparison("<>")
  case Gt  extends Comparison(">")
  case Gte extends Comparison(">=")
  case Lt  extends Comparison("<")
  case Lte extends Comparison("<=")

/** What a filter tests, shaped by its operator so no operator can meet a value of the wrong form.
  */
enum Predicate:
  /** `eq neq gt gte lt lte`: the raw text, type-checked later against the probed column. */
  case Compare(cmp: Comparison, raw: String)

  /** `like ilike`: the pattern with `*` mapped to `%` and a literal `%`, `_` or `\` escaped with
    * `\`. `escaped` says whether any escape was written, which is when (and only when) `RestSql`
    * emits `ESCAPE '\'` (§6.3).
    */
  case Pattern(caseInsensitive: Boolean, sqlPattern: String, escaped: Boolean)

  /** `in`: the decoded items, each type-checked later like a [[Compare]] value. */
  case Items(items: Vector[String])

  /** `is`: `null`, `true` or `false`. */
  case Is(target: IsTarget)

enum IsTarget(val sql: String):
  case Null  extends IsTarget("NULL")
  case True  extends IsTarget("TRUE")
  case False extends IsTarget("FALSE")

/** One column filter: `<param>=[not.]<op>.<value>`. `param` is the column as the client spelled it;
  * resolution against the probe happens in [[RestResolver]].
  */
final case class Filter(param: String, negated: Boolean, predicate: Predicate)

enum NullsOrder(val sql: String):
  case First extends NullsOrder("NULLS FIRST")
  case Last  extends NullsOrder("NULLS LAST")

final case class OrderTerm(column: String, descending: Boolean, nulls: Option[NullsOrder])

/** A reserved parameter whose value also parses as a filter expression (`limit=eq.5`). Whether that
  * is a `reserved_column` error depends on the probed table (Q6, §6.1), so the parser only records
  * it; `ownError` is the error the parameter's own syntax produced, raised by [[RestResolver]] when
  * the table turns out NOT to have a column of that name. While pending, a parameter whose own
  * syntax failed is treated as absent.
  */
final case class PendingReserved(name: String, ownError: Option[RestError])

/** The schema-free AST of one request's query string (design §6.1, §6.3).
  *
  * Built only by [[RestQuery.parse]], which has already applied every check that does not need the
  * probed schema: grammar, reserved names and their duplicates, caps, NUL bytes, `limit`/`offset`
  * syntax, `branch` refused (Q7), `asOf*` mutual exclusion and `order_required`.
  */
final case class RestQuery(
    select: Option[Vector[String]],
    filters: Vector[Filter],
    order: Vector[OrderTerm],
    limit: Option[Int],
    offset: Int,
    asOf: Option[Long],
    asOfTag: Option[String],
    asOfTs: Option[Instant],
    pool: Option[String],
    format: Option[String],
    pendingReserved: Vector[PendingReserved]
):
  /** `asOf` or `asOfTag` given: the content is immutable, so the handler may cache it (§6.4). */
  def pinned: Boolean = asOf.isDefined || asOfTag.isDefined

  def timeTravel: Boolean = asOf.isDefined || asOfTag.isDefined || asOfTs.isDefined

object RestQuery:

  /** Hard caps of §6.3. `MaxWildcards` is subject to spike S8. */
  val MaxFilters       = 32
  val MaxInItems       = 64
  val MaxOrderTerms    = 16
  val MaxSelected      = 256
  val MaxValueBytes    = 4096
  val MaxWildcards     = 4
  private val IntLimit = Int.MaxValue.toLong

  val Reserved: Set[String] =
    Set(
      "select",
      "order",
      "limit",
      "offset",
      "asOf",
      "asOfTag",
      "asOfTs",
      "pool",
      "format",
      "branch"
    )

  private val AsOfNames = List("asOf", "asOfTag", "asOfTs")

  // limit := [1-9][0-9]{0,9}; offset := "0" | the same (§6.3). No sign, no leading zero, no
  // exponent: `+1`, `01` and `1e3` are refused, not normalised.
  private val LimitRe    = "[1-9][0-9]{0,9}".r
  private val SnapshotRe = "0|[1-9][0-9]{0,18}".r

  /** Percent-decodes a raw query string exactly once (§6.1), which is the representation the parser
    * takes: the `(name, value)` pairs in arrival order.
    *
    * Why the edge decodes the raw string itself rather than taking Tapir's `QueryParams`: the
    * form-urlencoded convention those decoders follow turns `+` into a space and tends to pass a
    * malformed `%` through, whereas §6.1 wants `+` literal and a bad `%` sequence refused. A
    * handler that does take Tapir's `QueryParams` can feed `params.toSeq` to [[parse]] directly; it
    * then inherits that decoder's `+` and `%` behaviour.
    *
    * Empty segments (`a=1&&b=2`) are skipped; a segment without `=` is a name with an empty value.
    */
  def decodeQueryString(raw: String): Either[RestError, Vector[(String, String)]] =
    val segments = if raw.isEmpty then Vector.empty else raw.split("&", -1).toVector
    segments
      .filter(_.nonEmpty)
      .foldLeft[Either[RestError, Vector[(String, String)]]](Right(Vector.empty)) {
        case (acc, seg) =>
          acc.flatMap { out =>
            val eq           = seg.indexOf('=')
            val (rawK, rawV) =
              if eq < 0 then (seg, "") else (seg.substring(0, eq), seg.substring(eq + 1))
            for
              k <- percentDecode(rawK)
              v <- percentDecode(rawV)
            yield out :+ (k -> v)
          }
      }

  private val BadEncoding =
    RestError.InvalidParameter("query string: invalid percent-encoding")

  private def percentDecode(s: String): Either[RestError, String] =
    if s.indexOf('%') < 0 then Right(s)
    else
      val out = new ByteArrayOutputStream(s.length)
      var i   = 0
      var bad = false
      while i < s.length && !bad do
        val c = s.charAt(i)
        if c == '%' then
          if i + 2 < s.length then
            val hi = Character.digit(s.charAt(i + 1), 16)
            val lo = Character.digit(s.charAt(i + 2), 16)
            if hi < 0 || lo < 0 then bad = true
            else
              out.write((hi << 4) | lo)
              i += 3
          else bad = true
        else
          // A char that needs no decoding: re-encode it so the byte stream stays UTF-8.
          val end = if Character.isHighSurrogate(c) && i + 1 < s.length then i + 2 else i + 1
          out.writeBytes(s.substring(i, end).getBytes(StandardCharsets.UTF_8))
          i = end
      if bad then Left(BadEncoding)
      else
        val dec = StandardCharsets.UTF_8
          .newDecoder()
          .onMalformedInput(CodingErrorAction.REPORT)
          .onUnmappableCharacter(CodingErrorAction.REPORT)
        try Right(dec.decode(ByteBuffer.wrap(out.toByteArray)).toString)
        catch case _: CharacterCodingException => Left(BadEncoding)

  /** Parses decoded `(name, value)` pairs (in arrival order) into a [[RestQuery]]. */
  def parse(params: Seq[(String, String)]): Either[RestError, RestQuery] =
    val grouped  = params.groupBy(_._1)
    val filterPs = params.filterNot(p => Reserved.contains(p._1)).toVector
    val reserved = params.filter(p => Reserved.contains(p._1)).toMap
    val pending  = reserved.toVector.sortBy(_._1).collect {
      case (name, value) if parseFilterExpr(name, value).isRight =>
        PendingReserved(name, ownCheck(name, value).left.toOption)
    }
    // A pending parameter whose own syntax failed is absent until the resolver decides.
    val deferred = pending.filter(_.ownError.isDefined).map(_.name).toSet
    val own      = reserved.filterNot(p => deferred.contains(p._1))
    val twice    = Reserved.toList.sorted.find(n => grouped.get(n).exists(_.size > 1))
    for
      _       <- checkBytes(params)
      _       <- twice.map(RestError.invalidParameter(_, "given more than once")).toLeft(())
      _       <- guard(AsOfNames.count(grouped.contains) <= 1, MultipleSelectors)
      _       <- guard(filterPs.size <= MaxFilters, TooManyFilters)
      filters <- traverse(filterPs) { case (name, value) => parseFilterExpr(name, value) }
      _       <- guard(!own.contains("branch"), branchRefused)
      select  <- own.get("select").fold(Right(None))(v => parseSelect(v).map(Some(_)))
      order   <- own.get("order").fold(Right(Vector.empty))(parseOrder)
      limit   <- own.get("limit").fold(Right(None))(v => parseLimit(v).map(Some(_)))
      offset  <- own.get("offset").fold(Right(0))(parseOffset)
      asOf    <- own.get("asOf").fold(Right(None))(v => parseAsOf(v).map(Some(_)))
      asOfTag <- own.get("asOfTag").fold(Right(None))(v => nonEmptySelector("asOfTag", v))
      asOfTs  <- own.get("asOfTs").fold(Right(None))(parseAsOfTs)
      pool    <- own.get("pool").fold(Right(None))(v => nonEmpty("pool", v))
      format  <- own.get("format").fold(Right(None))(v => nonEmpty("format", v))
      _       <- guard(offset == 0 || order.nonEmpty, RestError.OrderRequired)
    yield RestQuery(
      select,
      filters,
      order,
      limit,
      offset,
      asOf,
      asOfTag,
      asOfTs,
      pool,
      format,
      pending
    )

  private val MultipleSelectors =
    RestError.InvalidSelector("supply only one of asOf, asOfTag, or asOfTs")
  private val TooManyFilters = RestError.InvalidParameter(s"at most $MaxFilters filters")

  private def guard(ok: Boolean, e: => RestError): Either[RestError, Unit] =
    if ok then Right(()) else Left(e)

  private val branchRefused =
    RestError.invalidParameter("branch", "branch reads are not supported")

  /** NUL anywhere, and the per-value byte cap, for every parameter. */
  private def checkBytes(params: Seq[(String, String)]): Either[RestError, Unit] =
    firstError(params.toList.flatMap { case (name, value) =>
      if name.isEmpty then Some(RestError.InvalidParameter("empty parameter name"))
      else if name.indexOf('\u0000') >= 0 || value.indexOf('\u0000') >= 0 then
        Some(RestError.invalidParameter(name, "NUL byte"))
      else if value.getBytes(StandardCharsets.UTF_8).length > MaxValueBytes then
        Some(RestError.invalidParameter(name, s"value exceeds $MaxValueBytes bytes"))
      else None
    })

  /** The reserved parameter's own syntax, used to decide what a pending one defers. */
  private def ownCheck(name: String, value: String): Either[RestError, Unit] = name match
    case "branch" => Left(branchRefused)
    case "select" => parseSelect(value).map(_ => ())
    case "order"  => parseOrder(value).map(_ => ())
    case "limit"  => parseLimit(value).map(_ => ())
    case "offset" => parseOffset(value).map(_ => ())
    case "asOf"   => parseAsOf(value).map(_ => ())
    case "asOfTs" => parseAsOfTs(value).map(_ => ())
    case _        => Right(())

  private def nonEmpty(name: String, v: String): Either[RestError, Option[String]] =
    if v.isEmpty then Left(RestError.invalidParameter(name, "empty value")) else Right(Some(v))

  private def nonEmptySelector(name: String, v: String): Either[RestError, Option[String]] =
    if v.isEmpty then Left(RestError.InvalidSelector(s"$name must not be empty"))
    else Right(Some(v))

  private def parseSelect(v: String): Either[RestError, Vector[String]] =
    val cols = v.split(",", -1).toVector
    if cols.exists(_.isEmpty) then Left(RestError.invalidParameter("select", "empty column"))
    else if cols.size > MaxSelected then
      Left(RestError.invalidParameter("select", s"at most $MaxSelected columns"))
    else if cols.map(RestResolver.asciiLower).distinct.size != cols.size then
      Left(RestError.invalidParameter("select", "duplicate column"))
    else Right(cols)

  /** `term := col ["." ("asc"|"desc")] ["." ("nullsfirst"|"nullslast")]`, suffixes read from the
    * right so a column name may itself contain dots.
    */
  private def parseOrder(v: String): Either[RestError, Vector[OrderTerm]] =
    val terms = v.split(",", -1).toVector
    if terms.size > MaxOrderTerms then
      Left(RestError.invalidParameter("order", s"at most $MaxOrderTerms terms"))
    else traverse(terms)(parseOrderTerm)

  private def parseOrderTerm(t: String): Either[RestError, OrderTerm] =
    def strip(s: String, suffixes: Map[String, Boolean]): (String, Option[Boolean]) =
      suffixes
        .collectFirst {
          case (sfx, flag) if s.endsWith("." + sfx) => (s.dropRight(sfx.length + 1), Some(flag))
        }
        .getOrElse((s, None))
    val (rest1, nullsFirst) = strip(t, Map("nullsfirst" -> true, "nullslast" -> false))
    val (col, desc)         = strip(rest1, Map("asc" -> false, "desc" -> true))
    if col.isEmpty then Left(RestError.invalidParameter("order", "empty column"))
    else
      Right(
        OrderTerm(
          col,
          desc.getOrElse(false),
          nullsFirst.map(if _ then NullsOrder.First else NullsOrder.Last)
        )
      )

  private def parseLimit(v: String): Either[RestError, Int] =
    if LimitRe.matches(v) && v.toLong <= IntLimit then Right(v.toInt)
    else Left(RestError.invalidParameter("limit", "expected an integer in 1..2147483647"))

  private def parseOffset(v: String): Either[RestError, Int] =
    if v == "0" then Right(0)
    else if LimitRe.matches(v) && v.toLong <= IntLimit then Right(v.toInt)
    else Left(RestError.invalidParameter("offset", "expected an integer in 0..2147483647"))

  private def parseAsOf(v: String): Either[RestError, Long] =
    if SnapshotRe.matches(v) && BigInt(v) <= Long.MaxValue then Right(v.toLong)
    else Left(RestError.InvalidSelector("asOf must be a snapshot id"))

  // Same parser (and wording) as the preview endpoint's asOfTs.
  private def parseAsOfTs(v: String): Either[RestError, Option[Instant]] =
    QueryParams
      .instantAs(Some(v), "asOfTs", "invalid_selector")
      .left
      .map { case (_, e) => RestError.InvalidSelector(e.message) }

  /** `[not.]<op>.<value>` (§6.3). Also the test for "parses as a filter expression" behind the
    * `reserved_column` rule, which is why a reserved name can be passed in.
    */
  def parseFilterExpr(name: String, raw: String): Either[RestError, Filter] =
    val negated = raw.startsWith("not.")
    val body    = if negated then raw.drop(4) else raw
    val dot     = body.indexOf('.')
    if dot < 0 then Left(RestError.invalidFilter(name, "expected [not.]<op>.<value>"))
    else
      FilterOp.fromWire(body.substring(0, dot)) match
        case None     => Left(RestError.invalidFilter(name, "unknown operator"))
        case Some(op) =>
          parseValue(name, op, body.substring(dot + 1)).map(Filter(name, negated, _))

  private def parseValue(name: String, op: FilterOp, v: String): Either[RestError, Predicate] =
    def compare(c: Comparison) =
      if v.isEmpty then Left(RestError.invalidFilter(name, "empty value"))
      else Right(Predicate.Compare(c, v))
    op match
      case FilterOp.Is =>
        v match
          case "null"  => Right(Predicate.Is(IsTarget.Null))
          case "true"  => Right(Predicate.Is(IsTarget.True))
          case "false" => Right(Predicate.Is(IsTarget.False))
          case _       => Left(RestError.invalidFilter(name, "is expects null, true or false"))
      case FilterOp.In    => parseItems(name, v).map(Predicate.Items(_))
      case FilterOp.Like  => likePattern(name, v, caseInsensitive = false)
      case FilterOp.ILike => likePattern(name, v, caseInsensitive = true)
      case FilterOp.Eq    => compare(Comparison.Eq)
      case FilterOp.Neq   => compare(Comparison.Neq)
      case FilterOp.Gt    => compare(Comparison.Gt)
      case FilterOp.Gte   => compare(Comparison.Gte)
      case FilterOp.Lt    => compare(Comparison.Lt)
      case FilterOp.Lte   => compare(Comparison.Lte)

  /** `*` -> `%`; a literal `%`, `_` or `\` is escaped with `\` (§6.3). */
  private def likePattern(
      name: String,
      v: String,
      caseInsensitive: Boolean
  ): Either[RestError, Predicate] =
    val wildcards = v.count(_ == '*')
    if v.isEmpty then Left(RestError.invalidFilter(name, "empty value"))
    else if wildcards > MaxWildcards then
      Left(RestError.invalidFilter(name, s"at most $MaxWildcards wildcards"))
    else
      val sb      = new StringBuilder(v.length + 8)
      var escaped = false
      v.foreach {
        case '*'                    => sb.append('%')
        case c @ ('%' | '_' | '\\') => sb.append('\\').append(c); escaped = true
        case c                      => sb.append(c)
      }
      Right(Predicate.Pattern(caseInsensitive, sb.toString, escaped))

  /** `"(" item ("," item)* ")"`; `item := bare | '"' quoted '"'`, quoted with `\"` and `\\`
    * escapes. A bare item is non-empty and holds none of `, ( ) "`.
    */
  private def parseItems(name: String, v: String): Either[RestError, Vector[String]] =
    val bad = Left(RestError.invalidFilter(name, "in expects (item,...)"))
    if v.length < 2 || v.head != '(' || v.last != ')' then bad
    else
      val s     = v.substring(1, v.length - 1)
      val items = Vector.newBuilder[String]
      var n     = 0
      var i     = 0
      var ok    = true
      var more  = true
      while ok && more do
        if i < s.length && s.charAt(i) == '"' then
          val sb     = new StringBuilder
          var j      = i + 1
          var closed = false
          while ok && !closed && j < s.length do
            s.charAt(j) match
              case '"'  => closed = true; j += 1
              case '\\' =>
                if j + 1 < s.length && (s.charAt(j + 1) == '"' || s.charAt(j + 1) == '\\') then
                  sb.append(s.charAt(j + 1)); j += 2
                else ok = false
              case c => sb.append(c); j += 1
          if !closed then ok = false
          items += sb.toString
          i = j
        else
          var j = i
          while j < s.length && ",()\"".indexOf(s.charAt(j)) < 0 do j += 1
          if j == i then ok = false
          items += s.substring(i, j)
          i = j
        n += 1
        if ok then
          if i == s.length then more = false
          else if s.charAt(i) == ',' then i += 1
          else ok = false
      if !ok then bad
      else if n > MaxInItems then Left(RestError.invalidFilter(name, s"at most $MaxInItems items"))
      else Right(items.result())

  private def firstError(errs: List[RestError]): Either[RestError, Unit] =
    errs.headOption.toLeft(())

  private[rest] def traverse[A, B](
      xs: Vector[A]
  )(f: A => Either[RestError, B]): Either[RestError, Vector[B]] =
    xs.foldLeft[Either[RestError, Vector[B]]](Right(Vector.empty)) { (acc, a) =>
      acc.flatMap(out => f(a).map(out :+ _))
    }

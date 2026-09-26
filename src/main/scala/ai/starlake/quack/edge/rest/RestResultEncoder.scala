package ai.starlake.quack.edge.rest

import io.circe.{Json, JsonNumber}
import org.apache.arrow.vector.*
import org.apache.arrow.vector.complex.*
import org.apache.arrow.vector.dictionary.DictionaryProvider
import org.apache.arrow.vector.ipc.ArrowReader
import org.apache.arrow.vector.types.TimeUnit
import org.apache.arrow.vector.types.pojo.ArrowType

import java.nio.charset.StandardCharsets.UTF_8
import java.time.format.DateTimeFormatter
import java.time.{Instant, LocalDate, LocalDateTime, LocalTime, ZoneOffset}
import java.util.Base64
import scala.jdk.CollectionConverters.*

/** Turns the data statement's `ArrowReader` into the slice-1 bodies of design §6.4: a JSON array of
  * objects, or RFC 4180 CSV.
  *
  * This does not reuse `ArrowRowsDecoder`: its `toString` fallback loses information on nested
  * types, and the JSON contract here is exact. Every value goes through ONE mapping to a circe
  * [[Json]], and CSV is derived from it, so the two formats cannot disagree on a type:
  *
  *   - integers up to 64 bits (signed or unsigned) are JSON numbers;
  *   - DECIMAL is an exact plain number built from the vector's `BigDecimal`, never a double;
  *   - DECIMAL(38,0) is a STRING: DuckDB's default Arrow export turns HUGEINT and UHUGEINT into
  *     exactly that type, so it is the only way to honour "HUGEINT is a string" (§6.4), at the cost
  *     of also stringifying a genuine DECIMAL(38,0);
  *   - NaN and the infinities are the strings `NaN`, `Infinity`, `-Infinity`;
  *   - dates, times and timestamps are ISO-8601, zoned timestamps in UTC with `Z`;
  *   - BLOB is standard base64, intervals are ISO-8601 durations;
  *   - lists, structs and unions nest; a MAP is an array of `{"key", "value"}` objects, which keeps
  *     non-string keys and key order;
  *   - NULL is `null` at every depth.
  *
  * The reader is consumed but not closed: the handler's single finalizer owns `Routed.close()`
  * (§6.4 "Encoding").
  */
object RestResultEncoder:

  /** `rows` rows were encoded; `more` says a further row existed past the cap. */
  final case class Encoded(body: String, rows: Int, more: Boolean)

  def encode(format: RestFormat, reader: ArrowReader, maxRows: Int): Encoded = format match
    case RestFormat.Json => json(reader, maxRows)
    case RestFormat.Csv  => csv(reader, maxRows)

  def json(reader: ArrowReader, maxRows: Int): Encoded =
    val sb           = new StringBuilder("[")
    val names        = fieldNames(reader)
    val (rows, more) = foreachRow(reader, maxRows) { (cells, n) =>
      if n > 0 then sb.append(',')
      sb.append(Json.fromFields(names.zip(cells)).noSpaces)
    }
    Encoded(sb.append(']').toString, rows, more)

  def csv(reader: ArrowReader, maxRows: Int): Encoded =
    val sb = new StringBuilder
    fieldNames(reader).map(csvField).addString(sb, ",").append("\r\n")
    val (rows, more) = foreachRow(reader, maxRows) { (cells, _) =>
      cells.map(csvCell).addString(sb, ",").append("\r\n")
    }
    Encoded(sb.toString, rows, more)

  private def fieldNames(reader: ArrowReader): Vector[String] =
    reader.getVectorSchemaRoot.getSchema.getFields.asScala.toVector.map(_.getName)

  /** Feeds at most `maxRows` rows to `f`, then peeks (loading further batches if needed) for one
    * more row. Returns the rows fed and whether another existed.
    */
  private def foreachRow(reader: ArrowReader, maxRows: Int)(
      f: (Vector[Json], Int) => Unit
  ): (Int, Boolean) =
    val root = reader.getVectorSchemaRoot
    var n    = 0
    var more = false
    var done = false
    while !done && reader.loadNextBatch() do
      val cols  = root.getFieldVectors.asScala.toVector.map(v => encoderFor(v, reader))
      val count = root.getRowCount
      var i     = 0
      while i < count && n < maxRows do
        f(cols.map(_(i)), n)
        n += 1
        i += 1
      if n >= maxRows then
        more = i < count || hasAnotherRow(reader)
        done = true
    (n, more)

  private def hasAnotherRow(reader: ArrowReader): Boolean =
    var found = false
    while !found && reader.loadNextBatch() do found = reader.getVectorSchemaRoot.getRowCount > 0
    found

  // ---- CSV -----------------------------------------------------------------------------------

  /** RFC 4180: quote a field holding `,`, `"`, CR or LF, doubling inner quotes. */
  private def csvField(s: String): String =
    if s.exists(c => c == ',' || c == '"' || c == '\n' || c == '\r') then
      "\"" + s.replace("\"", "\"\"") + "\""
    else s

  /** NULL is an empty field and an empty string a quoted empty field, so the two stay distinct;
    * scalars are their JSON text unquoted, nested values their compact JSON.
    */
  private def csvCell(j: Json): String =
    if j.isNull then ""
    else
      j.asString match
        case Some("") => "\"\""
        case Some(s)  => csvField(s)
        case None     => csvField(j.noSpaces)

  // ---- one Arrow value -> Json ---------------------------------------------------------------

  private val IsoLocalDateTime = DateTimeFormatter.ISO_LOCAL_DATE_TIME
  private val IsoLocalTime     = DateTimeFormatter.ISO_LOCAL_TIME

  private def number(plain: String): Json =
    Json.fromJsonNumber(JsonNumber.fromDecimalStringUnsafe(plain))

  private def floating(d: Double, text: => String): Json =
    if d.isNaN then Json.fromString("NaN")
    else if d.isPosInfinity then Json.fromString("Infinity")
    else if d.isNegInfinity then Json.fromString("-Infinity")
    else number(text)

  private def decimal(bd: java.math.BigDecimal, precision: Int, scale: Int): Json =
    if precision == 38 && scale == 0 then Json.fromString(bd.toPlainString)
    else number(bd.toPlainString)

  private def instantOf(raw: Long, unit: TimeUnit): Instant = unit match
    case TimeUnit.SECOND      => Instant.ofEpochSecond(raw)
    case TimeUnit.MILLISECOND => Instant.ofEpochMilli(raw)
    case TimeUnit.MICROSECOND =>
      Instant.ofEpochSecond(Math.floorDiv(raw, 1000000L), Math.floorMod(raw, 1000000L) * 1000L)
    case TimeUnit.NANOSECOND =>
      Instant.ofEpochSecond(Math.floorDiv(raw, 1000000000L), Math.floorMod(raw, 1000000000L))

  private def timestamp(raw: Long, t: ArrowType.Timestamp): Json =
    val local = LocalDateTime.ofInstant(instantOf(raw, t.getUnit), ZoneOffset.UTC)
    val text  = local.format(IsoLocalDateTime)
    Json.fromString(if t.getTimezone != null then text + "Z" else text)

  private def time(nanoOfDay: Long): Json =
    Json.fromString(LocalTime.ofNanoOfDay(nanoOfDay).format(IsoLocalTime))

  private def base64(bytes: Array[Byte]): Json =
    Json.fromString(Base64.getEncoder.encodeToString(bytes))

  /** Per-batch compiled cell reader for one vector; NULL is checked before any typed access. */
  private def encoderFor(v: ValueVector, dicts: DictionaryProvider): Int => Json =
    val enc: Int => Json = v.getField.getDictionary match
      case null => valueEncoder(v, dicts)
      case de   =>
        // DuckDB dictionary-encodes ENUMs: the vector holds indices into the dictionary vector.
        val values = dicts.lookup(de.getId).getVector
        val inner  = encoderFor(values, dicts)
        val index  = v.asInstanceOf[BaseIntVector]
        i => inner(index.getValueAsLong(i).toInt)
    i => if v.isNull(i) then Json.Null else enc(i)

  private def valueEncoder(v: ValueVector, dicts: DictionaryProvider): Int => Json = v match
    case x: BitVector      => i => Json.fromBoolean(x.get(i) != 0)
    case x: TinyIntVector  => i => Json.fromLong(x.get(i).toLong)
    case x: SmallIntVector => i => Json.fromLong(x.get(i).toLong)
    case x: IntVector      => i => Json.fromLong(x.get(i).toLong)
    case x: BigIntVector   => i => Json.fromLong(x.get(i))
    case x: UInt1Vector    => i => Json.fromLong(x.get(i) & 0xffL)
    case x: UInt2Vector    => i => Json.fromLong(x.get(i).toLong)
    case x: UInt4Vector    => i => Json.fromLong(x.get(i) & 0xffffffffL)
    case x: UInt8Vector    => i => number(java.lang.Long.toUnsignedString(x.get(i)))
    case x: Float4Vector   =>
      i =>
        val f = x.get(i)
        floating(f.toDouble, java.lang.Float.toString(f))
    case x: Float8Vector =>
      i =>
        val d = x.get(i)
        floating(d, java.lang.Double.toString(d))
    case x: DecimalVector         => i => decimal(x.getObject(i), x.getPrecision, x.getScale)
    case x: Decimal256Vector      => i => decimal(x.getObject(i), x.getPrecision, x.getScale)
    case x: VarCharVector         => i => Json.fromString(new String(x.get(i), UTF_8))
    case x: LargeVarCharVector    => i => Json.fromString(new String(x.get(i), UTF_8))
    case x: ViewVarCharVector     => i => Json.fromString(new String(x.get(i), UTF_8))
    case x: VarBinaryVector       => i => base64(x.get(i))
    case x: LargeVarBinaryVector  => i => base64(x.get(i))
    case x: ViewVarBinaryVector   => i => base64(x.get(i))
    case x: FixedSizeBinaryVector => i => base64(x.get(i))
    case x: DateDayVector   => i => Json.fromString(LocalDate.ofEpochDay(x.get(i).toLong).toString)
    case x: DateMilliVector =>
      i =>
        val dt = LocalDateTime.ofInstant(Instant.ofEpochMilli(x.get(i)), ZoneOffset.UTC)
        Json.fromString(dt.format(IsoLocalDateTime))
    case x: TimeSecVector   => i => time(x.get(i) * 1000000000L)
    case x: TimeMilliVector => i => time(x.get(i) * 1000000L)
    case x: TimeMicroVector => i => time(x.get(i) * 1000L)
    case x: TimeNanoVector  => i => time(x.get(i))
    case x: TimeStampVector =>
      val t = x.getField.getType.asInstanceOf[ArrowType.Timestamp]
      i => timestamp(x.get(i), t)
    case x: IntervalMonthDayNanoVector =>
      i => Json.fromString(x.getObject(i).toISO8601IntervalString)
    case x: IntervalDayVector  => i => Json.fromString(x.getObject(i).toString)
    case x: IntervalYearVector => i => Json.fromString(x.getObject(i).toString)
    case x: DurationVector     => i => Json.fromString(x.getObject(i).toString)
    case x: NullVector         => _ => Json.Null
    // MapVector extends ListVector: it must be matched first.
    case x: MapVector =>
      val entries = x.getDataVector.asInstanceOf[StructVector]
      val key     = encoderFor(entries.getChildByOrdinal(0), dicts)
      val value   = encoderFor(entries.getChildByOrdinal(1), dicts)
      i =>
        Json.fromValues(
          (x.getElementStartIndex(i) until x.getElementEndIndex(i)).map(j =>
            Json.obj("key" -> key(j), "value" -> value(j))
          )
        )
    case x: ListVector =>
      val child = encoderFor(x.getDataVector, dicts)
      i => Json.fromValues((x.getElementStartIndex(i) until x.getElementEndIndex(i)).map(child))
    case x: LargeListVector =>
      val child = encoderFor(x.getDataVector, dicts)
      i =>
        Json.fromValues(
          (x.getElementStartIndex(i) until x.getElementEndIndex(i)).map(j => child(j.toInt))
        )
    case x: FixedSizeListVector =>
      val child = encoderFor(x.getDataVector, dicts)
      val size  = x.getListSize
      i => Json.fromValues((i * size until (i + 1) * size).map(child))
    case x: StructVector =>
      val children = x.getChildrenFromFields.asScala.toVector.map(c =>
        c.getField.getName -> encoderFor(c, dicts)
      )
      i => Json.fromFields(children.map((name, enc) => name -> enc(i)))
    case x: UnionVector =>
      // Sparse union: the member vector for this row's type id, at the same row index.
      i => encoderFor(x.getVector(i), dicts)(i)
    case x: DenseUnionVector =>
      i => encoderFor(x.getVectorByType(x.getTypeId(i)), dicts)(x.getOffset(i))
    // Last resort for a vector type DuckDB does not export today; never reached by the pinned
    // DuckDB's types (RestResultEncoderSpec covers every family it produces).
    case other => i => Json.fromString(String.valueOf(other.getObject(i)))

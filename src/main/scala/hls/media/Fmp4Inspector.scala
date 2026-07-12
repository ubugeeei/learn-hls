package hls.media

import java.nio.ByteBuffer
import java.nio.charset.StandardCharsets

/** A top-level ISO Base Media File Format box. */
final case class IsoBox(boxType: String, offset: Long, size: Long, headerSize: Int)

/** Structural fMP4 parsing or profile failure. */
enum Fmp4Error:
  case TruncatedHeader(offset: Long)
  case InvalidSize(offset: Long, size: Long)
  case BoxExceedsInput(offset: Long, size: Long, inputLength: Long)
  case MissingRequiredBox(boxType: String)
  case UnexpectedBoxOrder(expectedBefore: String, expectedAfter: String)

/**
 * Bounded, non-recursive ISO BMFF top-level box inspector.
 *
 * HLS fMP4 initialization sections require `ftyp` and `moov`; Media Segments require a `moof`
 * followed by `mdat`. See [[https://www.rfc-editor.org/rfc/rfc8216#section-3.3 RFC 8216 §3.3]].
 * This inspector validates those top-level facts, not nested track/timestamp rules.
 */
object Fmp4Inspector:
  /** Parses all top-level boxes, including 64-bit extended sizes. */
  def boxes(bytes: Array[Byte]): Either[Vector[Fmp4Error], Vector[IsoBox]] =
    val result                   = Vector.newBuilder[IsoBox]
    var offset                   = 0L
    var error: Option[Fmp4Error] = None
    while offset < bytes.length && error.isEmpty do
      if bytes.length - offset < 8 then error = Some(Fmp4Error.TruncatedHeader(offset))
      else
        val index    = offset.toInt
        val size32   = Integer.toUnsignedLong(ByteBuffer.wrap(bytes, index, 4).getInt)
        val boxType  = String(bytes, index + 4, 4, StandardCharsets.US_ASCII)
        val extended = size32 == 1
        val header   = if extended then 16 else 8
        if extended && bytes.length - offset < 16 then
          error = Some(Fmp4Error.TruncatedHeader(offset))
        else
          val declared =
            if size32 == 0 then bytes.length - offset
            else if extended then ByteBuffer.wrap(bytes, index + 8, 8).getLong
            else size32
          if declared < header then error = Some(Fmp4Error.InvalidSize(offset, declared))
          else if declared > bytes.length - offset then
            error = Some(Fmp4Error.BoxExceedsInput(offset, declared, bytes.length))
          else
            result += IsoBox(boxType, offset, declared, header)
            offset += declared
    error.fold[Either[Vector[Fmp4Error], Vector[IsoBox]]](Right(result.result()))(value =>
      Left(Vector(value))
    )

  /** Validates an fMP4 Media Initialization Section. */
  def initialization(bytes: Array[Byte]): Either[Vector[Fmp4Error], Vector[IsoBox]] =
    boxes(bytes).flatMap(parsed => requireBoxes(parsed, Vector("ftyp", "moov")))

  /** Validates the top-level shape of one fMP4 Media Segment. */
  def mediaSegment(bytes: Array[Byte]): Either[Vector[Fmp4Error], Vector[IsoBox]] =
    boxes(bytes).flatMap: parsed =>
      requireBoxes(parsed, Vector("moof", "mdat")).flatMap: complete =>
        val moof = complete.indexWhere(_.boxType == "moof")
        val mdat = complete.indexWhere(_.boxType == "mdat")
        Either.cond(moof < mdat, complete, Vector(Fmp4Error.UnexpectedBoxOrder("moof", "mdat")))

  private def requireBoxes(
      parsed: Vector[IsoBox],
      required: Vector[String]
  ): Either[Vector[Fmp4Error], Vector[IsoBox]] =
    val errors = required
      .filterNot(name => parsed.exists(_.boxType == name))
      .map(Fmp4Error.MissingRequiredBox(_))
    Either.cond(errors.isEmpty, parsed, errors)

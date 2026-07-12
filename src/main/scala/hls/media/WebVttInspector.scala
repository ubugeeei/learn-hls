package hls.media

import scala.util.matching.Regex

/** HLS WebVTT structural validation failure. */
enum WebVttError:
  case MissingHeader
  case InvalidTimestampMap(line: Int)
  case InvalidCueTiming(line: Int)
  case NoCues

/** Facts extracted from one WebVTT segment. */
final case class WebVttReport(
    cueCount: Int,
    localTimestamp: Option[String],
    mpegTsTimestamp: Option[Long]
)

/**
 * Inspector for WebVTT Media Segments described by
 * [[https://www.rfc-editor.org/rfc/rfc8216#section-3.5 RFC 8216 §3.5]].
 *
 * It validates the required header, HLS timestamp-map syntax, and cue timing line shape. It does
 * not implement the complete WebVTT rendering model.
 */
object WebVttInspector:
  private val CueTiming: Regex =
    raw"(?:\d{2}:)?\d{2}:\d{2}\.\d{3}\s+-->\s+(?:\d{2}:)?\d{2}:\d{2}\.\d{3}(?:\s+.*)?".r

  /** Inspects one decoded UTF-8 WebVTT segment. */
  def inspect(source: String): Either[Vector[WebVttError], WebVttReport] =
    val lines = source
      .stripPrefix("\uFEFF")
      .replace("\r\n", "\n")
      .replace('\r', '\n')
      .split("\n", -1)
      .toVector
    if lines.headOption.forall(line => line != "WEBVTT" && !line.startsWith("WEBVTT ")) then
      Left(Vector(WebVttError.MissingHeader))
    else
      val errors                = Vector.newBuilder[WebVttError]
      var local: Option[String] = None
      var mpegTs: Option[Long]  = None
      var cueCount              = 0
      lines.zipWithIndex.foreach: (line, index) =>
        if line.startsWith("X-TIMESTAMP-MAP=") then
          parseTimestampMap(line.drop(16)) match
            case Some((localValue, mpegTsValue)) =>
              local = Some(localValue)
              mpegTs = Some(mpegTsValue)
            case None => errors += WebVttError.InvalidTimestampMap(index + 1)
        else if line.contains("-->") then
          if CueTiming.matches(line) then cueCount += 1
          else errors += WebVttError.InvalidCueTiming(index + 1)
      if cueCount == 0 then errors += WebVttError.NoCues
      val result = errors.result()
      if result.nonEmpty then Left(result) else Right(WebVttReport(cueCount, local, mpegTs))

  private def parseTimestampMap(value: String): Option[(String, Long)] =
    val attributes = value
      .split(',')
      .toVector
      .flatMap: entry =>
        entry.split(":", 2).toList match
          case name :: content :: Nil => Some(name -> content)
          case _                      => None
    val map = attributes.toMap
    for
      local  <- map.get("LOCAL").filter(value => CueTiming.matches(s"$value --> $value"))
      mpegTs <- map.get("MPEGTS").flatMap(_.toLongOption).filter(_ >= 0)
    yield local -> mpegTs

package hls.parser

import hls.model.*
import hls.model.ValueTypes.*
import hls.validation.PlaylistValidator
import java.time.OffsetDateTime
import scala.util.Try

/**
 * Parses UTF-8 HLS playlists according to
 * [[https://www.rfc-editor.org/rfc/rfc8216#section-4 RFC 8216 section 4]]. Unknown `EXT-X-` tags
 * are ignored for forward compatibility; malformed or inconsistent tags implemented by this library
 * produce a [[ParseError]].
 */
object PlaylistParser:
  /** A parsed playlist plus variables that it can explicitly export to child Media Playlists. */
  final case class Result(playlist: Playlist, definedVariables: Map[String, String])

  /**
   * Parses, refines, and semantically validates one complete playlist.
   *
   * The method is pure and performs no URI resolution or network access. Unknown extension tags are
   * ignored; malformed implemented tags fail with a one-based source line.
   */
  def parse(
      source: String,
      context: VariableContext = VariableContext()
  ): Either[ParseError, Playlist] = parseWithVariables(source, context).map(_.playlist)

  /** Parses a playlist while retaining its non-persistent variable environment for `IMPORT`. */
  def parseWithVariables(
      source: String,
      context: VariableContext = VariableContext()
  ): Either[ParseError, Result] =
    val normalized = source.stripPrefix("\uFEFF").replace("\r\n", "\n").replace('\r', '\n')
    val rawLines   = normalized.split("\n", -1).toVector
    VariableSubstitution
      .expand(rawLines, context)
      .flatMap: expansion =>
        val lines = expansion.lines
        lines.indexWhere(_.nonEmpty) match
          case -1                                 => Left(ParseError(1, "empty playlist"))
          case first if lines(first) != "#EXTM3U" =>
            Left(ParseError(first + 1, "playlist must start with #EXTM3U"))
          case _
              if lines.exists(_.startsWith("#EXT-X-STREAM-INF:")) || lines.exists(
                _.startsWith("#EXT-X-MEDIA:")
              ) || lines.exists(_.startsWith("#EXT-X-I-FRAME-STREAM-INF:")) || lines.exists(
                _.startsWith("#EXT-X-SESSION-")
              ) =>
            if expansion.importedNames.nonEmpty then
              Left(ParseError(0, "EXT-X-DEFINE IMPORT is forbidden in Multivariant Playlists"))
            else MultivariantPlaylistParser.parse(lines).map(Result(_, expansion.definedVariables))
          case _ => parseMedia(lines).map(Result(_, expansion.definedVariables))

  private final case class Pending(
      duration: Option[(Duration, Option[String])] = None,
      byteRange: Option[ByteRange] = None,
      discontinuity: Boolean = false,
      programDateTime: Option[OffsetDateTime] = None,
      gap: Boolean = false,
      dateRanges: Vector[DateRange] = Vector.empty
  )

  private def parseMedia(lines: Vector[String]): Either[ParseError, Playlist] =
    var version: Option[Int]                     = None
    var target: Option[Long]                     = None
    var sequence                                 = MediaSequence.unsafe(0)
    var discontinuitySequence                    = 0L
    var playlistType: Option[PlaylistType]       = None
    var independent                              = false
    var ended                                    = false
    var start: Option[StartOffset]               = None
    var iFramesOnly                              = false
    var partInformation: Option[PartInformation] = None
    var serverControl: Option[ServerControl]     = None
    var skip: Option[PlaylistSkip]               = None
    var encryption: Encryption                   = Encryption.None
    var initMap: Option[InitializationMap]       = None
    var pending                                  = Pending()
    var previousRangeEnd: Option[Long]           = None
    var previousPartRangeEnd: Option[Long]       = None
    var completedSegments                        = 0L
    var currentPartIndex                         = 0
    val segments                                 = Vector.newBuilder[MediaSegment]
    val partialSegments                          = Vector.newBuilder[PartialSegment]
    val preloadHints                             = Vector.newBuilder[PreloadHint]
    val renditionReports                         = Vector.newBuilder[RenditionReport]

    def fail[A](index: Int, message: String): Either[ParseError, A] = Left(
      ParseError(index + 1, message)
    )
    def value(line: String): String = line.drop(line.indexOf(':') + 1)
    def unique[A](
        index: Int,
        current: Option[A],
        next: => Either[String, A],
        name: String
    ): Either[ParseError, Option[A]] =
      if current.nonEmpty then fail(index, s"duplicate $name")
      else next.left.map(ParseError(index + 1, _)).map(Some(_))

    lines.zipWithIndex
      .drop(1)
      .foldLeft[Either[ParseError, Unit]](Right(())):
        case (result, (line, index)) =>
          result.flatMap: _ =>
            if line.isEmpty || (line.startsWith("#") && !line.startsWith("#EXT")) then Right(())
            else if line.startsWith("#EXT-X-STREAM-INF:") || line.startsWith("#EXT-X-MEDIA:") then
              fail(index, "media and multivariant tags cannot be mixed")
            else if line.startsWith("#EXT-X-VERSION:") then
              unique(
                index,
                version,
                value(line).toIntOption.toRight("invalid version"),
                "EXT-X-VERSION"
              ).map(version = _)
            else if line.startsWith("#EXT-X-TARGETDURATION:") then
              unique(
                index,
                target,
                value(line).toLongOption.filter(_ > 0).toRight("invalid target duration"),
                "EXT-X-TARGETDURATION"
              ).map(target = _)
            else if line.startsWith("#EXT-X-MEDIA-SEQUENCE:") then
              MediaSequence.parse(value(line)).left.map(ParseError(index + 1, _)).map(sequence = _)
            else if line.startsWith("#EXT-X-DISCONTINUITY-SEQUENCE:") then
              value(line).toLongOption
                .filter(_ >= 0)
                .toRight(ParseError(index + 1, "invalid discontinuity sequence"))
                .map(discontinuitySequence = _)
            else if line == "#EXT-X-INDEPENDENT-SEGMENTS" then
              independent = true
              Right(())
            else if line == "#EXT-X-ENDLIST" then
              ended = true
              Right(())
            else if line == "#EXT-X-DISCONTINUITY" then
              pending = pending.copy(discontinuity = true)
              Right(())
            else if line == "#EXT-X-GAP" then
              pending = pending.copy(gap = true)
              Right(())
            else if line == "#EXT-X-I-FRAMES-ONLY" then
              iFramesOnly = true
              Right(())
            else if line.startsWith("#EXT-X-PART-INF:") then
              if partInformation.nonEmpty then fail(index, "duplicate EXT-X-PART-INF")
              else
                LowLatencyParser
                  .partInformation(value(line), index + 1)
                  .map(parsed => partInformation = Some(parsed))
            else if line.startsWith("#EXT-X-SERVER-CONTROL:") then
              if serverControl.nonEmpty then fail(index, "duplicate EXT-X-SERVER-CONTROL")
              else
                LowLatencyParser
                  .serverControl(value(line), index + 1)
                  .map(parsed => serverControl = Some(parsed))
            else if line.startsWith("#EXT-X-PART:") then
              val parent = sequence.value + skip.fold(0L)(_.skippedSegments) + completedSegments
              LowLatencyParser
                .partialSegment(
                  value(line),
                  index + 1,
                  parent,
                  currentPartIndex,
                  previousPartRangeEnd
                )
                .map: part =>
                  partialSegments += part
                  currentPartIndex += 1
                  previousPartRangeEnd = part.byteRange.map(range => range.offset + range.length)
            else if line.startsWith("#EXT-X-SKIP:") then
              if skip.nonEmpty then fail(index, "duplicate EXT-X-SKIP")
              else LowLatencyParser.skip(value(line), index + 1).map(parsed => skip = Some(parsed))
            else if line.startsWith("#EXT-X-PRELOAD-HINT:") then
              LowLatencyParser.preloadHint(value(line), index + 1).map(preloadHints += _)
            else if line.startsWith("#EXT-X-RENDITION-REPORT:") then
              LowLatencyParser.renditionReport(value(line), index + 1).map(renditionReports += _)
            else if line.startsWith("#EXT-X-PLAYLIST-TYPE:") then
              value(line) match
                case "EVENT" => playlistType = Some(PlaylistType.Event); Right(())
                case "VOD"   => playlistType = Some(PlaylistType.Vod); Right(())
                case other   => fail(index, s"invalid playlist type: $other")
            else if line.startsWith("#EXTINF:") then
              if pending.duration.nonEmpty then fail(index, "EXTINF without a following URI")
              else
                val parts = value(line).split(",", 2)
                Duration
                  .parse(parts(0))
                  .left
                  .map(ParseError(index + 1, _))
                  .map: duration =>
                    pending =
                      pending.copy(duration = Some(duration -> parts.lift(1).filter(_.nonEmpty)))
            else if line.startsWith("#EXT-X-BYTERANGE:") then
              val parts  = value(line).split('@')
              val parsed = for
                length <- parts.headOption
                  .flatMap(_.toLongOption)
                  .filter(_ > 0)
                  .toRight("invalid byte range length")
                offset <- parts
                  .lift(1)
                  .flatMap(_.toLongOption)
                  .orElse(previousRangeEnd)
                  .filter(_ >= 0)
                  .toRight("byte range needs an offset")
              yield ByteRange(length, offset)
              parsed.left
                .map(ParseError(index + 1, _))
                .map(range => pending = pending.copy(byteRange = Some(range)))
            else if line.startsWith("#EXT-X-PROGRAM-DATE-TIME:") then
              Try(OffsetDateTime.parse(value(line))).toEither.left
                .map(_ => ParseError(index + 1, "invalid program date-time"))
                .map: date =>
                  pending = pending.copy(programDateTime = Some(date))
            else if line.startsWith("#EXT-X-DATERANGE:") then
              StandardTagParser
                .dateRange(value(line), index + 1)
                .map(range => pending = pending.copy(dateRanges = pending.dateRanges :+ range))
            else if line.startsWith("#EXT-X-START:") then
              StandardTagParser.start(value(line), index + 1).map(offset => start = Some(offset))
            else if line.startsWith("#EXT-X-KEY:") then
              StandardTagParser.key(value(line), index + 1).map(encryption = _)
            else if line.startsWith("#EXT-X-MAP:") then
              StandardTagParser
                .initializationMap(value(line), index + 1)
                .map(map => initMap = Some(map))
            else if line.startsWith("#") then Right(())
            else
              pending.duration match
                case None                    => fail(index, "segment URI has no preceding EXTINF")
                case Some((duration, title)) =>
                  PlaylistUri
                    .parse(line)
                    .left
                    .map(ParseError(index + 1, _))
                    .map: uri =>
                      segments += MediaSegment(
                        uri,
                        duration,
                        title,
                        pending.byteRange,
                        pending.discontinuity,
                        encryption,
                        initMap,
                        pending.programDateTime,
                        pending.gap,
                        pending.dateRanges
                      )
                      previousRangeEnd = pending.byteRange.map(r => r.offset + r.length)
                      pending = Pending()
                      completedSegments += 1
                      currentPartIndex = 0
                      previousPartRangeEnd = None
      .flatMap: _ =>
        if pending.duration.nonEmpty then
          Left(ParseError(lines.length, "EXTINF has no following URI"))
        else
          target
            .toRight(ParseError(0, "media playlist requires EXT-X-TARGETDURATION"))
            .flatMap: targetDuration =>
              val playlist = MediaPlaylist(
                version,
                targetDuration,
                sequence,
                discontinuitySequence,
                playlistType,
                independent,
                segments.result(),
                ended,
                start,
                iFramesOnly,
                partInformation,
                serverControl,
                partialSegments.result(),
                skip,
                preloadHints.result(),
                renditionReports.result()
              )
              PlaylistValidator
                .validateMedia(playlist)
                .headOption
                .toLeft(Playlist.Media(playlist))
                .left
                .map(ParseError(0, _))

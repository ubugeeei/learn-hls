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
  /**
   * Parses, refines, and semantically validates one complete playlist.
   *
   * The method is pure and performs no URI resolution or network access. Unknown extension tags are
   * ignored; malformed implemented tags fail with a one-based source line.
   */
  def parse(source: String): Either[ParseError, Playlist] =
    val normalized = source.stripPrefix("\uFEFF").replace("\r\n", "\n").replace('\r', '\n')
    val lines      = normalized.split("\n", -1).toVector
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
        MultivariantPlaylistParser.parse(lines)
      case _ => parseMedia(lines)

  private final case class Pending(
      duration: Option[(Duration, Option[String])] = None,
      byteRange: Option[ByteRange] = None,
      discontinuity: Boolean = false,
      programDateTime: Option[OffsetDateTime] = None,
      gap: Boolean = false,
      dateRanges: Vector[DateRange] = Vector.empty
  )

  private def parseMedia(lines: Vector[String]): Either[ParseError, Playlist] =
    var version: Option[Int]               = None
    var target: Option[Long]               = None
    var sequence                           = MediaSequence.unsafe(0)
    var discontinuitySequence              = 0L
    var playlistType: Option[PlaylistType] = None
    var independent                        = false
    var ended                              = false
    var start: Option[StartOffset]         = None
    var iFramesOnly                        = false
    var encryption: Encryption             = Encryption.None
    var initMap: Option[InitializationMap] = None
    var pending                            = Pending()
    var previousRangeEnd: Option[Long]     = None
    val segments                           = Vector.newBuilder[MediaSegment]

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
              parseDateRange(value(line), index + 1)
                .map(range => pending = pending.copy(dateRanges = pending.dateRanges :+ range))
            else if line.startsWith("#EXT-X-START:") then
              parseStart(value(line), index + 1).map(offset => start = Some(offset))
            else if line.startsWith("#EXT-X-KEY:") then
              parseKey(value(line), index + 1).map(encryption = _)
            else if line.startsWith("#EXT-X-MAP:") then
              parseMap(value(line), index + 1).map(map => initMap = Some(map))
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
                iFramesOnly
              )
              PlaylistValidator
                .validateMedia(playlist)
                .headOption
                .toLeft(Playlist.Media(playlist))
                .left
                .map(ParseError(0, _))

  private[parser] def parseKey(input: String, line: Int): Either[ParseError, Encryption] =
    AttributeList
      .parse(input, line)
      .flatMap: a =>
        a.get("METHOD") match
          case Some("NONE")                              => Right(Encryption.None)
          case Some(method @ ("AES-128" | "SAMPLE-AES")) =>
            for
              rawUri <- a.get("URI").toRight(ParseError(line, s"$method requires URI"))
              uri    <- PlaylistUri.parse(rawUri).left.map(ParseError(line, _))
            yield
              if method == "AES-128" then Encryption.Aes128(uri, a.get("IV"))
              else
                Encryption.SampleAes(
                  uri,
                  a.get("KEYFORMAT"),
                  a.get("KEYFORMATVERSIONS"),
                  a.get("IV")
                )
          case Some(other) => Left(ParseError(line, s"unsupported encryption method: $other"))
          case None        => Left(ParseError(line, "EXT-X-KEY requires METHOD"))

  private def parseMap(input: String, line: Int): Either[ParseError, InitializationMap] =
    AttributeList
      .parse(input, line)
      .flatMap: a =>
        for
          rawUri <- a.get("URI").toRight(ParseError(line, "EXT-X-MAP requires URI"))
          uri    <- PlaylistUri.parse(rawUri).left.map(ParseError(line, _))
          range  <- a.get("BYTERANGE").traverse(parseExplicitRange(_, line))
        yield InitializationMap(uri, range)

  private def parseExplicitRange(value: String, line: Int): Either[ParseError, ByteRange] =
    value.split('@').toList match
      case length :: offset :: Nil =>
        for
          l <- length.toLongOption.filter(_ > 0).toRight(ParseError(line, "invalid byte range"))
          o <- offset.toLongOption.filter(_ >= 0).toRight(ParseError(line, "invalid byte range"))
        yield ByteRange(l, o)
      case _ => Left(ParseError(line, "map byte range requires an explicit offset"))

  private def parseStart(input: String, line: Int): Either[ParseError, StartOffset] =
    AttributeList
      .parse(input, line)
      .flatMap: attributes =>
        for
          raw <- attributes
            .get("TIME-OFFSET")
            .toRight(ParseError(line, "EXT-X-START requires TIME-OFFSET"))
          seconds <- Try(BigDecimal(raw)).toEither.left
            .map(_ => ParseError(line, "invalid start offset"))
        yield StartOffset(seconds, attributes.get("PRECISE").contains("YES"))

  private def parseDateRange(input: String, line: Int): Either[ParseError, DateRange] =
    AttributeList
      .parse(input, line)
      .flatMap: a =>
        def date(name: String): Either[ParseError, Option[OffsetDateTime]] =
          a.get(name)
            .traverse(value =>
              Try(OffsetDateTime.parse(value)).toEither.left
                .map(_ => ParseError(line, s"invalid $name"))
            )
        def duration(name: String): Either[ParseError, Option[Duration]] =
          a.get(name).traverse(value => Duration.parse(value).left.map(ParseError(line, _)))
        for
          id <- a
            .get("ID")
            .filter(_.nonEmpty)
            .toRight(ParseError(line, "EXT-X-DATERANGE requires ID"))
          rawStart <- a
            .get("START-DATE")
            .toRight(ParseError(line, "EXT-X-DATERANGE requires START-DATE"))
          startDate <- Try(OffsetDateTime.parse(rawStart)).toEither.left
            .map(_ => ParseError(line, "invalid START-DATE"))
          endDate        <- date("END-DATE")
          actualDuration <- duration("DURATION")
          planned        <- duration("PLANNED-DURATION")
        yield DateRange(
          id,
          startDate,
          a.get("CLASS"),
          endDate,
          actualDuration,
          planned,
          a.get("END-ON-NEXT").contains("YES"),
          a.get("SCTE35-CMD"),
          a.get("SCTE35-OUT"),
          a.get("SCTE35-IN"),
          a.filter((name, _) => name.startsWith("X-"))
        )

  extension [A](option: Option[A])
    private def traverse[B](function: A => Either[ParseError, B]): Either[ParseError, Option[B]] =
      option match
        case Some(value) => function(value).map(Some(_))
        case None        => Right(None)

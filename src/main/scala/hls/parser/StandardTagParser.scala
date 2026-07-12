package hls.parser

import hls.model.*
import hls.model.ValueTypes.*
import java.time.OffsetDateTime
import scala.util.Try

/** Shared parsers for standard tags used by RFC 8216 and HLS 2 Playlists. */
private[parser] object StandardTagParser:
  def key(input: String, line: Int): Either[ParseError, Encryption] =
    AttributeList
      .parse(input, line)
      .flatMap: attributes =>
        attributes.get("METHOD") match
          case Some("NONE")                              => Right(Encryption.None)
          case Some(method @ ("AES-128" | "SAMPLE-AES")) =>
            for
              rawUri <- attributes.get("URI").toRight(ParseError(line, s"$method requires URI"))
              uri    <- PlaylistUri.parse(rawUri).left.map(ParseError(line, _))
            yield
              if method == "AES-128" then Encryption.Aes128(uri, attributes.get("IV"))
              else
                Encryption.SampleAes(
                  uri,
                  attributes.get("KEYFORMAT"),
                  attributes.get("KEYFORMATVERSIONS"),
                  attributes.get("IV")
                )
          case Some(other) => Left(ParseError(line, s"unsupported encryption method: $other"))
          case None        => Left(ParseError(line, "EXT-X-KEY requires METHOD"))

  def initializationMap(input: String, line: Int): Either[ParseError, InitializationMap] =
    AttributeList
      .parse(input, line)
      .flatMap: attributes =>
        for
          rawUri <- attributes.get("URI").toRight(ParseError(line, "EXT-X-MAP requires URI"))
          uri    <- PlaylistUri.parse(rawUri).left.map(ParseError(line, _))
          range  <- attributes.get("BYTERANGE").traverse(explicitRange(_, line))
        yield InitializationMap(uri, range)

  def start(input: String, line: Int): Either[ParseError, StartOffset] =
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

  def dateRange(input: String, line: Int): Either[ParseError, DateRange] =
    AttributeList
      .parse(input, line)
      .flatMap: attributes =>
        def date(name: String): Either[ParseError, Option[OffsetDateTime]] =
          attributes
            .get(name)
            .traverse(value =>
              Try(OffsetDateTime.parse(value)).toEither.left
                .map(_ => ParseError(line, s"invalid $name"))
            )
        def duration(name: String): Either[ParseError, Option[Duration]] =
          attributes
            .get(name)
            .traverse(value => Duration.parse(value).left.map(ParseError(line, _)))
        for
          id <- attributes
            .get("ID")
            .filter(_.nonEmpty)
            .toRight(ParseError(line, "EXT-X-DATERANGE requires ID"))
          rawStart <- attributes
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
          attributes.get("CLASS"),
          endDate,
          actualDuration,
          planned,
          attributes.get("END-ON-NEXT").contains("YES"),
          attributes.get("SCTE35-CMD"),
          attributes.get("SCTE35-OUT"),
          attributes.get("SCTE35-IN"),
          attributes.filter((name, _) => name.startsWith("X-"))
        )

  private def explicitRange(value: String, line: Int): Either[ParseError, ByteRange] =
    value.split('@').toList match
      case length :: offset :: Nil =>
        for
          parsedLength <- length.toLongOption
            .filter(_ > 0)
            .toRight(ParseError(line, "invalid byte range"))
          parsedOffset <- offset.toLongOption
            .filter(_ >= 0)
            .toRight(ParseError(line, "invalid byte range"))
        yield ByteRange(parsedLength, parsedOffset)
      case _ => Left(ParseError(line, "map byte range requires an explicit offset"))

  extension [A](option: Option[A])
    private def traverse[B](function: A => Either[ParseError, B]): Either[ParseError, Option[B]] =
      option match
        case Some(value) => function(value).map(Some(_))
        case None        => Right(None)

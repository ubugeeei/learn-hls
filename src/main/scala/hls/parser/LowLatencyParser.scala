package hls.parser

import hls.model.*
import hls.model.ValueTypes.*

/** Attribute parsers for draft-pantos-hls-rfc8216bis-22 low-latency tags. */
private[parser] object LowLatencyParser:
  def partInformation(input: String, line: Int): Either[ParseError, PartInformation] =
    AttributeList
      .parse(input, line)
      .flatMap: attributes =>
        requiredDuration(attributes, "PART-TARGET", line).map(PartInformation(_))

  def serverControl(input: String, line: Int): Either[ParseError, ServerControl] =
    AttributeList
      .parse(input, line)
      .flatMap: attributes =>
        for
          canSkip      <- optionalDuration(attributes, "CAN-SKIP-UNTIL", line)
          holdBack     <- optionalDuration(attributes, "HOLD-BACK", line)
          partHoldBack <- optionalDuration(attributes, "PART-HOLD-BACK", line)
        yield ServerControl(
          canSkipUntil = canSkip,
          canSkipDateRanges = yes(attributes, "CAN-SKIP-DATERANGES"),
          holdBack = holdBack,
          partHoldBack = partHoldBack,
          canBlockReload = yes(attributes, "CAN-BLOCK-RELOAD")
        )

  def partialSegment(
      input: String,
      line: Int,
      parentSequence: Long,
      partIndex: Int,
      implicitOffset: Option[Long]
  ): Either[ParseError, PartialSegment] =
    AttributeList
      .parse(input, line)
      .flatMap: attributes =>
        for
          rawUri   <- required(attributes, "URI", line)
          uri      <- PlaylistUri.parse(rawUri).left.map(ParseError(line, _))
          duration <- requiredDuration(attributes, "DURATION", line)
          range    <- attributes.get("BYTERANGE").traverse(parseByteRange(_, implicitOffset, line))
        yield PartialSegment(
          parentMediaSequence = parentSequence,
          partIndex = partIndex,
          uri = uri,
          duration = duration,
          independent = yes(attributes, "INDEPENDENT"),
          byteRange = range,
          gap = yes(attributes, "GAP")
        )

  def skip(input: String, line: Int): Either[ParseError, PlaylistSkip] =
    AttributeList
      .parse(input, line)
      .flatMap: attributes =>
        for
          raw   <- required(attributes, "SKIPPED-SEGMENTS", line)
          count <- raw.toLongOption
            .filter(_ >= 0)
            .toRight(ParseError(line, "invalid SKIPPED-SEGMENTS"))
        yield PlaylistSkip(
          skippedSegments = count,
          recentlyRemovedDateRangeIds = attributes
            .get("RECENTLY-REMOVED-DATERANGES")
            .toVector
            .flatMap(_.split('\t'))
            .filter(_.nonEmpty)
        )

  def preloadHint(input: String, line: Int): Either[ParseError, PreloadHint] =
    AttributeList
      .parse(input, line)
      .flatMap: attributes =>
        for
          rawType  <- required(attributes, "TYPE", line)
          hintType <- rawType match
            case "PART" => Right(PreloadHintType.Part)
            case "MAP"  => Right(PreloadHintType.Map)
            case other  => Left(ParseError(line, s"invalid preload hint TYPE: $other"))
          rawUri <- required(attributes, "URI", line)
          uri    <- PlaylistUri.parse(rawUri).left.map(ParseError(line, _))
          start  <- optionalLong(attributes, "BYTERANGE-START", line, minimum = 0)
          length <- optionalLong(attributes, "BYTERANGE-LENGTH", line, minimum = 1)
        yield PreloadHint(hintType, uri, start, length)

  def renditionReport(input: String, line: Int): Either[ParseError, RenditionReport] =
    AttributeList
      .parse(input, line)
      .flatMap: attributes =>
        for
          rawUri <- required(attributes, "URI", line)
          uri    <- PlaylistUri.parse(rawUri).left.map(ParseError(line, _))
          msn    <- optionalLong(attributes, "LAST-MSN", line, minimum = 0)
          part   <- optionalLong(attributes, "LAST-PART", line, minimum = 0)
        yield RenditionReport(uri, msn, part.map(_.toInt))

  private def parseByteRange(
      value: String,
      implicitOffset: Option[Long],
      line: Int
  ): Either[ParseError, ByteRange] =
    value.split('@').toList match
      case length :: Nil =>
        for
          parsedLength <- length.toLongOption
            .filter(_ > 0)
            .toRight(ParseError(line, "invalid part byte-range length"))
          offset <- implicitOffset.toRight(ParseError(line, "part byte range needs an offset"))
        yield ByteRange(parsedLength, offset)
      case length :: offset :: Nil =>
        for
          parsedLength <- length.toLongOption
            .filter(_ > 0)
            .toRight(ParseError(line, "invalid part byte-range length"))
          parsedOffset <- offset.toLongOption
            .filter(_ >= 0)
            .toRight(ParseError(line, "invalid part byte-range offset"))
        yield ByteRange(parsedLength, parsedOffset)
      case _ => Left(ParseError(line, "invalid part byte range"))

  private def required(
      attributes: Map[String, String],
      name: String,
      line: Int
  ): Either[ParseError, String] =
    attributes.get(name).toRight(ParseError(line, s"attribute $name is required"))

  private def requiredDuration(
      attributes: Map[String, String],
      name: String,
      line: Int
  ): Either[ParseError, Duration] =
    required(attributes, name, line).flatMap(value =>
      Duration.parse(value).left.map(ParseError(line, _))
    )

  private def optionalDuration(
      attributes: Map[String, String],
      name: String,
      line: Int
  ): Either[ParseError, Option[Duration]] =
    attributes.get(name).traverse(value => Duration.parse(value).left.map(ParseError(line, _)))

  private def optionalLong(
      attributes: Map[String, String],
      name: String,
      line: Int,
      minimum: Long
  ): Either[ParseError, Option[Long]] =
    attributes
      .get(name)
      .traverse(value =>
        value.toLongOption.filter(_ >= minimum).toRight(ParseError(line, s"invalid $name"))
      )

  private def yes(attributes: Map[String, String], name: String): Boolean =
    attributes.get(name).contains("YES")

  extension [A](option: Option[A])
    private def traverse[B](function: A => Either[ParseError, B]): Either[ParseError, Option[B]] =
      option match
        case Some(value) => function(value).map(Some(_))
        case None        => Right(None)

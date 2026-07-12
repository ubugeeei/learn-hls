package hls.parser

import hls.model.*
import hls.model.ValueTypes.*
import hls.validation.PlaylistValidator
import scala.util.Try

/** Internal state machine for Multivariant Playlist tags and following URIs. */
private[parser] object MultivariantPlaylistParser:
  def parse(lines: Vector[String]): Either[ParseError, Playlist] =
    var version: Option[Int]                        = None
    var independent                                 = false
    val variants                                    = Vector.newBuilder[Variant]
    val renditions                                  = Vector.newBuilder[Rendition]
    var pending: Option[(Int, Map[String, String])] = None
    lines.zipWithIndex
      .drop(1)
      .foldLeft[Either[ParseError, Unit]](Right(())):
        case (result, (line, index)) =>
          result.flatMap: _ =>
            if line.startsWith("#EXT-X-VERSION:") then
              line
                .drop(15)
                .toIntOption
                .toRight(ParseError(index + 1, "invalid version"))
                .map(value => version = Some(value))
            else if line == "#EXT-X-INDEPENDENT-SEGMENTS" then
              independent = true
              Right(())
            else if line.startsWith("#EXT-X-MEDIA:") then
              AttributeList
                .parse(line.drop(13), index + 1)
                .flatMap(parseRendition(_, index + 1))
                .map(renditions += _)
            else if line.startsWith("#EXT-X-STREAM-INF:") then
              if pending.nonEmpty then Left(ParseError(index + 1, "variant has no URI"))
              else
                AttributeList
                  .parse(line.drop(18), index + 1)
                  .map(attributes => pending = Some(index + 1 -> attributes))
            else if line.startsWith("#EXT-X-TARGETDURATION:") || line.startsWith("#EXTINF:") then
              Left(ParseError(index + 1, "media and multivariant tags cannot be mixed"))
            else if line.isEmpty || line.startsWith("#") then Right(())
            else
              pending match
                case None                        => Left(ParseError(index + 1, "unexpected URI"))
                case Some((tagLine, attributes)) =>
                  parseVariant(attributes, line, tagLine).map: variant =>
                    variants += variant
                    pending = None
      .flatMap(_ =>
        pending.fold[Either[ParseError, Unit]](Right(()))((line, _) =>
          Left(ParseError(line, "variant has no URI"))
        )
      )
      .flatMap: _ =>
        val playlist =
          MultivariantPlaylist(version, independent, renditions.result(), variants.result())
        PlaylistValidator
          .validateMultivariant(playlist)
          .headOption
          .toLeft(Playlist.Multivariant(playlist))
          .left
          .map(ParseError(0, _))

  private def parseVariant(
      attributes: Map[String, String],
      rawUri: String,
      line: Int
  ): Either[ParseError, Variant] =
    for
      rawBandwidth <- attributes
        .get("BANDWIDTH")
        .toRight(ParseError(line, "STREAM-INF requires BANDWIDTH"))
      bandwidth <- Bandwidth.parse(rawBandwidth).left.map(ParseError(line, _))
      average   <- attributes
        .get("AVERAGE-BANDWIDTH")
        .traverse(value => Bandwidth.parse(value).left.map(ParseError(line, _)))
      resolution <- attributes
        .get("RESOLUTION")
        .traverse(value => Resolution.parse(value).left.map(ParseError(line, _)))
      frameRate <- attributes
        .get("FRAME-RATE")
        .traverse(value =>
          Try(BigDecimal(value)).toEither.left.map(_ => ParseError(line, "invalid frame rate"))
        )
      uri <- PlaylistUri.parse(rawUri).left.map(ParseError(line + 1, _))
    yield Variant(
      uri,
      bandwidth,
      average,
      attributes.get("CODECS").toVector.flatMap(_.split(',')),
      resolution,
      frameRate,
      attributes.get("AUDIO"),
      attributes.get("VIDEO"),
      attributes.get("SUBTITLES"),
      attributes.get("CLOSED-CAPTIONS")
    )

  private def parseRendition(
      attributes: Map[String, String],
      line: Int
  ): Either[ParseError, Rendition] =
    def required(name: String) =
      attributes.get(name).toRight(ParseError(line, s"MEDIA requires $name"))
    def yes(name: String) = attributes.get(name).contains("YES")
    for
      rawType   <- required("TYPE")
      mediaType <- rawType match
        case "AUDIO"           => Right(RenditionType.Audio)
        case "VIDEO"           => Right(RenditionType.Video)
        case "SUBTITLES"       => Right(RenditionType.Subtitles)
        case "CLOSED-CAPTIONS" => Right(RenditionType.ClosedCaptions)
        case other             => Left(ParseError(line, s"invalid rendition type: $other"))
      group <- required("GROUP-ID")
      name  <- required("NAME")
      uri   <- attributes
        .get("URI")
        .traverse(value => PlaylistUri.parse(value).left.map(ParseError(line, _)))
    yield Rendition(
      mediaType,
      group,
      name,
      uri,
      attributes.get("LANGUAGE"),
      yes("DEFAULT"),
      yes("AUTOSELECT"),
      yes("FORCED"),
      attributes.get("CHARACTERISTICS").toVector.flatMap(_.split(','))
    )

  extension [A](option: Option[A])
    private def traverse[B](function: A => Either[ParseError, B]): Either[ParseError, Option[B]] =
      option match
        case Some(value) => function(value).map(Some(_))
        case None        => Right(None)

package hls.model

import java.net.URI
import scala.util.Try

/**
 * Validated scalar values used by HLS playlists.
 *
 * The grammar comes from
 * [[https://www.rfc-editor.org/rfc/rfc8216#section-4.2 RFC 8216 section 4.2]]. Opaque types prevent
 * accidentally exchanging values that have the same JVM representation but different protocol
 * meanings.
 */
object ValueTypes:
  opaque type Duration = BigDecimal
  object Duration:
    def from(value: BigDecimal): Either[String, Duration] =
      Either.cond(value >= 0, value, s"duration must be non-negative: $value")
    def parse(value: String): Either[String, Duration] =
      Try(BigDecimal(value)).toEither.left
        .map(_ => s"invalid decimal duration: $value")
        .flatMap(from)
    def unsafe(value: BigDecimal): Duration =
      from(value).fold(message => throw IllegalArgumentException(message), identity)
  extension (value: Duration)
    def decimal: BigDecimal = value
    def render: String      = value.bigDecimal.stripTrailingZeros.toPlainString

  opaque type Bandwidth = Long
  object Bandwidth:
    def from(value: Long): Either[String, Bandwidth] =
      Either.cond(value > 0, value, s"bandwidth must be positive: $value")
    def parse(value: String): Either[String, Bandwidth] =
      value.toLongOption.toRight(s"invalid decimal integer: $value").flatMap(from)
    def unsafe(value: Long): Bandwidth =
      from(value).fold(message => throw IllegalArgumentException(message), identity)
  extension (value: Bandwidth) def bitsPerSecond: Long = value

  opaque type MediaSequence = Long
  object MediaSequence:
    def from(value: Long): Either[String, MediaSequence] =
      Either.cond(value >= 0, value, s"media sequence must be non-negative: $value")
    def parse(value: String): Either[String, MediaSequence] =
      value.toLongOption.toRight(s"invalid decimal integer: $value").flatMap(from)
    def unsafe(value: Long): MediaSequence =
      from(value).fold(message => throw IllegalArgumentException(message), identity)
  extension (value: MediaSequence) def value: Long = value

  opaque type PlaylistUri = URI
  object PlaylistUri:
    def parse(value: String): Either[String, PlaylistUri] =
      Try(URI.create(value)).toEither.left
        .map(_ => s"invalid URI: $value")
        .filterOrElse(
          uri => !uri.toString.exists(c => c == '\r' || c == '\n'),
          "URI contains a line break"
        )
    def unsafe(value: String): PlaylistUri =
      parse(value).fold(message => throw IllegalArgumentException(message), identity)
  extension (value: PlaylistUri) def uri: URI = value

/** A positive pixel resolution used by the `RESOLUTION` attribute. */
final case class Resolution private (width: Int, height: Int)
object Resolution:
  def from(width: Int, height: Int): Either[String, Resolution] =
    Either.cond(
      width > 0 && height > 0,
      Resolution(width, height),
      s"invalid resolution: ${width}x$height"
    )
  def parse(value: String): Either[String, Resolution] = value.split('x').toList match
    case width :: height :: Nil =>
      for
        w      <- width.toIntOption.toRight(s"invalid resolution: $value")
        h      <- height.toIntOption.toRight(s"invalid resolution: $value")
        result <- from(w, h)
      yield result
    case _ => Left(s"invalid resolution: $value")

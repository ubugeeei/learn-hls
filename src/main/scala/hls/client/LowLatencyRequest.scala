package hls.client

import java.net.URI

/** Delta Update mode for the `_HLS_skip` Delivery Directive. */
enum SkipRequest:
  case Yes, V2

/** Validated draft-22 Low-Latency HLS Delivery Directives. */
final case class DeliveryDirectives private (
    mediaSequence: Option[Long],
    part: Option[Int],
    skip: Option[SkipRequest]
):
  /**
   * Appends reserved `_HLS_` query parameters while preserving existing query parameters and URI
   * fragments.
   */
  def applyTo(uri: URI): URI =
    val additions = Vector(
      mediaSequence.map(value => s"_HLS_msn=$value"),
      part.map(value => s"_HLS_part=$value"),
      skip.map:
        case SkipRequest.Yes => "_HLS_skip=YES"
        case SkipRequest.V2  => "_HLS_skip=v2"
    ).flatten
    val query = (Option(uri.getRawQuery).filter(_.nonEmpty).toVector ++ additions).mkString("&")
    URI(uri.getScheme, uri.getRawAuthority, uri.getRawPath, query, uri.getRawFragment)

object DeliveryDirectives:
  /** Constructs a directive set; `_HLS_part` requires `_HLS_msn`. */
  def create(
      mediaSequence: Option[Long] = None,
      part: Option[Int] = None,
      skip: Option[SkipRequest] = None
  ): Either[String, DeliveryDirectives] =
    for
      _ <- Either.cond(mediaSequence.forall(_ >= 0), (), "media sequence must be non-negative")
      _ <- Either.cond(part.forall(_ >= 0), (), "part index must be non-negative")
      _ <- Either.cond(
        part.isEmpty || mediaSequence.nonEmpty,
        (),
        "part index requires media sequence"
      )
      _ <- Either.cond(
        mediaSequence.nonEmpty || part.nonEmpty || skip.nonEmpty,
        (),
        "at least one directive is required"
      )
    yield DeliveryDirectives(mediaSequence, part, skip)

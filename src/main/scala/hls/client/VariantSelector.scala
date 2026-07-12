package hls.client

import hls.model.Variant
import hls.model.ValueTypes.*

/**
 * Playback capabilities used to filter and rank HLS variants.
 *
 * @param estimatedBitsPerSecond
 *   current conservative throughput estimate
 * @param supportedCodecPrefixes
 *   RFC 6381 codec prefixes accepted by the decoder, such as `avc1` and `mp4a`
 * @param maximumWidth
 *   optional display or decoder width limit
 * @param maximumHeight
 *   optional display or decoder height limit
 * @param bandwidthFraction
 *   fraction of estimated throughput available to declared peak bandwidth
 */
final case class PlaybackCapabilities(
    estimatedBitsPerSecond: Long,
    supportedCodecPrefixes: Set[String],
    maximumWidth: Option[Int] = None,
    maximumHeight: Option[Int] = None,
    bandwidthFraction: BigDecimal = BigDecimal("0.8")
):
  require(estimatedBitsPerSecond > 0, "estimated bandwidth must be positive")
  require(bandwidthFraction > 0 && bandwidthFraction <= 1, "bandwidth fraction must be in (0, 1]")

/**
 * Pure adaptive-bitrate variant filtering and ranking.
 *
 * This selector intentionally uses declared peak `BANDWIDTH` for admission and leaves throughput
 * estimation to the caller. It chooses the highest admitted peak bandwidth, breaking ties by pixel
 * count.
 */
object VariantSelector:
  /**
   * Returns the best compatible variant, or `None` when none can be decoded within the configured
   * bandwidth and resolution limits.
   */
  def select(variants: Vector[Variant], capabilities: PlaybackCapabilities): Option[Variant] =
    val budget = BigDecimal(capabilities.estimatedBitsPerSecond) * capabilities.bandwidthFraction
    variants
      .filter(variant => BigDecimal(variant.bandwidth.bitsPerSecond) <= budget)
      .filter(variant => codecsSupported(variant, capabilities.supportedCodecPrefixes))
      .filter(variant => resolutionSupported(variant, capabilities))
      .maxByOption(variant => variant.bandwidth.bitsPerSecond -> pixels(variant))

  private def codecsSupported(variant: Variant, supported: Set[String]): Boolean =
    variant.codecs.forall(codec =>
      supported.exists(prefix => codec == prefix || codec.startsWith(s"$prefix."))
    )

  private def resolutionSupported(variant: Variant, capabilities: PlaybackCapabilities): Boolean =
    variant.resolution.forall: resolution =>
      capabilities.maximumWidth.forall(resolution.width <= _) &&
        capabilities.maximumHeight.forall(resolution.height <= _)

  private def pixels(variant: Variant): Long =
    variant.resolution.fold(0L)(resolution => resolution.width.toLong * resolution.height)

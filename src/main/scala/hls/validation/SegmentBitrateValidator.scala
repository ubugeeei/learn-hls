package hls.validation

import hls.model.MediaPlaylist
import hls.model.ValueTypes.*

/**
 * Checks advertised `EXT-X-BITRATE` values against encoded resource sizes.
 *
 * The playlist parser cannot prove this media-level invariant because a manifest does not contain
 * segment byte lengths. A packager or origin can supply them here. The declared rate must be within
 * 90% to 110% of every applicable segment's measured bit rate, per
 * [[https://datatracker.ietf.org/doc/html/draft-pantos-hls-rfc8216bis-22#section-4.4.4.8 HLS 2 draft-22 §4.4.4.8]].
 */
object SegmentBitrateValidator:
  enum Error:
    case MissingSize(segmentIndex: Int, uri: String)
    case OutsideTolerance(
        segmentIndex: Int,
        declaredKbps: Long,
        measuredKbps: BigDecimal
    )

  /**
   * Validates every segment that carries an approximate bitrate.
   *
   * @param byteLength
   *   returns the complete encoded resource length for a segment; byte-range segments never ask for
   *   a value because `EXT-X-BITRATE` does not apply to them
   */
  def validate(
      playlist: MediaPlaylist,
      byteLength: Int => Option[Long]
  ): Vector[Error] =
    playlist.segments.zipWithIndex.flatMap: (segment, index) =>
      segment.bitrateKbps.toVector.flatMap: declared =>
        byteLength(index) match
          case None        => Vector(Error.MissingSize(index, segment.uri.uri.toString))
          case Some(bytes) =>
            val measured = BigDecimal(bytes) * 8 / segment.duration.decimal / 1000
            val minimum  = measured * BigDecimal("0.9")
            val maximum  = measured * BigDecimal("1.1")
            Option
              .when(BigDecimal(declared) < minimum || BigDecimal(declared) > maximum)(
                Error.OutsideTolerance(index, declared, measured)
              )
              .toVector

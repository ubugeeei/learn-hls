package hls.model

import hls.model.ValueTypes.*

/**
 * `EXT-X-PART-INF` Part Target Duration. draft-pantos-hls-rfc8216bis-22 §4.4.3.7.
 */
final case class PartInformation(partTarget: Duration)

/**
 * Delivery capabilities and hold-back recommendations from `EXT-X-SERVER-CONTROL`.
 */
final case class ServerControl(
    canSkipUntil: Option[Duration] = None,
    canSkipDateRanges: Boolean = false,
    holdBack: Option[Duration] = None,
    partHoldBack: Option[Duration] = None,
    canBlockReload: Boolean = false
)

/** One Low-Latency HLS Partial Segment with explicit parent identity. */
final case class PartialSegment(
    parentMediaSequence: Long,
    partIndex: Int,
    uri: PlaylistUri,
    duration: Duration,
    independent: Boolean = false,
    byteRange: Option[ByteRange] = None,
    gap: Boolean = false
)

/** Delta Update metadata replacing older segments and optional date ranges. */
final case class PlaylistSkip(
    skippedSegments: Long,
    recentlyRemovedDateRangeIds: Vector[String] = Vector.empty
)

/** Resource class advertised by `EXT-X-PRELOAD-HINT`. */
enum PreloadHintType:
  case Part, Map

/** A resource that the server expects to publish and may block while producing. */
final case class PreloadHint(
    hintType: PreloadHintType,
    uri: PlaylistUri,
    byteRangeStart: Option[Long] = None,
    byteRangeLength: Option[Long] = None
)

/**
 * Latest position advertised for an associated rendition. Values are optional because draft-22
 * permits omission when equal to the containing Playlist.
 */
final case class RenditionReport(
    uri: PlaylistUri,
    lastMediaSequence: Option[Long] = None,
    lastPart: Option[Int] = None
)

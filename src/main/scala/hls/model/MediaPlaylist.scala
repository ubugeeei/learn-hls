package hls.model

import hls.model.ValueTypes.*
import java.time.OffsetDateTime

/**
 * Indicates whether a Media Playlist can change.
 * [[https://www.rfc-editor.org/rfc/rfc8216#section-4.3.3.5 RFC 8216 §4.3.3.5]]
 */
enum PlaylistType:
  case Event, Vod

/**
 * Encryption state applying to subsequent segments.
 * [[https://www.rfc-editor.org/rfc/rfc8216#section-4.3.2.4 RFC 8216 §4.3.2.4]]
 */
enum Encryption:
  case None
  case Aes128(uri: PlaylistUri, iv: Option[String])
  case SampleAes(
      uri: PlaylistUri,
      keyFormat: Option[String],
      versions: Option[String],
      iv: Option[String]
  )

/**
 * A byte sub-range of a resource. The offset is optional only in source syntax; parsers resolve
 * implicit offsets before constructing this value.
 */
final case class ByteRange(length: Long, offset: Long):
  require(length > 0, "byte range length must be positive")
  require(offset >= 0, "byte range offset must be non-negative")

/**
 * An fMP4 Media Initialization Section.
 * [[https://www.rfc-editor.org/rfc/rfc8216#section-4.3.2.5 RFC 8216 §4.3.2.5]]
 */
final case class InitializationMap(uri: PlaylistUri, byteRange: Option[ByteRange] = None)

/**
 * One Media Segment and the tags scoped to it.
 * [[https://www.rfc-editor.org/rfc/rfc8216#section-3 RFC 8216 §3]]
 */
final case class MediaSegment(
    uri: PlaylistUri,
    duration: Duration,
    title: Option[String] = None,
    byteRange: Option[ByteRange] = None,
    discontinuity: Boolean = false,
    encryption: Encryption = Encryption.None,
    initializationMap: Option[InitializationMap] = None,
    programDateTime: Option[OffsetDateTime] = None,
    gap: Boolean = false,
    dateRanges: Vector[DateRange] = Vector.empty,
    /**
     * Approximate rate in kilobits per second; absent for byte-range segments.
     * [[https://datatracker.ietf.org/doc/html/draft-pantos-hls-rfc8216bis-22#section-4.4.4.8 HLS 2 draft-22 §4.4.4.8]]
     */
    bitrateKbps: Option[Long] = None
)

/** A complete Media Playlist. Construction is validated by [[hls.validation.PlaylistValidator]]. */
final case class MediaPlaylist(
    version: Option[Int],
    targetDurationSeconds: Long,
    mediaSequence: MediaSequence = MediaSequence.unsafe(0),
    discontinuitySequence: Long = 0,
    playlistType: Option[PlaylistType] = None,
    independentSegments: Boolean = false,
    segments: Vector[MediaSegment],
    ended: Boolean = false,
    start: Option[StartOffset] = None,
    iFramesOnly: Boolean = false,
    partInformation: Option[PartInformation] = None,
    serverControl: Option[ServerControl] = None,
    partialSegments: Vector[PartialSegment] = Vector.empty,
    skip: Option[PlaylistSkip] = None,
    preloadHints: Vector[PreloadHint] = Vector.empty,
    renditionReports: Vector[RenditionReport] = Vector.empty
)

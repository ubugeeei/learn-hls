package hls.model

import hls.model.ValueTypes.*

/**
 * The media type of an alternative rendition.
 * [[https://www.rfc-editor.org/rfc/rfc8216#section-4.3.4.1 RFC 8216 §4.3.4.1]]
 */
enum RenditionType:
  case Audio, Video, Subtitles, ClosedCaptions

/** A rendition selected alongside a variant stream. */
final case class Rendition(
    mediaType: RenditionType,
    groupId: String,
    name: String,
    uri: Option[PlaylistUri] = None,
    language: Option[String] = None,
    default: Boolean = false,
    autoselect: Boolean = false,
    forced: Boolean = false,
    characteristics: Vector[String] = Vector.empty
)

/**
 * A playable variant described by `EXT-X-STREAM-INF`.
 * [[https://www.rfc-editor.org/rfc/rfc8216#section-4.3.4.2 RFC 8216 §4.3.4.2]]
 */
final case class Variant(
    uri: PlaylistUri,
    bandwidth: Bandwidth,
    averageBandwidth: Option[Bandwidth] = None,
    codecs: Vector[String] = Vector.empty,
    resolution: Option[Resolution] = None,
    frameRate: Option[BigDecimal] = None,
    audioGroup: Option[String] = None,
    videoGroup: Option[String] = None,
    subtitlesGroup: Option[String] = None,
    closedCaptionsGroup: Option[String] = None
)

/** A Master Playlist, renamed "Multivariant Playlist" by current HLS terminology. */
final case class MultivariantPlaylist(
    version: Option[Int],
    independentSegments: Boolean = false,
    renditions: Vector[Rendition] = Vector.empty,
    variants: Vector[Variant]
)

/**
 * The two playlist kinds are mutually exclusive per
 * [[https://www.rfc-editor.org/rfc/rfc8216#section-4.3.1.1 RFC 8216 §4.3.1.1]].
 */
enum Playlist:
  case Media(value: MediaPlaylist)
  case Multivariant(value: MultivariantPlaylist)

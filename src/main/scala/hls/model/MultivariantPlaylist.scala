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
    associatedLanguage: Option[String] = None,
    default: Boolean = false,
    autoselect: Boolean = false,
    forced: Boolean = false,
    characteristics: Vector[String] = Vector.empty,
    instreamId: Option[String] = None,
    channels: Option[String] = None
)

/** HDCP protection level required to play a variant. */
enum HdcpLevel:
  case Type0, None

/**
 * The `CLOSED-CAPTIONS` attribute distinguishes an omitted attribute, a group, and the explicit
 * `NONE` value.
 */
enum ClosedCaptions:
  case Group(id: String)
  case None

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
    closedCaptions: Option[ClosedCaptions] = None,
    hdcpLevel: Option[HdcpLevel] = None
)

/**
 * An I-frame-only variant used for fast forward and reverse playback.
 * [[https://www.rfc-editor.org/rfc/rfc8216#section-4.3.4.3 RFC 8216 §4.3.4.3]]
 */
final case class IFrameVariant(
    uri: PlaylistUri,
    bandwidth: Bandwidth,
    averageBandwidth: Option[Bandwidth] = None,
    codecs: Vector[String] = Vector.empty,
    resolution: Option[Resolution] = None,
    hdcpLevel: Option[HdcpLevel] = None,
    videoGroup: Option[String] = None
)

/**
 * Session-wide application data embedded in a Multivariant Playlist. Exactly one of `value` and
 * `uri` must be present.
 * [[https://www.rfc-editor.org/rfc/rfc8216#section-4.3.4.4 RFC 8216 §4.3.4.4]]
 */
final case class SessionData(
    dataId: String,
    value: Option[String] = None,
    uri: Option[PlaylistUri] = None,
    language: Option[String] = None
)

/** A Master Playlist, renamed "Multivariant Playlist" by current HLS terminology. */
final case class MultivariantPlaylist(
    version: Option[Int],
    independentSegments: Boolean = false,
    renditions: Vector[Rendition] = Vector.empty,
    variants: Vector[Variant],
    iFrameVariants: Vector[IFrameVariant] = Vector.empty,
    sessionData: Vector[SessionData] = Vector.empty,
    sessionKeys: Vector[Encryption] = Vector.empty,
    start: Option[StartOffset] = None
)

/**
 * The two playlist kinds are mutually exclusive per
 * [[https://www.rfc-editor.org/rfc/rfc8216#section-4.3.1.1 RFC 8216 §4.3.1.1]].
 */
enum Playlist:
  case Media(value: MediaPlaylist)
  case Multivariant(value: MultivariantPlaylist)

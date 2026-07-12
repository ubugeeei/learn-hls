package hls.client

import hls.model.*
import hls.model.ValueTypes.*
import java.net.URI

/**
 * Resolves every URI reference in a playlist against its retrieval URI.
 *
 * Relative references are defined by RFC 3986 and used throughout HLS; see
 * [[https://www.rfc-editor.org/rfc/rfc8216#section-4.1 RFC 8216 §4.1]]. Keeping resolution separate
 * from parsing allows the same parser to handle strings that have not yet been associated with a
 * network location.
 */
object PlaylistResolver:
  def resolve(playlist: Playlist, base: URI): Playlist = playlist match
    case Playlist.Media(value)        => Playlist.Media(resolveMedia(value, base))
    case Playlist.Multivariant(value) => Playlist.Multivariant(resolveMultivariant(value, base))

  def resolveMedia(playlist: MediaPlaylist, base: URI): MediaPlaylist =
    playlist.copy(
      segments = playlist.segments.map: segment =>
        segment.copy(
          uri = resolveUri(segment.uri, base),
          encryption = segment.encryption match
            case Encryption.None            => Encryption.None
            case Encryption.Aes128(uri, iv) => Encryption.Aes128(resolveUri(uri, base), iv)
            case Encryption.SampleAes(uri, format, versions, iv) =>
              Encryption.SampleAes(resolveUri(uri, base), format, versions, iv),
          initializationMap =
            segment.initializationMap.map(map => map.copy(uri = resolveUri(map.uri, base)))
        ),
      partialSegments =
        playlist.partialSegments.map(part => part.copy(uri = resolveUri(part.uri, base))),
      preloadHints = playlist.preloadHints.map(hint => hint.copy(uri = resolveUri(hint.uri, base))),
      renditionReports =
        playlist.renditionReports.map(report => report.copy(uri = resolveUri(report.uri, base)))
    )

  def resolveMultivariant(playlist: MultivariantPlaylist, base: URI): MultivariantPlaylist =
    playlist.copy(
      renditions = playlist.renditions.map(rendition =>
        rendition.copy(uri = rendition.uri.map(resolveUri(_, base)))
      ),
      variants =
        playlist.variants.map(variant => variant.copy(uri = resolveUri(variant.uri, base))),
      iFrameVariants =
        playlist.iFrameVariants.map(variant => variant.copy(uri = resolveUri(variant.uri, base))),
      sessionData =
        playlist.sessionData.map(data => data.copy(uri = data.uri.map(resolveUri(_, base)))),
      sessionKeys = playlist.sessionKeys.map:
        case Encryption.None            => Encryption.None
        case Encryption.Aes128(uri, iv) => Encryption.Aes128(resolveUri(uri, base), iv)
        case Encryption.SampleAes(uri, format, versions, iv) =>
          Encryption.SampleAes(resolveUri(uri, base), format, versions, iv)
    )

  private def resolveUri(reference: PlaylistUri, base: URI): PlaylistUri =
    PlaylistUri.unsafe(base.resolve(reference.uri).toString)

package hls.validation

import hls.model.*
import hls.model.ValueTypes.*

/** Semantic validation rules that are useful to both parsers and builders. */
object PlaylistValidator:
  def validate(playlist: Playlist): Vector[String] = playlist match
    case Playlist.Media(value) => validateMedia(value)
    case Playlist.Multivariant(value) => validateMultivariant(value)

  def validateMedia(playlist: MediaPlaylist): Vector[String] =
    val targetErrors =
      if playlist.targetDurationSeconds <= 0 then Vector("target duration must be positive")
      else playlist.segments.collect:
        case segment if segment.duration.decimal.setScale(0, BigDecimal.RoundingMode.HALF_UP) > playlist.targetDurationSeconds =>
          s"segment duration ${segment.duration.render} rounds above target duration ${playlist.targetDurationSeconds}"
    val typeErrors = playlist.playlistType match
      case Some(PlaylistType.Vod) if !playlist.ended => Vector("a VOD playlist must contain EXT-X-ENDLIST")
      case _ => Vector.empty
    val versionErrors = minimumVersion(playlist).toVector.flatMap: required =>
      playlist.version.filter(_ < required).map(actual => s"playlist version $actual is lower than required version $required")
    targetErrors ++ typeErrors ++ versionErrors

  def validateMultivariant(playlist: MultivariantPlaylist): Vector[String] =
    val groups = playlist.renditions.map(_.groupId).toSet
    val references = playlist.variants.flatMap(v => Vector(v.audioGroup, v.videoGroup, v.subtitlesGroup).flatten)
    val referenceErrors = references.filterNot(groups).distinct.map(group => s"variant references missing rendition group: $group")
    val renditionErrors = playlist.renditions.collect:
      case r if r.forced && r.mediaType != RenditionType.Subtitles => s"FORCED is only valid for SUBTITLES: ${r.name}"
      case r if r.default && !r.autoselect => s"DEFAULT=YES requires AUTOSELECT=YES: ${r.name}"
    (if playlist.variants.isEmpty then Vector("multivariant playlist has no variants") else Vector.empty) ++ referenceErrors ++ renditionErrors

  /** Minimum compatibility version from RFC 8216 section 7. */
  def minimumVersion(playlist: MediaPlaylist): Option[Int] =
    val versions = playlist.segments.flatMap: segment =>
      Vector(
        Option.when(segment.byteRange.nonEmpty)(4),
        Option.when(segment.initializationMap.nonEmpty)(6),
        Option.when(segment.encryption match
          case Encryption.SampleAes(_, Some(_), _, _) => true
          case _ => false)(5)
      ).flatten
    versions.maxOption


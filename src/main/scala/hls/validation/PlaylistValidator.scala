package hls.validation

import hls.model.*
import hls.model.ValueTypes.*

/** Semantic validation rules that are useful to both parsers and builders. */
object PlaylistValidator:
  def validate(playlist: Playlist): Vector[String] = playlist match
    case Playlist.Media(value)        => validateMedia(value)
    case Playlist.Multivariant(value) => validateMultivariant(value)

  def validateMedia(playlist: MediaPlaylist): Vector[String] =
    val targetErrors =
      if playlist.targetDurationSeconds <= 0 then Vector("target duration must be positive")
      else
        playlist.segments.collect:
          case segment
              if segment.duration.decimal.setScale(
                0,
                BigDecimal.RoundingMode.HALF_UP
              ) > playlist.targetDurationSeconds =>
            s"segment duration ${segment.duration.render} rounds above target duration ${playlist.targetDurationSeconds}"
    val typeErrors = playlist.playlistType match
      case Some(PlaylistType.Vod) if !playlist.ended =>
        Vector("a VOD playlist must contain EXT-X-ENDLIST")
      case _ => Vector.empty
    val versionErrors = minimumVersion(playlist).toVector.flatMap: required =>
      playlist.version
        .filter(_ < required)
        .map(actual => s"playlist version $actual is lower than required version $required")
    val dateRanges      = playlist.segments.flatMap(_.dateRanges)
    val dateRangeErrors =
      dateRanges
        .groupBy(_.id)
        .collect { case (id, ranges) if ranges.size > 1 => s"duplicate date range ID: $id" }
        .toVector ++
        dateRanges.collect:
          case range if range.endOnNext && range.className.isEmpty =>
            s"END-ON-NEXT date range requires CLASS: ${range.id}"
          case range if range.endOnNext && (range.duration.nonEmpty || range.endDate.nonEmpty) =>
            s"END-ON-NEXT date range cannot have END-DATE or DURATION: ${range.id}"
    targetErrors ++ typeErrors ++ versionErrors ++ dateRangeErrors

  def validateMultivariant(playlist: MultivariantPlaylist): Vector[String] =
    val groups     = playlist.renditions.map(_.groupId).toSet
    val references = playlist.variants.flatMap(v =>
      Vector(v.audioGroup, v.videoGroup, v.subtitlesGroup).flatten ++
        v.closedCaptions.collect { case ClosedCaptions.Group(id) => id }
    ) ++ playlist.iFrameVariants.flatMap(_.videoGroup)
    val referenceErrors = references
      .filterNot(groups)
      .distinct
      .map(group => s"variant references missing rendition group: $group")
    val renditionErrors = playlist.renditions.collect:
      case r if r.forced && r.mediaType != RenditionType.Subtitles =>
        s"FORCED is only valid for SUBTITLES: ${r.name}"
      case r if r.default && !r.autoselect => s"DEFAULT=YES requires AUTOSELECT=YES: ${r.name}"
      case r if r.mediaType == RenditionType.Subtitles && r.uri.isEmpty =>
        s"SUBTITLES rendition requires URI: ${r.name}"
      case r if r.mediaType == RenditionType.ClosedCaptions && r.uri.nonEmpty =>
        s"CLOSED-CAPTIONS rendition forbids URI: ${r.name}"
      case r if r.mediaType == RenditionType.ClosedCaptions && r.instreamId.isEmpty =>
        s"CLOSED-CAPTIONS rendition requires INSTREAM-ID: ${r.name}"
      case r if r.instreamId.nonEmpty && r.mediaType != RenditionType.ClosedCaptions =>
        s"INSTREAM-ID is only valid for CLOSED-CAPTIONS: ${r.name}"
    val sessionDataErrors = playlist.sessionData
      .groupBy(data => data.dataId -> data.language)
      .collect:
        case ((dataId, language), values) if values.size > 1 =>
          s"duplicate SESSION-DATA combination: $dataId/${language.getOrElse("")}"
      .toVector
    val captionModes  = playlist.variants.flatMap(_.closedCaptions)
    val captionErrors =
      if captionModes.contains(ClosedCaptions.None) && captionModes.exists:
          case ClosedCaptions.Group(_) => true
          case ClosedCaptions.None     => false
      then Vector("CLOSED-CAPTIONS=NONE must appear on every variant when used")
      else Vector.empty
    (if playlist.variants.isEmpty then Vector("multivariant playlist has no variants")
     else Vector.empty) ++ referenceErrors ++ renditionErrors ++ sessionDataErrors ++ captionErrors

  /** Minimum compatibility version from RFC 8216 section 7. */
  def minimumVersion(playlist: MediaPlaylist): Option[Int] =
    val versions = playlist.segments.flatMap: segment =>
      Vector(
        Option.when(segment.byteRange.nonEmpty)(4),
        Option.when(segment.initializationMap.nonEmpty)(6),
        Option.when(segment.dateRanges.nonEmpty)(6),
        Option.when(segment.gap)(6),
        Option.when(segment.encryption match
          case Encryption.SampleAes(_, Some(_), _, _) => true
          case _                                      => false)(5)
      ).flatten
    versions.maxOption

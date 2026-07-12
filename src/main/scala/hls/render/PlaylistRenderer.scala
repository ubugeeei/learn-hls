package hls.render

import hls.model.*
import hls.model.ValueTypes.*

/**
 * Canonical serialization to the UTF-8 Extended M3U format.
 * [[https://www.rfc-editor.org/rfc/rfc8216#section-4.1 RFC 8216 §4.1]]
 */
object PlaylistRenderer:
  /** Renders either playlist kind using deterministic tag ordering. */
  def render(playlist: Playlist): String = playlist match
    case Playlist.Media(value)        => renderMedia(value)
    case Playlist.Multivariant(value) => renderMultivariant(value)

  /** Renders a validated Media Playlist with a trailing newline. */
  def renderMedia(playlist: MediaPlaylist): String =
    val header = Vector("#EXTM3U") ++
      playlist.version.map(v => s"#EXT-X-VERSION:$v") ++
      Vector(s"#EXT-X-TARGETDURATION:${playlist.targetDurationSeconds}") ++
      Option.when(playlist.mediaSequence.value != 0)(
        s"#EXT-X-MEDIA-SEQUENCE:${playlist.mediaSequence.value}"
      ) ++
      Option.when(playlist.discontinuitySequence != 0)(
        s"#EXT-X-DISCONTINUITY-SEQUENCE:${playlist.discontinuitySequence}"
      ) ++
      playlist.playlistType.map:
        case PlaylistType.Event => "#EXT-X-PLAYLIST-TYPE:EVENT"
        case PlaylistType.Vod   => "#EXT-X-PLAYLIST-TYPE:VOD"
      ++ Option.when(playlist.independentSegments)("#EXT-X-INDEPENDENT-SEGMENTS") ++
      playlist.start.map(renderStart) ++
      Option.when(playlist.iFramesOnly)("#EXT-X-I-FRAMES-ONLY") ++
      playlist.partInformation.map(info =>
        s"#EXT-X-PART-INF:PART-TARGET=${info.partTarget.render}"
      ) ++
      playlist.serverControl.map(renderServerControl) ++
      playlist.skip.map(renderSkip)
    var key: Encryption                    = Encryption.None
    var initMap: Option[InitializationMap] = None
    val body = playlist.segments.zipWithIndex.flatMap: (segment, index) =>
      val sequence =
        playlist.mediaSequence.value + playlist.skip.fold(0L)(_.skippedSegments) + index
      val partLines =
        playlist.partialSegments.filter(_.parentMediaSequence == sequence).map(renderPart)
      val keyLine = Option.when(segment.encryption != key)(renderKey(segment.encryption))
      val mapLine = Option.when(segment.initializationMap != initMap)(
        segment.initializationMap.map(renderMap).getOrElse("")
      )
      key = segment.encryption
      initMap = segment.initializationMap
      keyLine ++ mapLine.filter(_.nonEmpty) ++ partLines ++
        segment.dateRanges.map(renderDateRange) ++
        Option.when(segment.discontinuity)("#EXT-X-DISCONTINUITY") ++
        segment.programDateTime.map(date => s"#EXT-X-PROGRAM-DATE-TIME:$date") ++
        Vector(s"#EXTINF:${segment.duration.render},${segment.title.getOrElse("")}") ++
        segment.byteRange.map(range => s"#EXT-X-BYTERANGE:${range.length}@${range.offset}") ++
        Option.when(segment.gap)("#EXT-X-GAP") ++
        Vector(segment.uri.toString)
    val completedSequences = playlist.segments.indices
      .map(index =>
        playlist.mediaSequence.value + playlist.skip.fold(0L)(_.skippedSegments) + index
      )
      .toSet
    val trailingParts = playlist.partialSegments
      .filterNot(part => completedSequences(part.parentMediaSequence))
      .sortBy(part => part.parentMediaSequence -> part.partIndex)
      .map(renderPart)
    val metadata = playlist.preloadHints.map(renderPreloadHint) ++
      playlist.renditionReports.map(renderRenditionReport)
    (header ++ body ++ trailingParts ++ metadata ++ Option.when(playlist.ended)("#EXT-X-ENDLIST"))
      .mkString("", "\n", "\n")

  /** Renders a validated Multivariant Playlist with a trailing newline. */
  def renderMultivariant(playlist: MultivariantPlaylist): String =
    val header = Vector("#EXTM3U") ++ playlist.version.map(v => s"#EXT-X-VERSION:$v") ++
      Option.when(playlist.independentSegments)("#EXT-X-INDEPENDENT-SEGMENTS") ++
      playlist.start.map(renderStart)
    val renditionLines = playlist.renditions.map: rendition =>
      val attributes = Vector(
        Some("TYPE" -> (rendition.mediaType match
          case RenditionType.Audio          => "AUDIO"
          case RenditionType.Video          => "VIDEO"
          case RenditionType.Subtitles      => "SUBTITLES"
          case RenditionType.ClosedCaptions => "CLOSED-CAPTIONS")),
        Some("GROUP-ID" -> quote(rendition.groupId)),
        Some("NAME"     -> quote(rendition.name)),
        rendition.uri.map(uri => "URI" -> quote(uri.toString)),
        rendition.language.map("LANGUAGE" -> quote(_)),
        rendition.associatedLanguage.map("ASSOC-LANGUAGE" -> quote(_)),
        Option.when(rendition.default)("DEFAULT"       -> "YES"),
        Option.when(rendition.autoselect)("AUTOSELECT" -> "YES"),
        Option.when(rendition.forced)("FORCED"         -> "YES"),
        Option.when(rendition.characteristics.nonEmpty)(
          "CHARACTERISTICS" -> quote(rendition.characteristics.mkString(","))
        ),
        rendition.instreamId.map("INSTREAM-ID" -> quote(_)),
        rendition.channels.map("CHANNELS" -> quote(_))
      ).flatten
      s"#EXT-X-MEDIA:${attributes.map((k, v) => s"$k=$v").mkString(",")}"
    val variantLines = playlist.variants.flatMap: variant =>
      val attributes = Vector(
        Some("BANDWIDTH" -> variant.bandwidth.bitsPerSecond.toString),
        variant.averageBandwidth.map(v => "AVERAGE-BANDWIDTH" -> v.bitsPerSecond.toString),
        Option.when(variant.codecs.nonEmpty)("CODECS" -> quote(variant.codecs.mkString(","))),
        variant.resolution.map(r => "RESOLUTION" -> s"${r.width}x${r.height}"),
        variant.frameRate.map(v => "FRAME-RATE" -> v.bigDecimal.stripTrailingZeros.toPlainString),
        variant.audioGroup.map("AUDIO" -> quote(_)),
        variant.videoGroup.map("VIDEO" -> quote(_)),
        variant.subtitlesGroup.map("SUBTITLES" -> quote(_)),
        variant.closedCaptions.map:
          case ClosedCaptions.Group(id) => "CLOSED-CAPTIONS" -> quote(id)
          case ClosedCaptions.None      => "CLOSED-CAPTIONS" -> "NONE",
        variant.hdcpLevel.map("HDCP-LEVEL" -> renderHdcp(_))
      ).flatten
      Vector(
        s"#EXT-X-STREAM-INF:${attributes.map((k, v) => s"$k=$v").mkString(",")}",
        variant.uri.toString
      )
    val iFrameLines = playlist.iFrameVariants.map: variant =>
      val attributes = Vector(
        Some("URI"       -> quote(variant.uri.toString)),
        Some("BANDWIDTH" -> variant.bandwidth.bitsPerSecond.toString),
        variant.averageBandwidth.map(value => "AVERAGE-BANDWIDTH" -> value.bitsPerSecond.toString),
        Option.when(variant.codecs.nonEmpty)("CODECS" -> quote(variant.codecs.mkString(","))),
        variant.resolution.map(value => "RESOLUTION" -> s"${value.width}x${value.height}"),
        variant.hdcpLevel.map("HDCP-LEVEL" -> renderHdcp(_)),
        variant.videoGroup.map("VIDEO" -> quote(_))
      ).flatten
      s"#EXT-X-I-FRAME-STREAM-INF:${renderAttributes(attributes)}"
    val dataLines = playlist.sessionData.map: data =>
      val attributes = Vector(
        Some("DATA-ID" -> quote(data.dataId)),
        data.value.map("VALUE" -> quote(_)),
        data.uri.map(uri => "URI" -> quote(uri.toString)),
        data.language.map("LANGUAGE" -> quote(_))
      ).flatten
      s"#EXT-X-SESSION-DATA:${renderAttributes(attributes)}"
    val keyLines = playlist.sessionKeys.map(encryption =>
      renderKey(encryption).replace("#EXT-X-KEY:", "#EXT-X-SESSION-KEY:")
    )
    (header ++ renditionLines ++ keyLines ++ dataLines ++ variantLines ++ iFrameLines)
      .mkString("", "\n", "\n")

  private def renderKey(encryption: Encryption): String = encryption match
    case Encryption.None            => "#EXT-X-KEY:METHOD=NONE"
    case Encryption.Aes128(uri, iv) =>
      s"#EXT-X-KEY:METHOD=AES-128,URI=${quote(uri.toString)}${iv.fold("")(v => s",IV=$v")}"
    case Encryption.SampleAes(uri, keyFormat, versions, iv) =>
      val optional = Vector(
        keyFormat.map(v => s"KEYFORMAT=${quote(v)}"),
        versions.map(v => s"KEYFORMATVERSIONS=${quote(v)}"),
        iv.map(v => s"IV=$v")
      ).flatten
      (Vector(s"#EXT-X-KEY:METHOD=SAMPLE-AES", s"URI=${quote(uri.toString)}") ++ optional)
        .mkString(",")

  private def renderMap(map: InitializationMap): String =
    s"#EXT-X-MAP:URI=${quote(
        map.uri.toString
      )}${map.byteRange.fold("")(r => s",BYTERANGE=${quote(s"${r.length}@${r.offset}")}")}"

  private def quote(value: String): String = s"\"$value\""

  private def renderStart(start: StartOffset): String =
    s"#EXT-X-START:TIME-OFFSET=${decimal(start.seconds)}${
        if start.precise then ",PRECISE=YES" else ""
      }"

  private def renderDateRange(range: DateRange): String =
    val attributes = Vector(
      Some("ID"         -> quote(range.id)),
      Some("START-DATE" -> quote(range.startDate.toString)),
      range.className.map("CLASS" -> quote(_)),
      range.endDate.map(value => "END-DATE" -> quote(value.toString)),
      range.duration.map(value => "DURATION" -> value.render),
      range.plannedDuration.map(value => "PLANNED-DURATION" -> value.render),
      Option.when(range.endOnNext)("END-ON-NEXT" -> "YES"),
      range.scte35Cmd.map("SCTE35-CMD" -> _),
      range.scte35Out.map("SCTE35-OUT" -> _),
      range.scte35In.map("SCTE35-IN" -> _)
    ).flatten ++ range.clientAttributes.toVector
      .sortBy(_._1)
      .map((name, value) => name -> quote(value))
    s"#EXT-X-DATERANGE:${attributes.map((name, value) => s"$name=$value").mkString(",")}"

  private def decimal(value: BigDecimal): String = value.bigDecimal.stripTrailingZeros.toPlainString

  private def renderHdcp(value: HdcpLevel): String = value match
    case HdcpLevel.Type0 => "TYPE-0"
    case HdcpLevel.None  => "NONE"

  private def renderAttributes(attributes: Vector[(String, String)]): String =
    attributes.map((name, value) => s"$name=$value").mkString(",")

  private def renderServerControl(control: ServerControl): String =
    val attributes = Vector(
      control.canSkipUntil.map(value => "CAN-SKIP-UNTIL" -> value.render),
      Option.when(control.canSkipDateRanges)("CAN-SKIP-DATERANGES" -> "YES"),
      control.holdBack.map(value => "HOLD-BACK" -> value.render),
      control.partHoldBack.map(value => "PART-HOLD-BACK" -> value.render),
      Option.when(control.canBlockReload)("CAN-BLOCK-RELOAD" -> "YES")
    ).flatten
    s"#EXT-X-SERVER-CONTROL:${renderAttributes(attributes)}"

  private def renderPart(part: PartialSegment): String =
    val attributes = Vector(
      Some("DURATION"                             -> part.duration.render),
      Some("URI"                                  -> quote(part.uri.toString)),
      Option.when(part.independent)("INDEPENDENT" -> "YES"),
      part.byteRange.map(range => "BYTERANGE" -> quote(s"${range.length}@${range.offset}")),
      Option.when(part.gap)("GAP" -> "YES")
    ).flatten
    s"#EXT-X-PART:${renderAttributes(attributes)}"

  private def renderSkip(skip: PlaylistSkip): String =
    val attributes = Vector(
      Some("SKIPPED-SEGMENTS" -> skip.skippedSegments.toString),
      Option
        .when(skip.recentlyRemovedDateRangeIds.nonEmpty)(
          "RECENTLY-REMOVED-DATERANGES" -> quote(skip.recentlyRemovedDateRangeIds.mkString("\t"))
        )
    ).flatten
    s"#EXT-X-SKIP:${renderAttributes(attributes)}"

  private def renderPreloadHint(hint: PreloadHint): String =
    val attributes = Vector(
      Some("TYPE" -> (hint.hintType match
        case PreloadHintType.Part => "PART"
        case PreloadHintType.Map  => "MAP")),
      Some("URI" -> quote(hint.uri.toString)),
      hint.byteRangeStart.map(value => "BYTERANGE-START" -> value.toString),
      hint.byteRangeLength.map(value => "BYTERANGE-LENGTH" -> value.toString)
    ).flatten
    s"#EXT-X-PRELOAD-HINT:${renderAttributes(attributes)}"

  private def renderRenditionReport(report: RenditionReport): String =
    val attributes = Vector(
      Some("URI" -> quote(report.uri.toString)),
      report.lastMediaSequence.map(value => "LAST-MSN" -> value.toString),
      report.lastPart.map(value => "LAST-PART" -> value.toString)
    ).flatten
    s"#EXT-X-RENDITION-REPORT:${renderAttributes(attributes)}"

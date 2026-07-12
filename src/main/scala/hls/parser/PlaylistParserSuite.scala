//> using target.scope test
package hls.parser

import hls.model.*
import hls.model.ValueTypes.*
import hls.render.PlaylistRenderer

final class PlaylistParserSuite extends munit.FunSuite:
  test("parse an encrypted fMP4 VOD media playlist and round trip"):
    val source = """#EXTM3U
      |#EXT-X-VERSION:7
      |#EXT-X-TARGETDURATION:6
      |#EXT-X-PLAYLIST-TYPE:VOD
      |#EXT-X-MAP:URI="init.mp4",BYTERANGE="720@0"
      |#EXT-X-KEY:METHOD=AES-128,URI="key.bin",IV=0x00000000000000000000000000000001
      |#EXT-X-PROGRAM-DATE-TIME:2026-07-11T12:00:00+09:00
      |#EXTINF:5.005,Opening
      |#EXT-X-BYTERANGE:1000@720
      |media.mp4
      |#EXTINF:4.5,
      |#EXT-X-BYTERANGE:900
      |media.mp4
      |#EXT-X-ENDLIST
      |""".stripMargin
    val parsed = PlaylistParser.parse(source)
    assert(parsed.isRight, parsed.left.toOption.map(_.toString).getOrElse(""))
    val media = parsed.toOption.get.asInstanceOf[Playlist.Media].value
    assertEquals(media.segments(1).byteRange, Some(ByteRange(900, 1720)))
    assertEquals(media.segments.head.duration.render, "5.005")
    assertEquals(PlaylistParser.parse(PlaylistRenderer.render(parsed.toOption.get)), parsed)

  test("parse multivariant attributes containing quoted commas"):
    val source = """#EXTM3U
      |#EXT-X-VERSION:4
      |#EXT-X-MEDIA:TYPE=AUDIO,GROUP-ID="aac",NAME="English",URI="audio.m3u8",DEFAULT=YES,AUTOSELECT=YES
      |#EXT-X-STREAM-INF:BANDWIDTH=1280000,AVERAGE-BANDWIDTH=1000000,CODECS="avc1.4d401f,mp4a.40.2",RESOLUTION=1280x720,FRAME-RATE=29.97,AUDIO="aac"
      |video.m3u8
      |""".stripMargin
    val parsed = PlaylistParser.parse(source)
    assert(parsed.isRight, parsed.left.toOption.map(_.toString).getOrElse(""))
    val master = parsed.toOption.get.asInstanceOf[Playlist.Multivariant].value
    assertEquals(master.variants.head.codecs, Vector("avc1.4d401f", "mp4a.40.2"))
    assertEquals(PlaylistParser.parse(PlaylistRenderer.render(parsed.toOption.get)), parsed)

  test("reject a missing header with a useful line number"):
    val error = PlaylistParser.parse("#EXT-X-TARGETDURATION:4").left.toOption.get
    assertEquals(error.line, 1)
    assert(error.message.contains("#EXTM3U"))

  test("reject a media segment longer than target duration after rounding"):
    val error = PlaylistParser
      .parse("""#EXTM3U
      |#EXT-X-TARGETDURATION:4
      |#EXTINF:4.6,
      |too-long.ts
      |""".stripMargin)
      .left
      .toOption
      .get
    assert(error.message.contains("rounds above target"))

  test("reject dangling rendition group references"):
    val error = PlaylistParser
      .parse("""#EXTM3U
      |#EXT-X-STREAM-INF:BANDWIDTH=1000,AUDIO="missing"
      |video.m3u8
      |""".stripMargin)
      .left
      .toOption
      .get
    assert(error.message.contains("missing rendition group"))

  test("resolve a UTF-8 BOM and CRLF"):
    val source = "\uFEFF#EXTM3U\r\n#EXT-X-TARGETDURATION:1\r\n#EXTINF:1,\r\na.ts\r\n"
    assert(PlaylistParser.parse(source).isRight)

  test("parse date ranges, start offsets, gaps, and I-frame playlists"):
    val source = """#EXTM3U
      |#EXT-X-VERSION:6
      |#EXT-X-TARGETDURATION:4
      |#EXT-X-START:TIME-OFFSET=-8.5,PRECISE=YES
      |#EXT-X-I-FRAMES-ONLY
      |#EXT-X-DATERANGE:ID="ad-1",CLASS="ads",START-DATE="2026-07-12T10:00:00+09:00",DURATION=4.0,X-CAMPAIGN="summer"
      |#EXTINF:4,
      |#EXT-X-GAP
      |missing.m4s
      |""".stripMargin
    val parsed = PlaylistParser.parse(source)
    assert(parsed.isRight, parsed.left.toOption.map(_.toString).getOrElse(""))
    val media = parsed.toOption.get.asInstanceOf[Playlist.Media].value
    assertEquals(media.start, Some(StartOffset(BigDecimal("-8.5"), precise = true)))
    assert(media.iFramesOnly)
    assert(media.segments.head.gap)
    assertEquals(
      media.segments.head.dateRanges.head.clientAttributes,
      Map("X-CAMPAIGN" -> "summer")
    )
    assertEquals(PlaylistParser.parse(PlaylistRenderer.render(parsed.toOption.get)), parsed)

  test("validate END-ON-NEXT date ranges"):
    val source = """#EXTM3U
      |#EXT-X-VERSION:6
      |#EXT-X-TARGETDURATION:4
      |#EXT-X-DATERANGE:ID="chapter",START-DATE="2026-07-12T10:00:00Z",END-ON-NEXT=YES
      |#EXTINF:4,
      |one.ts
      |""".stripMargin
    val error = PlaylistParser.parse(source).left.toOption.get
    assert(error.message.contains("requires CLASS"))

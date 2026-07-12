//> using target.scope test
package hls.live

import hls.builder.MediaPlaylistBuilder
import hls.model.*
import hls.model.ValueTypes.*

final class LivePlaylistSuite extends munit.FunSuite:
  private def segment(index: Int) = MediaSegment(PlaylistUri.unsafe(s"$index.ts"), Duration.unsafe(2))

  test("a bounded live window advances media sequence"):
    val live = LivePlaylist.create(targetDurationSeconds = 2, windowSize = 3).toOption.get
    (0 until 5).foreach(index => live.append(segment(index)))
    assertEquals(live.snapshot.mediaSequence.value, 2L)
    assertEquals(live.snapshot.segments.map(_.uri.toString), Vector("2.ts", "3.ts", "4.ts"))
    assert(live.end().ended)

  test("builder infers the compatibility version for fMP4"):
    val fmp4 = segment(0).copy(initializationMap = Some(InitializationMap(PlaylistUri.unsafe("init.mp4"))))
    val playlist = MediaPlaylistBuilder.create(2).toOption.get.add(fmp4).asVod.build.toOption.get
    assertEquals(playlist.version, Some(6))

  test("builder accumulates semantic validation"):
    val result = MediaPlaylistBuilder.create(2).toOption.get.add(segment(0).copy(duration = Duration.unsafe(3))).asVod.build
    assert(result.isLeft)
    assert(result.left.toOption.get.exists(_.contains("target duration")))

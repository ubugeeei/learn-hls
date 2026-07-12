package examples.steps

import hls.live.LivePlaylist
import hls.model.*
import hls.model.ValueTypes.*
import hls.render.PlaylistRenderer

/** Chapter 4 checkpoint: observe a live window and its sequence number. */
object Step04LiveWindow:
  @main def simulateLive(): Unit =
    val live = LivePlaylist.create(targetDurationSeconds = 2, windowSize = 3).toOption.get
    (0 until 5).foreach: index =>
      live.append(MediaSegment(PlaylistUri.unsafe(s"segment$index.ts"), Duration.unsafe(2)))
      println(s"--- after segment $index ---")
      print(PlaylistRenderer.renderMedia(live.snapshot))


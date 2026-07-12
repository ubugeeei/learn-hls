package examples.steps

import hls.builder.MediaPlaylistBuilder
import hls.model.*
import hls.model.ValueTypes.*
import hls.render.PlaylistRenderer

/** Chapter 2 checkpoint: replace protocol-shaped strings with domain values. */
object Step02TypedPlaylist:
  val playlist: MediaPlaylist = MediaPlaylistBuilder
    .create(4)
    .toOption
    .get
    .add(MediaSegment(PlaylistUri.unsafe("segment0.ts"), Duration.unsafe(4)))
    .asVod
    .build
    .toOption
    .get

  @main def printTypedPlaylist(): Unit = print(PlaylistRenderer.renderMedia(playlist))

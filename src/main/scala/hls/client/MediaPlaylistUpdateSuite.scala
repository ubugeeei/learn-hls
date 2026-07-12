//> using target.scope test
package hls.client

import hls.model.*
import hls.model.ValueTypes.*

final class MediaPlaylistUpdateSuite extends munit.FunSuite:
  private def segment(index: Int) =
    MediaSegment(PlaylistUri.unsafe(s"$index.ts"), Duration.unsafe(4))

  private def playlist(sequence: Long, indexes: Int*) = MediaPlaylist(
    version = None,
    targetDurationSeconds = 4,
    mediaSequence = MediaSequence.unsafe(sequence),
    segments = indexes.toVector.map(segment)
  )

  test("reconciliation scenarios are declared as snapshot pairs"):
    final case class UpdateCase(
        name: String,
        previous: MediaPlaylist,
        current: MediaPlaylist,
        expected: MediaPlaylistUpdate
    )
    val cases = Vector(
      UpdateCase("unchanged", playlist(0, 0, 1), playlist(0, 0, 1), MediaPlaylistUpdate.Unchanged),
      UpdateCase(
        "append",
        playlist(0, 0, 1),
        playlist(0, 0, 1, 2),
        MediaPlaylistUpdate.Advanced(Vector(segment(2)), 0)
      ),
      UpdateCase(
        "slide",
        playlist(0, 0, 1, 2),
        playlist(1, 1, 2, 3),
        MediaPlaylistUpdate.Advanced(Vector(segment(3)), 1)
      ),
      UpdateCase("rewind", playlist(5, 5, 6), playlist(0, 0, 1), MediaPlaylistUpdate.Rewound(5, 0)),
      UpdateCase(
        "changed overlap",
        playlist(0, 0, 1),
        playlist(1, 99),
        MediaPlaylistUpdate.Inconsistent(1, "1.ts", "99.ts")
      )
    )
    cases.foreach: example =>
      assertEquals(
        MediaPlaylistReconciler.reconcile(example.previous, example.current),
        example.expected,
        clues(example.name)
      )

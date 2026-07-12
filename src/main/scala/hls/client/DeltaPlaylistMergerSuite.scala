//> using target.scope test
package hls.client

import hls.model.*
import hls.model.ValueTypes.*
import java.time.OffsetDateTime

final class DeltaPlaylistMergerSuite extends munit.FunSuite:
  private def segment(index: Long, ranges: Vector[DateRange] = Vector.empty) =
    MediaSegment(
      PlaylistUri.unsafe(s"$index.m4s"),
      Duration.unsafe(4),
      dateRanges = ranges
    )

  test("restore skipped sequences and remove announced date ranges"):
    val oldRange = DateRange("old-ad", OffsetDateTime.parse("2026-07-12T12:00:00Z"))
    val previous = MediaPlaylist(
      version = Some(10),
      targetDurationSeconds = 4,
      mediaSequence = MediaSequence.unsafe(100),
      segments = Vector(segment(100, Vector(oldRange)), segment(101), segment(102))
    )
    val delta = MediaPlaylist(
      version = Some(10),
      targetDurationSeconds = 4,
      mediaSequence = MediaSequence.unsafe(100),
      segments = Vector(segment(102), segment(103)),
      skip = Some(PlaylistSkip(2, Vector("old-ad")))
    )

    val merged = DeltaPlaylistMerger.merge(previous, delta).toOption.get

    assertEquals(
      merged.segments.map(_.uri.toString),
      Vector("100.m4s", "101.m4s", "102.m4s", "103.m4s")
    )
    assertEquals(merged.segments.head.dateRanges, Vector.empty)
    assertEquals(merged.skip, None)

  test("missing history forces a full reload"):
    val previous = MediaPlaylist(
      version = Some(10),
      targetDurationSeconds = 4,
      mediaSequence = MediaSequence.unsafe(101),
      segments = Vector(segment(101))
    )
    val delta = MediaPlaylist(
      version = Some(10),
      targetDurationSeconds = 4,
      mediaSequence = MediaSequence.unsafe(100),
      segments = Vector(segment(102)),
      skip = Some(PlaylistSkip(2))
    )
    assertEquals(
      DeltaPlaylistMerger.merge(previous, delta),
      Left(DeltaMergeError.SequenceChanged(101, 100))
    )

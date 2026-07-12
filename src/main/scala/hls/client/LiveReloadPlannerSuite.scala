//> using target.scope test
package hls.client

import hls.model.*
import hls.model.ValueTypes.*
import java.time.{Duration as JavaDuration, Instant}

final class LiveReloadPlannerSuite extends munit.FunSuite:
  private val started = Instant.parse("2026-07-12T12:00:00Z")

  private def playlist(
      sequence: Long = 10,
      ended: Boolean = false,
      playlistType: Option[PlaylistType] = None
  ) = MediaPlaylist(
    version = None,
    targetDurationSeconds = 6,
    mediaSequence = MediaSequence.unsafe(sequence),
    playlistType = playlistType,
    segments = Vector(10, 11, 12).map(index =>
      MediaSegment(PlaylistUri.unsafe(s"$index.ts"), Duration.unsafe(6))
    ),
    ended = ended
  )

  test("RFC minimum reload intervals are a declarative observation table"):
    val cases = Vector(
      ReloadObservation.Initial   -> JavaDuration.ofSeconds(6),
      ReloadObservation.Changed   -> JavaDuration.ofSeconds(6),
      ReloadObservation.Unchanged -> JavaDuration.ofSeconds(3)
    )
    cases.foreach: (observation, delay) =>
      assertEquals(
        LiveReloadPlanner.next(playlist(), observation, started),
        ReloadDecision.At(started.plus(delay), delay),
        clues(observation)
      )

  test("completed and VOD playlists stop reloads"):
    assert(
      LiveReloadPlanner
        .next(playlist(ended = true), ReloadObservation.Changed, started)
        .isInstanceOf[ReloadDecision.Stop]
    )
    assert(
      LiveReloadPlanner
        .next(playlist(playlistType = Some(PlaylistType.Vod)), ReloadObservation.Changed, started)
        .isInstanceOf[ReloadDecision.Stop]
    )

  test("next-segment cases distinguish availability, waiting, end, and eviction"):
    final case class SegmentCase(last: Option[Long], value: MediaPlaylist, expected: NextSegment)
    val open  = playlist()
    val cases = Vector(
      SegmentCase(None, open, NextSegment.Available(10, open.segments.head)),
      SegmentCase(Some(10), open, NextSegment.Available(11, open.segments(1))),
      SegmentCase(Some(12), open, NextSegment.WaitForReload),
      SegmentCase(Some(12), open.copy(ended = true), NextSegment.Ended),
      SegmentCase(Some(8), open, NextSegment.FellBehind(8, 10))
    )
    cases.foreach(example =>
      assertEquals(
        NextSegmentSelector.select(example.value, example.last),
        example.expected,
        clues(example.last)
      )
    )

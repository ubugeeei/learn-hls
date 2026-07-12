package hls.client

import hls.model.{MediaPlaylist, MediaSegment, PlaylistType}
import hls.model.ValueTypes.*
import java.time.{Duration as JavaDuration, Instant}

/** What the most recent playlist request revealed. */
enum ReloadObservation:
  case Initial, Changed, Unchanged

/** Whether and when another Media Playlist request is permitted. */
enum ReloadDecision:
  case At(earliest: Instant, minimumDelay: JavaDuration)
  case Stop(reason: String)

/**
 * Pure RFC 8216 §6.3.4 reload timing policy.
 *
 * Timing is measured from when the previous request began, not when parsing finished. Initial and
 * changed snapshots wait one target duration; unchanged snapshots wait half a target duration. The
 * planner owns no clock or thread.
 */
object LiveReloadPlanner:
  /** Computes the earliest legal reload instant. */
  def next(
      playlist: MediaPlaylist,
      observation: ReloadObservation,
      requestStartedAt: Instant
  ): ReloadDecision =
    if playlist.ended || playlist.playlistType.contains(PlaylistType.Vod) then
      ReloadDecision.Stop("playlist cannot grow")
    else
      val target = JavaDuration.ofSeconds(playlist.targetDurationSeconds)
      val delay  = observation match
        case ReloadObservation.Unchanged                           => target.dividedBy(2)
        case ReloadObservation.Initial | ReloadObservation.Changed => target
      ReloadDecision.At(requestStartedAt.plus(delay), delay)

/** Result of choosing the next segment after a previously loaded sequence. */
enum NextSegment:
  case Available(sequence: Long, segment: MediaSegment)
  case WaitForReload
  case Ended
  case FellBehind(lastLoaded: Long, oldestAvailable: Long)

/** RFC 8216 §6.3.5 next-segment selection by absolute media sequence. */
object NextSegmentSelector:
  /**
   * Chooses the lowest available sequence greater than `lastLoaded`.
   *
   * `None` means playback has not loaded a segment yet, so the oldest currently advertised segment
   * is selected. This low-level method intentionally leaves initial live-edge/start-offset policy
   * to the caller.
   */
  def select(playlist: MediaPlaylist, lastLoaded: Option[Long]): NextSegment =
    val oldest = playlist.mediaSequence.value
    lastLoaded match
      case Some(last) if last + 1 < oldest => NextSegment.FellBehind(last, oldest)
      case _                               =>
        val desired = lastLoaded.fold(oldest)(_ + 1)
        val index   = desired - oldest
        if index >= 0 && index < playlist.segments.size then
          NextSegment.Available(desired, playlist.segments(index.toInt))
        else if playlist.ended then NextSegment.Ended
        else NextSegment.WaitForReload

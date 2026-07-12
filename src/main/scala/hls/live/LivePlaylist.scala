package hls.live

import hls.model.*
import hls.model.ValueTypes.*
import java.util.concurrent.atomic.AtomicReference

/**
 * Thread-safe sliding window for a live Media Playlist.
 *
 * Removing a segment increments `EXT-X-MEDIA-SEQUENCE`, as required by
 * [[https://www.rfc-editor.org/rfc/rfc8216#section-6.2.2 RFC 8216 §6.2.2]]. An atomic
 * compare-and-set keeps concurrent producers from losing segments.
 */
final class LivePlaylist private (windowSize: Int, state: AtomicReference[MediaPlaylist]):
  /** Returns a coherent immutable snapshot suitable for rendering. */
  def snapshot: MediaPlaylist = state.get()

  /** Atomically appends a segment, evicts overflow, and advances sequence. */
  def append(segment: MediaSegment): MediaPlaylist =
    var updated: MediaPlaylist = state.get()
    var complete               = false
    while !complete do
      val current = state.get()
      val all     = current.segments :+ segment
      val removed = math.max(0, all.size - windowSize)
      updated = current.copy(
        mediaSequence = MediaSequence.unsafe(current.mediaSequence.value + removed),
        segments = all.drop(removed)
      )
      complete = state.compareAndSet(current, updated)
    updated

  /** Atomically publishes `EXT-X-ENDLIST` and returns the final snapshot. */
  def end(): MediaPlaylist =
    state.updateAndGet(_.copy(ended = true))

object LivePlaylist:
  /** Creates an empty live window with a minimum of three segment slots. */
  def create(targetDurationSeconds: Long, windowSize: Int): Either[String, LivePlaylist] =
    for
      _ <- Either.cond(targetDurationSeconds > 0, (), "target duration must be positive")
      _ <- Either.cond(windowSize >= 3, (), "live window should contain at least three segments")
    yield LivePlaylist(
      windowSize,
      AtomicReference(MediaPlaylist(None, targetDurationSeconds, segments = Vector.empty))
    )

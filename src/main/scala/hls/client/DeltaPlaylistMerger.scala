package hls.client

import hls.model.*
import hls.model.ValueTypes.*

/** Failure to reconstruct a full Media Playlist from a Delta Update. */
enum DeltaMergeError:
  case NotADeltaUpdate
  case SequenceChanged(previous: Long, delta: Long)
  case MissingSkippedSequence(sequence: Long)

/** Reconstructs draft-22 Playlist Delta Updates using a previous full snapshot. */
object DeltaPlaylistMerger:
  /**
   * Restores skipped segments by absolute sequence and removes date ranges named by
   * `RECENTLY-REMOVED-DATERANGES`.
   */
  def merge(
      previous: MediaPlaylist,
      delta: MediaPlaylist
  ): Either[DeltaMergeError, MediaPlaylist] =
    delta.skip
      .toRight(DeltaMergeError.NotADeltaUpdate)
      .flatMap: skip =>
        val deltaStart    = delta.mediaSequence.value
        val previousStart = previous.mediaSequence.value
        if deltaStart < previousStart then
          Left(DeltaMergeError.SequenceChanged(previousStart, deltaStart))
        else
          val previousBySequence = previous.segments.zipWithIndex
            .map((segment, index) => (previousStart + index) -> segment)
            .toMap
          val required = (deltaStart until deltaStart + skip.skippedSegments).toVector
          required.find(sequence => !previousBySequence.contains(sequence)) match
            case Some(sequence) => Left(DeltaMergeError.MissingSkippedSequence(sequence))
            case None           =>
              val removedIds = skip.recentlyRemovedDateRangeIds.toSet
              def withoutRemoved(segment: MediaSegment): MediaSegment =
                segment
                  .copy(dateRanges = segment.dateRanges.filterNot(range => removedIds(range.id)))
              val prefix = required.map(sequence => withoutRemoved(previousBySequence(sequence)))
              Right(
                delta.copy(
                  segments = prefix ++ delta.segments.map(withoutRemoved),
                  partialSegments = delta.partialSegments,
                  skip = None
                )
              )

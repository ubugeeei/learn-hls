package hls.client

import hls.model.{MediaPlaylist, MediaSegment}
import hls.model.ValueTypes.*

/** Relationship between consecutive snapshots of the same Media Playlist. */
enum MediaPlaylistUpdate:
  /** The snapshots identify the same sequence range and segments. */
  case Unchanged

  /** A valid forward update, including newly advertised and evicted segments. */
  case Advanced(added: Vector[MediaSegment], removedCount: Long)

  /**
   * The new sequence moved backwards, usually indicating an origin reset or that snapshots from
   * different playlist identities were compared.
   */
  case Rewound(previousSequence: Long, currentSequence: Long)

  /** Overlapping sequence numbers identify different segment URIs. */
  case Inconsistent(sequence: Long, previousUri: String, currentUri: String)

/** Reconciles immutable live Media Playlist snapshots by media sequence. */
object MediaPlaylistReconciler:
  /** Compares snapshots without relying on local vector indexes. */
  def reconcile(previous: MediaPlaylist, current: MediaPlaylist): MediaPlaylistUpdate =
    val previousStart = previous.mediaSequence.value
    val currentStart  = current.mediaSequence.value
    if currentStart < previousStart then MediaPlaylistUpdate.Rewound(previousStart, currentStart)
    else
      val previousBySequence = previous.segments.zipWithIndex
        .map((segment, index) => (previousStart + index) -> segment)
        .toMap
      val currentBySequence = current.segments.zipWithIndex
        .map((segment, index) => (currentStart + index) -> segment)
        .toMap
      val mismatch = previousBySequence.keySet
        .intersect(currentBySequence.keySet)
        .toVector
        .sorted
        .collectFirst:
          case sequence
              if previousBySequence(sequence).uri.toString != currentBySequence(
                sequence
              ).uri.toString =>
            MediaPlaylistUpdate.Inconsistent(
              sequence,
              previousBySequence(sequence).uri.toString,
              currentBySequence(sequence).uri.toString
            )
      mismatch.getOrElse:
        val added = currentBySequence.toVector
          .filterNot((sequence, _) => previousBySequence.contains(sequence))
          .sortBy(_._1)
          .map(_._2)
        val removed = previousBySequence.keys.count(_ < currentStart).toLong
        if added.isEmpty && removed == 0 && previous.ended == current.ended then
          MediaPlaylistUpdate.Unchanged
        else MediaPlaylistUpdate.Advanced(added, removed)

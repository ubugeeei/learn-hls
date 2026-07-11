package hls.builder

import hls.model.*
import hls.model.ValueTypes.*
import hls.validation.PlaylistValidator

/** Immutable, fluent construction of valid Media Playlists.
  *
  * State transitions return a new builder, making a partially assembled
  * playlist safe to share. Call [[build]] to run all RFC semantic checks.
  */
final case class MediaPlaylistBuilder private (
    targetDurationSeconds: Long,
    segments: Vector[MediaSegment],
    version: Option[Int],
    mediaSequence: MediaSequence,
    playlistType: Option[PlaylistType],
    independentSegments: Boolean,
    ended: Boolean
):
  def add(segment: MediaSegment): MediaPlaylistBuilder = copy(segments = segments :+ segment)
  def withVersion(value: Int): MediaPlaylistBuilder = copy(version = Some(value))
  def startingAt(value: MediaSequence): MediaPlaylistBuilder = copy(mediaSequence = value)
  def asEvent: MediaPlaylistBuilder = copy(playlistType = Some(PlaylistType.Event))
  def asVod: MediaPlaylistBuilder = copy(playlistType = Some(PlaylistType.Vod), ended = true)
  def withIndependentSegments: MediaPlaylistBuilder = copy(independentSegments = true)
  def end: MediaPlaylistBuilder = copy(ended = true)

  /** Returns every validation failure, rather than failing fast. */
  def build: Either[Vector[String], MediaPlaylist] =
    val inferred = version.orElse:
      val provisional = toPlaylist(None)
      PlaylistValidator.minimumVersion(provisional)
    val playlist = toPlaylist(inferred)
    PlaylistValidator.validateMedia(playlist) match
      case errors if errors.nonEmpty => Left(errors)
      case _ => Right(playlist)

  private def toPlaylist(effectiveVersion: Option[Int]) =
    MediaPlaylist(effectiveVersion, targetDurationSeconds, mediaSequence, 0, playlistType, independentSegments, segments, ended)

object MediaPlaylistBuilder:
  def create(targetDurationSeconds: Long): Either[String, MediaPlaylistBuilder] =
    Either.cond(targetDurationSeconds > 0, MediaPlaylistBuilder(targetDurationSeconds, Vector.empty, None, MediaSequence.unsafe(0), None, false, false), "target duration must be positive")


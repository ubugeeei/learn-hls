package hls.publish

import hls.model.Playlist
import hls.render.PlaylistRenderer
import hls.validation.PlaylistValidator
import java.nio.ByteBuffer
import java.nio.channels.FileChannel
import java.nio.charset.StandardCharsets
import java.nio.file.{Files, Path, StandardCopyOption, StandardOpenOption}
import java.time.Instant
import scala.util.Try

/** Evidence returned after a playlist has become visible at its destination. */
final case class PublishedPlaylist(path: Path, bytes: Long, publishedAt: Instant)

/** Failure modes that prevent publication. */
enum PublicationError:
  case InvalidPlaylist(errors: Vector[String])
  case InvalidDestination(message: String)
  case IoFailure(path: Path, cause: Throwable)

/**
 * Crash-resistant publisher for rendered playlist snapshots.
 *
 * A snapshot is validated, rendered as UTF-8, written to a temporary file in the destination
 * directory, forced to stable storage, and atomically renamed. Readers therefore observe either the
 * old complete playlist or the new one, never a partially written body. Calls on one publisher are
 * serialized.
 */
final class AtomicPlaylistPublisher private (clock: () => Instant):
  /**
   * Atomically replaces `destination` with a validated playlist.
   *
   * The destination must have a parent directory and an `.m3u8` filename. The method never deletes
   * or modifies the previous snapshot when validation or temporary-file writing fails.
   */
  def publish(playlist: Playlist, destination: Path): Either[PublicationError, PublishedPlaylist] =
    this.synchronized:
      val errors = PlaylistValidator.validate(playlist)
      if errors.nonEmpty then Left(PublicationError.InvalidPlaylist(errors))
      else
        validateDestination(destination).flatMap: target =>
          val bytes = PlaylistRenderer.render(playlist).getBytes(StandardCharsets.UTF_8)
          writeAndMove(target, bytes).map(_ => PublishedPlaylist(target, bytes.length, clock()))

  private def validateDestination(destination: Path): Either[PublicationError, Path] =
    val absolute = destination.toAbsolutePath.normalize
    Option(absolute.getParent) match
      case None => Left(PublicationError.InvalidDestination("destination needs a parent directory"))
      case Some(_) if !absolute.getFileName.toString.toLowerCase.endsWith(".m3u8") =>
        Left(PublicationError.InvalidDestination("destination must use the .m3u8 extension"))
      case Some(parent) if !Files.isDirectory(parent) =>
        Left(PublicationError.InvalidDestination(s"destination directory does not exist: $parent"))
      case Some(_) => Right(absolute)

  private def writeAndMove(target: Path, bytes: Array[Byte]): Either[PublicationError, Unit] =
    val parent    = target.getParent
    val temporary = Try(
      Files.createTempFile(parent, s".${target.getFileName}.", ".tmp")
    ).toEither.left.map(PublicationError.IoFailure(target, _))
    temporary.flatMap: temp =>
      val result = Try:
        val channel =
          FileChannel.open(temp, StandardOpenOption.WRITE, StandardOpenOption.TRUNCATE_EXISTING)
        try
          val buffer = ByteBuffer.wrap(bytes)
          while buffer.hasRemaining do channel.write(buffer)
          channel.force(true)
        finally channel.close()
        Files.move(
          temp,
          target,
          StandardCopyOption.ATOMIC_MOVE,
          StandardCopyOption.REPLACE_EXISTING
        )
        forceDirectory(parent)
      .toEither.left.map(PublicationError.IoFailure(target, _))
      if result.isLeft then Try(Files.deleteIfExists(temp))
      result

  private def forceDirectory(directory: Path): Unit =
    val channel = FileChannel.open(directory, StandardOpenOption.READ)
    try channel.force(true)
    finally channel.close()

object AtomicPlaylistPublisher:
  /** Creates a publisher using the system UTC clock. */
  def create(): AtomicPlaylistPublisher = AtomicPlaylistPublisher(() => Instant.now())

  private[publish] def testing(clock: () => Instant): AtomicPlaylistPublisher =
    AtomicPlaylistPublisher(clock)

//> using target.scope test
package hls.publish

import hls.model.*
import hls.model.ValueTypes.*
import hls.parser.PlaylistParser
import java.nio.file.Files
import java.time.Instant

final class AtomicPlaylistPublisherSuite extends munit.FunSuite:
  private val segment = MediaSegment(PlaylistUri.unsafe("segment.ts"), Duration.unsafe(4))

  test("publish a complete snapshot and atomically replace it"):
    val directory   = Files.createTempDirectory("hls-publisher")
    val destination = directory.resolve("index.m3u8")
    val now         = Instant.parse("2026-07-12T12:00:00Z")
    val publisher   = AtomicPlaylistPublisher.testing(() => now)
    val first       = Playlist.Media(MediaPlaylist(None, 4, segments = Vector(segment)))
    val second      = Playlist.Media(
      MediaPlaylist(
        None,
        4,
        segments = Vector(segment, segment.copy(uri = PlaylistUri.unsafe("next.ts")))
      )
    )

    val initial  = publisher.publish(first, destination).toOption.get
    val replaced = publisher.publish(second, destination).toOption.get

    assertEquals(initial.publishedAt, now)
    assert(replaced.bytes > initial.bytes)
    val parsed = PlaylistParser.parse(Files.readString(destination)).toOption.get
    assertEquals(parsed, second)
    assertEquals(Files.list(directory).filter(_.toString.endsWith(".tmp")).count(), 0L)

  test("validation failure preserves the previously published snapshot"):
    val directory   = Files.createTempDirectory("hls-publisher")
    val destination = directory.resolve("index.m3u8")
    Files.writeString(destination, "previous")
    val invalid = Playlist.Media(
      MediaPlaylist(None, targetDurationSeconds = 0, segments = Vector.empty)
    )

    val result = AtomicPlaylistPublisher.create().publish(invalid, destination)

    assert(result.left.toOption.exists(_.isInstanceOf[PublicationError.InvalidPlaylist]))
    assertEquals(Files.readString(destination), "previous")

  test("destination requirements are declared as a case table"):
    final case class DestinationCase(name: String, expectedFragment: String)
    val root  = Files.createTempDirectory("hls-publisher-destinations")
    val cases = Vector(
      DestinationCase("playlist.txt", ".m3u8 extension"),
      DestinationCase("missing/index.m3u8", "does not exist")
    )
    val valid = Playlist.Media(MediaPlaylist(None, 4, segments = Vector(segment)))

    cases.foreach: example =>
      val error = AtomicPlaylistPublisher
        .create()
        .publish(valid, root.resolve(example.name))
        .left
        .toOption
        .get
      val message = error.asInstanceOf[PublicationError.InvalidDestination].message
      assert(message.contains(example.expectedFragment), clues(example))

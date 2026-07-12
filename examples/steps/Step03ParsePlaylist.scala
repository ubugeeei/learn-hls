package examples.steps

import hls.parser.PlaylistParser
import hls.render.PlaylistRenderer

/** Chapter 3 checkpoint: parse untrusted playlist text and canonicalize it. */
object Step03ParsePlaylist:
  @main def parsePlaylist(path: String): Unit =
    val source = java.nio.file.Files.readString(java.nio.file.Path.of(path))
    PlaylistParser.parse(source) match
      case Left(error) => Console.err.println(error); sys.exit(1)
      case Right(playlist) => print(PlaylistRenderer.render(playlist))


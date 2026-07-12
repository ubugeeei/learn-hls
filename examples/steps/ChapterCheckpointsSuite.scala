//> using target.scope test
package examples.steps

import hls.parser.PlaylistParser

/** Keeps the source checkpoint shown by the book executable in CI. */
final class ChapterCheckpointsSuite extends munit.FunSuite:
  test("the hand-written one-segment checkpoint is valid"):
    assert(PlaylistParser.parse(Step01OneSegment.playlist).isRight)

  test("the typed checkpoint has the same segment"):
    assertEquals(Step02TypedPlaylist.playlist.segments.head.uri.toString, "segment0.ts")

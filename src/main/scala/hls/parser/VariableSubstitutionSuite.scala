//> using target.scope test
package hls.parser

import hls.model.Playlist
import hls.model.ValueTypes.*
import java.net.URI
import munit.FunSuite

/** Declarative examples for HLS 2 variable scope and substitution rules. */
final class VariableSubstitutionSuite extends FunSuite:
  test("a preceding NAME definition expands URI and quoted-string values exactly once"):
    val source =
      """#EXTM3U
        |#EXT-X-DEFINE:NAME="host",VALUE="cdn.example.com"
        |#EXT-X-DEFINE:NAME="nested",VALUE="{$host}"
        |#EXT-X-TARGETDURATION:4
        |#EXT-X-KEY:METHOD=AES-128,URI="https://{$host}/key"
        |#EXTINF:4,
        |https://{$host}/{$nested}/segment.ts
        |#EXT-X-ENDLIST
        |""".stripMargin

    val expanded = VariableSubstitution
      .expand(source.linesIterator.toVector, VariableContext())
      .toOption
      .get
      .lines

    assert(expanded.contains("https://cdn.example.com/{$host}/segment.ts"))
    assert(expanded.contains("#EXT-X-KEY:METHOD=AES-128,URI=\"https://cdn.example.com/key\""))

  test("QUERYPARAM uses the final playlist URI and percent-decodes its value"):
    val source =
      """#EXTM3U
        |#EXT-X-DEFINE:QUERYPARAM="token"
        |#EXT-X-TARGETDURATION:4
        |#EXTINF:4,
        |segment.ts?auth={$token}
        |#EXT-X-ENDLIST
        |""".stripMargin

    val context = VariableContext(Some(URI.create("https://example.com/live.m3u8?token=a%2Fb")))
    val media   =
      PlaylistParser.parse(source, context).toOption.get.asInstanceOf[Playlist.Media].value

    assertEquals(media.segments.head.uri.uri.toString, "segment.ts?auth=a/b")

  test("IMPORT is explicit and missing imported values fail"):
    val source =
      """#EXTM3U
        |#EXT-X-DEFINE:IMPORT="cdn"
        |#EXT-X-TARGETDURATION:4
        |#EXTINF:4,
        |https://{$cdn}/segment.ts
        |""".stripMargin

    assert(PlaylistParser.parse(source).left.toOption.get.message.contains("undefined import"))
    assert(
      PlaylistParser
        .parse(source, VariableContext(imported = Map("cdn" -> "media.example.com")))
        .isRight
    )

  test("detailed parsing exports a Multivariant variable environment for an explicit child load"):
    val multivariant =
      """#EXTM3U
        |#EXT-X-DEFINE:NAME="cdn",VALUE="media.example.com"
        |#EXT-X-STREAM-INF:BANDWIDTH=100000
        |https://{$cdn}/media.m3u8
        |""".stripMargin

    val result = PlaylistParser.parseWithVariables(multivariant).toOption.get

    assertEquals(result.definedVariables, Map("cdn" -> "media.example.com"))

  test("forward references, duplicate names, and substitutions in decimal values fail or stay raw"):
    val forward =
      """#EXTM3U
        |#EXT-X-TARGETDURATION:4
        |#EXTINF:4,
        |{$later}.ts
        |#EXT-X-DEFINE:NAME="later",VALUE="segment"
        |""".stripMargin
    val duplicate =
      """#EXTM3U
        |#EXT-X-DEFINE:NAME="x",VALUE="one"
        |#EXT-X-DEFINE:NAME="x",VALUE="two"
        |#EXT-X-TARGETDURATION:4
        |""".stripMargin
    val decimal =
      """#EXTM3U
        |#EXT-X-DEFINE:NAME="duration",VALUE="4"
        |#EXT-X-TARGETDURATION:{$duration}
        |""".stripMargin

    assert(PlaylistParser.parse(forward).left.toOption.get.message.contains("undefined variable"))
    assert(PlaylistParser.parse(duplicate).left.toOption.get.message.contains("duplicate variable"))
    assert(
      PlaylistParser.parse(decimal).left.toOption.get.message.contains("invalid target duration")
    )

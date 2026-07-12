//> using target.scope test
package hls.client

import hls.model.*
import hls.model.ValueTypes.*

final class VariantSelectorSuite extends munit.FunSuite:
  private def variant(name: String, bandwidth: Long, codec: String, width: Int, height: Int) =
    Variant(
      uri = PlaylistUri.unsafe(s"$name.m3u8"),
      bandwidth = Bandwidth.unsafe(bandwidth),
      codecs = Vector(codec),
      resolution = Resolution.from(width, height).toOption
    )

  private val variants = Vector(
    variant("360p", 800_000, "avc1.4d401e", 640, 360),
    variant("720p", 2_000_000, "avc1.4d401f", 1280, 720),
    variant("1080p", 5_000_000, "hvc1.1.6.L93", 1920, 1080)
  )

  test("selection scenarios form a declarative capability table"):
    final case class SelectionCase(
        name: String,
        estimate: Long,
        codecs: Set[String],
        maximumWidth: Option[Int],
        expected: Option[String]
    )
    val cases = Vector(
      SelectionCase("limited network", 1_200_000, Set("avc1"), None, Some("360p.m3u8")),
      SelectionCase("adequate AVC network", 3_000_000, Set("avc1"), None, Some("720p.m3u8")),
      SelectionCase("HEVC unavailable", 8_000_000, Set("avc1"), None, Some("720p.m3u8")),
      SelectionCase(
        "display limited",
        8_000_000,
        Set("avc1", "hvc1"),
        Some(1280),
        Some("720p.m3u8")
      ),
      SelectionCase("nothing affordable", 500_000, Set("avc1"), None, None)
    )
    cases.foreach: example =>
      val capabilities = PlaybackCapabilities(
        estimatedBitsPerSecond = example.estimate,
        supportedCodecPrefixes = example.codecs,
        maximumWidth = example.maximumWidth
      )
      val selected = VariantSelector.select(variants, capabilities).map(_.uri.toString)
      assertEquals(selected, example.expected, clues(example))

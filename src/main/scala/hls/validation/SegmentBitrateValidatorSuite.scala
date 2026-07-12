//> using target.scope test
package hls.validation

import hls.model.*
import hls.model.ValueTypes.*
import munit.FunSuite

/** Executable examples for the media-dependent `EXT-X-BITRATE` tolerance rule. */
final class SegmentBitrateValidatorSuite extends FunSuite:
  private def playlist(declared: Long) = MediaPlaylist(
    version = None,
    targetDurationSeconds = 4,
    segments = Vector(
      MediaSegment(
        PlaylistUri.unsafe("segment.ts"),
        Duration.unsafe(BigDecimal(4)),
        bitrateKbps = Some(declared)
      )
    ),
    ended = true
  )

  test("accept rates at both inclusive tolerance boundaries"):
    // 500,000 bytes × 8 / 4 seconds / 1,000 = exactly 1,000 kbps.
    assertEquals(SegmentBitrateValidator.validate(playlist(900), _ => Some(500000)), Vector.empty)
    assertEquals(SegmentBitrateValidator.validate(playlist(1100), _ => Some(500000)), Vector.empty)

  test("report measured rate and segment identity outside the tolerance"):
    val errors = SegmentBitrateValidator.validate(playlist(899), _ => Some(500000))

    assertEquals(
      errors,
      Vector(SegmentBitrateValidator.Error.OutsideTolerance(0, 899, BigDecimal(1000)))
    )

  test("missing resource metadata is explicit instead of silently skipped"):
    assertEquals(
      SegmentBitrateValidator.validate(playlist(1000), _ => None),
      Vector(SegmentBitrateValidator.Error.MissingSize(0, "segment.ts"))
    )

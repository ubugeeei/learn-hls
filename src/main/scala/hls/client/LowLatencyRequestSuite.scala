//> using target.scope test
package hls.client

import java.net.URI

final class LowLatencyRequestSuite extends munit.FunSuite:
  test("delivery directives preserve existing query and fragment"):
    val directives = DeliveryDirectives
      .create(Some(273), Some(2), Some(SkipRequest.V2))
      .toOption
      .get
    val result = directives.applyTo(URI.create("https://media.example/live.m3u8?token=abc#edge"))
    assertEquals(
      result.toString,
      "https://media.example/live.m3u8?token=abc&_HLS_msn=273&_HLS_part=2&_HLS_skip=v2#edge"
    )

  test("invalid directive combinations form a declarative table"):
    final case class InvalidCase(msn: Option[Long], part: Option[Int], skip: Option[SkipRequest])
    val cases = Vector(
      InvalidCase(None, None, None),
      InvalidCase(None, Some(0), None),
      InvalidCase(Some(-1), None, None),
      InvalidCase(Some(1), Some(-1), None)
    )
    cases.foreach(example =>
      assert(
        DeliveryDirectives.create(example.msn, example.part, example.skip).isLeft,
        clues(example)
      )
    )

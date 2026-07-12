//> using target.scope test
package hls.media

final class WebVttInspectorSuite extends munit.FunSuite:
  test("extract an HLS timestamp map and cues"):
    val source = """WEBVTT
      |X-TIMESTAMP-MAP=LOCAL:00:00:00.000,MPEGTS:900000
      |
      |cue-1
      |00:00:01.000 --> 00:00:03.500
      |Hello
      |""".stripMargin
    assertEquals(
      WebVttInspector.inspect(source),
      Right(WebVttReport(1, Some("00:00:00.000"), Some(900000)))
    )

  test("report malformed WebVTT as declarative cases"):
    final case class InvalidCase(source: String, expected: WebVttError)
    val cases = Vector(
      InvalidCase("not vtt", WebVttError.MissingHeader),
      InvalidCase("WEBVTT\n", WebVttError.NoCues),
      InvalidCase("WEBVTT\n00:broken --> 00:also-broken\n", WebVttError.InvalidCueTiming(2)),
      InvalidCase(
        "WEBVTT\nX-TIMESTAMP-MAP=LOCAL:nope,MPEGTS:x\n00:00:00.000 --> 00:00:01.000\ntext\n",
        WebVttError.InvalidTimestampMap(2)
      )
    )
    cases.foreach: example =>
      val errors = WebVttInspector.inspect(example.source).left.toOption.get
      assert(errors.contains(example.expected), clues(example, errors))

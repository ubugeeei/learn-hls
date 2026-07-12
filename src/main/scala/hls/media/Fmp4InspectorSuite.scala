//> using target.scope test
package hls.media

import java.nio.ByteBuffer
import java.nio.charset.StandardCharsets

final class Fmp4InspectorSuite extends munit.FunSuite:
  private def box(name: String, payload: Array[Byte] = Array.emptyByteArray): Array[Byte] =
    ByteBuffer
      .allocate(8 + payload.length)
      .putInt(8 + payload.length)
      .put(name.getBytes(StandardCharsets.US_ASCII))
      .put(payload)
      .array()

  private def fullBoxPayload(length: Int): Array[Byte] = Array.fill[Byte](length)(0)

  private def initialization(trackId: Int = 1, sampleCount: Int = 0): Array[Byte] =
    val ftyp = box("ftyp", "iso6".getBytes(StandardCharsets.US_ASCII) ++ Array.fill[Byte](4)(0))
    val mvhdPayload = fullBoxPayload(20)
    val tkhdPayload = fullBoxPayload(24)
    ByteBuffer.wrap(tkhdPayload, 12, 4).putInt(trackId)
    val stszPayload = fullBoxPayload(12)
    ByteBuffer.wrap(stszPayload, 8, 4).putInt(sampleCount)
    val trak = box("trak", box("tkhd", tkhdPayload) ++ box("stbl", box("stsz", stszPayload)))
    ftyp ++ box("moov", box("mvhd", mvhdPayload) ++ trak ++ box("mvex"))

  private def fragment(
      trackId: Int = 1,
      includeTfdt: Boolean = true,
      absoluteOffset: Boolean = false
  ): Array[Byte] =
    val tfhdPayload = fullBoxPayload(8)
    if absoluteOffset then tfhdPayload(3) = 1
    ByteBuffer.wrap(tfhdPayload, 4, 4).putInt(trackId)
    val trafPayload = box("tfhd", tfhdPayload) ++
      Option.when(includeTfdt)(box("tfdt", fullBoxPayload(12))).getOrElse(Array.emptyByteArray) ++
      box("trun", fullBoxPayload(8))
    box("styp") ++ box("moof", box("traf", trafPayload)) ++ box("mdat")

  test("validate nested initialization and fragment requirements"):
    val init   = initialization()
    val media  = fragment()
    val report = Fmp4Inspector.mediaSegment(media, Some(init)).toOption.get
    assertEquals(
      Fmp4Inspector.initialization(init).toOption.get.map(_.boxType),
      Vector("ftyp", "moov")
    )
    assertEquals(report.map(_.boxType), Vector("styp", "moof", "mdat"))
    assertEquals(report.find(_.boxType == "moof").get.descendants("tfdt").size, 1)

  test("profile failures are a declarative fragment table"):
    final case class InvalidCase(
        name: String,
        media: Array[Byte],
        init: Option[Array[Byte]],
        expected: Fmp4Error => Boolean
    )
    val cases = Vector(
      InvalidCase(
        "missing mdat",
        box("moof", box("traf")),
        None,
        _ == Fmp4Error.MissingRequiredBox("mdat")
      ),
      InvalidCase(
        "mdat before moof",
        box("mdat") ++ box("moof", box("traf")),
        None,
        _.isInstanceOf[Fmp4Error.UnexpectedBoxOrder]
      ),
      InvalidCase(
        "missing tfdt",
        fragment(includeTfdt = false),
        None,
        _ == Fmp4Error.MissingChild("traf", "tfdt")
      ),
      InvalidCase(
        "track mismatch",
        fragment(trackId = 2),
        Some(initialization(trackId = 1)),
        _.isInstanceOf[Fmp4Error.TrackIdMismatch]
      ),
      InvalidCase(
        "absolute offset",
        fragment(absoluteOffset = true),
        None,
        _ == Fmp4Error.AbsoluteDataOffset(1)
      )
    )
    cases.foreach: example =>
      val errors = Fmp4Inspector.mediaSegment(example.media, example.init).left.toOption.get
      assert(errors.exists(example.expected), clues(example.name, errors))

  test("initialization requirements and malformed box bounds are reported"):
    val nonZeroSamples =
      Fmp4Inspector.initialization(initialization(sampleCount = 1)).left.toOption.get
    assert(nonZeroSamples.contains(Fmp4Error.NonZeroSampleCount(1)))
    val incompatible =
      box("ftyp", "isom".getBytes(StandardCharsets.US_ASCII) ++ Array.fill[Byte](4)(0)) ++
        box("moov", box("trak") ++ box("mvex"))
    assert(
      Fmp4Inspector
        .initialization(incompatible)
        .left
        .toOption
        .get
        .exists(_.isInstanceOf[Fmp4Error.IncompatibleFileType])
    )
    assertEquals(
      Fmp4Inspector.boxes(Array.fill[Byte](7)(0)),
      Left(Vector(Fmp4Error.TruncatedHeader(0)))
    )

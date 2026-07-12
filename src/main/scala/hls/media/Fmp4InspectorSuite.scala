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

  test("recognize initialization sections and media fragments"):
    val initialization = box("ftyp") ++ box("moov")
    val segment        = box("styp") ++ box("moof") ++ box("mdat")
    assertEquals(
      Fmp4Inspector.initialization(initialization).toOption.get.map(_.boxType),
      Vector("ftyp", "moov")
    )
    assertEquals(
      Fmp4Inspector.mediaSegment(segment).toOption.get.map(_.boxType),
      Vector("styp", "moof", "mdat")
    )

  test("structural failures are returned as data"):
    val missing = Fmp4Inspector.mediaSegment(box("moof")).left.toOption.get
    assertEquals(missing, Vector(Fmp4Error.MissingRequiredBox("mdat")))
    val order = Fmp4Inspector.mediaSegment(box("mdat") ++ box("moof")).left.toOption.get
    assertEquals(order, Vector(Fmp4Error.UnexpectedBoxOrder("moof", "mdat")))
    val truncated = Fmp4Inspector.boxes(Array.fill[Byte](7)(0)).left.toOption.get
    assertEquals(truncated, Vector(Fmp4Error.TruncatedHeader(0)))

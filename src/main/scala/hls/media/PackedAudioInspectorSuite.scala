//> using target.scope test
package hls.media

import java.nio.ByteBuffer
import java.nio.charset.StandardCharsets

final class PackedAudioInspectorSuite extends munit.FunSuite:
  test("extract the Apple transport timestamp PRIV frame"):
    val owner = "com.apple.streaming.transportStreamTimestamp".getBytes(StandardCharsets.ISO_8859_1)
    val payload = owner ++ Array[Byte](0) ++ ByteBuffer.allocate(8).putLong(90_000L).array()
    val frame   = "PRIV".getBytes(StandardCharsets.US_ASCII) ++
      ByteBuffer.allocate(4).putInt(payload.length).array() ++ Array[Byte](0, 0) ++ payload
    val size = synchsafe(frame.length)
    val id3  = "ID3".getBytes(StandardCharsets.US_ASCII) ++ Array[Byte](3, 0, 0) ++ size ++ frame
    assertEquals(PackedAudioInspector.inspect(id3), Right(PackedAudioReport(90_000)))

  test("reject missing and truncated ID3 metadata"):
    assertEquals(
      PackedAudioInspector.inspect(Array.emptyByteArray),
      Left(Vector(PackedAudioError.MissingId3))
    )
    val truncated = "ID3".getBytes(StandardCharsets.US_ASCII) ++ Array[Byte](3, 0, 0, 0, 0, 0, 20)
    assertEquals(
      PackedAudioInspector.inspect(truncated),
      Left(Vector(PackedAudioError.TruncatedId3))
    )

  private def synchsafe(value: Int): Array[Byte] = Array(
    ((value >>> 21) & 0x7f).toByte,
    ((value >>> 14) & 0x7f).toByte,
    ((value >>> 7) & 0x7f).toByte,
    (value & 0x7f).toByte
  )

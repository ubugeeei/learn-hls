//> using target.scope test
package hls.media

final class MpegTsInspectorSuite extends munit.FunSuite:
  private def packet(pid: Int, continuity: Int, sync: Int = 0x47): Array[Byte] =
    val bytes = Array.fill[Byte](MpegTsInspector.PacketSize)(0xff.toByte)
    bytes(0) = sync.toByte
    bytes(1) = ((pid >>> 8) & 0x1f).toByte
    bytes(2) = (pid & 0xff).toByte
    bytes(3) = (0x10 | continuity).toByte
    bytes

  test("valid payload packets expose count and PIDs"):
    val bytes  = packet(256, 0) ++ packet(256, 1) ++ packet(257, 9)
    val report = MpegTsInspector.inspect(bytes).toOption.get
    assertEquals(report.packetCount, 3)
    assertEquals(report.packetIdentifiers, Set(256, 257))

  test("invalid transport streams are a declarative byte-case table"):
    final case class InvalidCase(name: String, bytes: Array[Byte], expected: MpegTsError => Boolean)
    val cases = Vector(
      InvalidCase("empty", Array.emptyByteArray, _ == MpegTsError.Empty),
      InvalidCase(
        "misaligned",
        Array.fill[Byte](10)(0),
        _.isInstanceOf[MpegTsError.MisalignedBytes]
      ),
      InvalidCase("sync", packet(256, 0, sync = 0), _.isInstanceOf[MpegTsError.InvalidSyncByte]),
      InvalidCase(
        "continuity",
        packet(256, 0) ++ packet(256, 2),
        _.isInstanceOf[MpegTsError.ContinuityDiscontinuity]
      )
    )
    cases.foreach: example =>
      val errors = MpegTsInspector.inspect(example.bytes).left.toOption.get
      assert(errors.exists(example.expected), clues(example.name, errors))

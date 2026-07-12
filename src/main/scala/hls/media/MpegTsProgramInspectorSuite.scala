//> using target.scope test
package hls.media

final class MpegTsProgramInspectorSuite extends munit.FunSuite:
  private val pmtPid   = 0x100
  private val videoPid = 0x101

  test("extract a single program, stream map, and first PTS"):
    val segment = psiPacket(0, pat(Vector(1 -> pmtPid))) ++
      psiPacket(pmtPid, pmt(program = 1, pcrPid = videoPid, streams = Vector(0x1b -> videoPid))) ++
      pesPacket(videoPid, pts = 90_000)
    val report = MpegTsProgramInspector.inspect(segment).toOption.get.get
    assertEquals(report.programNumber, 1)
    assertEquals(report.pmtPacketIdentifier, pmtPid)
    assertEquals(report.pcrPacketIdentifier, videoPid)
    assertEquals(report.streams, Vector(ElementaryStream(0x1b, videoPid)))
    assertEquals(report.firstPresentationTimestamp, Map(videoPid -> 90_000L))
    assert(report.startsWithPatAndPmt)

  test("PAT and PMT may be supplied by an external map"):
    assertEquals(
      MpegTsProgramInspector.inspect(pesPacket(videoPid, 0), hasExternalMap = true),
      Right(None)
    )
    assertEquals(
      MpegTsProgramInspector.inspect(pesPacket(videoPid, 0)),
      Left(Vector(MpegTsProgramError.MissingPat))
    )

  test("program-level failures are a declarative segment table"):
    final case class InvalidCase(
        name: String,
        bytes: Array[Byte],
        expected: MpegTsProgramError => Boolean
    )
    val goodPmt   = psiPacket(pmtPid, pmt(1, videoPid, Vector(0x1b -> videoPid)))
    val badCrcPat = pat(Vector(1 -> pmtPid)).updated(5, 0.toByte)
    val cases     = Vector(
      InvalidCase(
        "bad PAT CRC",
        psiPacket(0, badCrcPat) ++ goodPmt,
        _.isInstanceOf[MpegTsProgramError.InvalidPsiCrc]
      ),
      InvalidCase(
        "multiple programs",
        psiPacket(0, pat(Vector(1 -> pmtPid, 2 -> 0x110))),
        _.isInstanceOf[MpegTsProgramError.MultiplePrograms]
      ),
      InvalidCase(
        "missing PMT",
        psiPacket(0, pat(Vector(1 -> pmtPid))),
        _ == MpegTsProgramError.MissingPmt(pmtPid)
      ),
      InvalidCase(
        "bad PES prefix",
        psiPacket(0, pat(Vector(1 -> pmtPid))) ++ goodPmt ++ payloadPacket(
          videoPid,
          Array[Byte](9, 9, 9),
          start = true
        ),
        _ == MpegTsProgramError.InvalidPesStartCode(videoPid)
      )
    )
    cases.foreach: example =>
      val errors = MpegTsProgramInspector.inspect(example.bytes).left.toOption.get
      assert(errors.exists(example.expected), clues(example.name, errors))

  private def pat(programs: Vector[(Int, Int)]): Array[Byte] =
    val entries = programs.flatMap: (number, pid) =>
      Vector((number >>> 8).toByte, number.toByte, (0xe0 | (pid >>> 8)).toByte, pid.toByte)
    val sectionLength = 9 + entries.length
    withCrc(
      Array[Byte](
        0x00,
        (0xb0 | (sectionLength >>> 8)).toByte,
        sectionLength.toByte,
        0,
        1,
        0xc1.toByte,
        0,
        0
      ) ++ entries
    )

  private def pmt(program: Int, pcrPid: Int, streams: Vector[(Int, Int)]): Array[Byte] =
    val entries = streams.flatMap: (streamType, pid) =>
      Vector(streamType.toByte, (0xe0 | (pid >>> 8)).toByte, pid.toByte, 0xf0.toByte, 0)
    val sectionLength = 13 + entries.length
    withCrc(
      Array[Byte](
        0x02,
        (0xb0 | (sectionLength >>> 8)).toByte,
        sectionLength.toByte,
        (program >>> 8).toByte,
        program.toByte,
        0xc1.toByte,
        0,
        0,
        (0xe0 | (pcrPid >>> 8)).toByte,
        pcrPid.toByte,
        0xf0.toByte,
        0
      ) ++ entries
    )

  private def withCrc(sectionWithoutCrc: Array[Byte]): Array[Byte] =
    val crc = mpegCrc32(sectionWithoutCrc)
    sectionWithoutCrc ++ Array(
      (crc >>> 24).toByte,
      (crc >>> 16).toByte,
      (crc >>> 8).toByte,
      crc.toByte
    )

  private def mpegCrc32(bytes: Array[Byte]): Long =
    bytes.foldLeft(0xffffffffL): (crc, byte) =>
      (0 until 8).foldLeft(crc ^ ((byte & 0xffL) << 24)): (value, _) =>
        if (value & 0x80000000L) != 0 then ((value << 1) ^ 0x04c11db7L) & 0xffffffffL
        else (value << 1) & 0xffffffffL

  private def psiPacket(pid: Int, section: Array[Byte]): Array[Byte] =
    payloadPacket(pid, Array[Byte](0) ++ section, start = true)

  private def pesPacket(pid: Int, pts: Long): Array[Byte] =
    val encodedPts = Array[Byte](
      (0x21 | ((pts >>> 29) & 0x0e)).toByte,
      (pts >>> 22).toByte,
      (((pts >>> 14) & 0xfe) | 1).toByte,
      (pts >>> 7).toByte,
      (((pts << 1) & 0xfe) | 1).toByte
    )
    val pes = Array[Byte](0, 0, 1, 0xe0.toByte, 0, 0, 0x80.toByte, 0x80.toByte, 5) ++ encodedPts
    payloadPacket(pid, pes, start = true)

  private def payloadPacket(pid: Int, payload: Array[Byte], start: Boolean): Array[Byte] =
    require(payload.length <= 184)
    val packet = Array.fill[Byte](188)(0xff.toByte)
    packet(0) = 0x47
    packet(1) = ((if start then 0x40 else 0) | ((pid >>> 8) & 0x1f)).toByte
    packet(2) = pid.toByte
    packet(3) = 0x10
    payload.copyToArray(packet, 4)
    packet

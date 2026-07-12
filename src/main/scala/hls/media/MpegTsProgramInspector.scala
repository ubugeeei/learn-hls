package hls.media

import scala.collection.mutable.ArrayBuffer

/** One elementary stream declared by an MPEG-TS Program Map Table. */
final case class ElementaryStream(streamType: Int, packetIdentifier: Int)

/** Program-level facts extracted from PAT, PMT, and PES headers. */
final case class MpegTsProgramReport(
    programNumber: Int,
    pmtPacketIdentifier: Int,
    pcrPacketIdentifier: Int,
    streams: Vector[ElementaryStream],
    firstPresentationTimestamp: Map[Int, Long],
    startsWithPatAndPmt: Boolean
)

/** Program-level MPEG-TS validation failure. */
enum MpegTsProgramError:
  case MissingPat
  case MissingPmt(packetIdentifier: Int)
  case InvalidPsiCrc(packetIdentifier: Int)
  case InvalidTable(packetIdentifier: Int, expectedTableId: Int, actualTableId: Int)
  case MultiplePrograms(programNumbers: Vector[Int])
  case TruncatedSection(packetIdentifier: Int)
  case TransportError(packetIndex: Int)
  case InvalidPesStartCode(packetIdentifier: Int)
  case InvalidPts(packetIdentifier: Int)

/**
 * PAT/PMT/PES inspector for MPEG-TS HLS Media Segments.
 *
 * RFC 8216 §3.2 requires a single MPEG-2 Program and either PAT/PMT in each segment or an applied
 * `EXT-X-MAP`. PSI sections are reassembled across TS packets using payload-unit-start and pointer
 * fields. MPEG-2 CRC-32 is checked before tables are trusted. PES headers are inspected for the
 * first PTS per elementary PID; elementary codecs are not decoded.
 */
object MpegTsProgramInspector:
  private val PacketSize = 188

  /**
   * Inspects one complete TS segment.
   *
   * @param hasExternalMap
   *   true only when an `EXT-X-MAP` supplies PAT/PMT initialization externally
   */
  def inspect(
      bytes: Array[Byte],
      hasExternalMap: Boolean = false
  ): Either[Vector[MpegTsProgramError], Option[MpegTsProgramReport]] =
    val packets         = parsePackets(bytes)
    val transportErrors = packets.collect:
      case packet if packet.transportError => MpegTsProgramError.TransportError(packet.index)
    if transportErrors.nonEmpty then Left(transportErrors)
    else
      sectionFor(0, packets) match
        case None if hasExternalMap => Right(None)
        case None                   => Left(Vector(MpegTsProgramError.MissingPat))
        case Some(pat)              => inspectWithPat(pat, packets)

  private final case class Packet(
      index: Int,
      pid: Int,
      payloadUnitStart: Boolean,
      transportError: Boolean,
      payload: Array[Byte]
  )

  private def inspectWithPat(
      pat: Array[Byte],
      packets: Vector[Packet]
  ): Either[Vector[MpegTsProgramError], Option[MpegTsProgramReport]] =
    for
      _        <- validateSection(pat, 0, expectedTableId = 0)
      programs <- parsePrograms(pat)
      _        <- Either.cond(
        programs.size == 1,
        (),
        Vector(MpegTsProgramError.MultiplePrograms(programs.map(_._1)))
      )
      (programNumber, pmtPid) = programs.head
      pmt <- sectionFor(pmtPid, packets).toRight(Vector(MpegTsProgramError.MissingPmt(pmtPid)))
      _   <- validateSection(pmt, pmtPid, expectedTableId = 2)
      (pcrPid, streams) <- parsePmt(pmt, pmtPid)
      timestamps        <- inspectPes(streams, packets)
    yield Some(
      MpegTsProgramReport(
        programNumber = programNumber,
        pmtPacketIdentifier = pmtPid,
        pcrPacketIdentifier = pcrPid,
        streams = streams,
        firstPresentationTimestamp = timestamps,
        startsWithPatAndPmt = packets.take(2).map(_.pid) == Vector(0, pmtPid)
      )
    )

  private def parsePackets(bytes: Array[Byte]): Vector[Packet] =
    bytes
      .grouped(PacketSize)
      .zipWithIndex
      .collect:
        case (raw, index) if raw.length == PacketSize && (raw(0) & 0xff) == 0x47 =>
          val pid               = ((raw(1) & 0x1f) << 8) | (raw(2) & 0xff)
          val adaptationControl = (raw(3) >>> 4) & 0x03
          val payloadStart      = adaptationControl match
            case 1 => 4
            case 3 => 5 + (raw(4) & 0xff)
            case _ => PacketSize
          Packet(
            index = index,
            pid = pid,
            payloadUnitStart = (raw(1) & 0x40) != 0,
            transportError = (raw(1) & 0x80) != 0,
            payload =
              if payloadStart <= PacketSize then raw.drop(payloadStart) else Array.emptyByteArray
          )
      .toVector

  private def sectionFor(pid: Int, packets: Vector[Packet]): Option[Array[Byte]] =
    val collected                   = ArrayBuffer.empty[Byte]
    var expectedLength: Option[Int] = None
    var started                     = false
    packets.iterator
      .filter(_.pid == pid)
      .foreach: packet =>
        var payload = packet.payload
        if packet.payloadUnitStart && payload.nonEmpty then
          val pointer = payload(0) & 0xff
          payload = payload.drop(1 + pointer)
          collected.clear()
          expectedLength = None
          started = true
        if started then
          collected ++= payload
          if expectedLength.isEmpty && collected.size >= 3 then
            expectedLength = Some(3 + (((collected(1) & 0x0f) << 8) | (collected(2) & 0xff)))
    expectedLength.filter(_ <= collected.size).map(length => collected.take(length).toArray)

  private def validateSection(
      section: Array[Byte],
      pid: Int,
      expectedTableId: Int
  ): Either[Vector[MpegTsProgramError], Unit] =
    if section.length < 8 then Left(Vector(MpegTsProgramError.TruncatedSection(pid)))
    else if (section(0) & 0xff) != expectedTableId then
      Left(Vector(MpegTsProgramError.InvalidTable(pid, expectedTableId, section(0) & 0xff)))
    else if mpegCrc32(section) != 0 then Left(Vector(MpegTsProgramError.InvalidPsiCrc(pid)))
    else Right(())

  private def parsePrograms(
      section: Array[Byte]
  ): Either[Vector[MpegTsProgramError], Vector[(Int, Int)]] =
    val entries = section
      .slice(8, section.length - 4)
      .grouped(4)
      .collect:
        case entry if entry.length == 4 =>
          val number = ((entry(0) & 0xff) << 8) | (entry(1) & 0xff)
          val pid    = ((entry(2) & 0x1f) << 8) | (entry(3) & 0xff)
          number -> pid
      .filter(_._1 != 0)
      .toVector
    Either.cond(entries.nonEmpty, entries, Vector(MpegTsProgramError.TruncatedSection(0)))

  private def parsePmt(
      section: Array[Byte],
      pid: Int
  ): Either[Vector[MpegTsProgramError], (Int, Vector[ElementaryStream])] =
    if section.length < 16 then Left(Vector(MpegTsProgramError.TruncatedSection(pid)))
    else
      val pcrPid            = ((section(8) & 0x1f) << 8) | (section(9) & 0xff)
      val programInfoLength = ((section(10) & 0x0f) << 8) | (section(11) & 0xff)
      val end               = section.length - 4
      var offset            = 12 + programInfoLength
      val streams           = Vector.newBuilder[ElementaryStream]
      var valid             = offset <= end
      while valid && offset < end do
        if offset + 5 > end then valid = false
        else
          val streamType = section(offset) & 0xff
          val streamPid  = ((section(offset + 1) & 0x1f) << 8) | (section(offset + 2) & 0xff)
          val infoLength = ((section(offset + 3) & 0x0f) << 8) | (section(offset + 4) & 0xff)
          if offset + 5 + infoLength > end then valid = false
          else
            streams += ElementaryStream(streamType, streamPid)
            offset += 5 + infoLength
      Either.cond(
        valid,
        pcrPid -> streams.result(),
        Vector(MpegTsProgramError.TruncatedSection(pid))
      )

  private def inspectPes(
      streams: Vector[ElementaryStream],
      packets: Vector[Packet]
  ): Either[Vector[MpegTsProgramError], Map[Int, Long]] =
    val errors     = Vector.newBuilder[MpegTsProgramError]
    val timestamps = Map.newBuilder[Int, Long]
    streams.foreach: stream =>
      packets
        .find(packet => packet.pid == stream.packetIdentifier && packet.payloadUnitStart)
        .foreach: packet =>
          parsePts(packet.payload) match
            case Left(error)     => errors += error(stream.packetIdentifier)
            case Right(Some(ts)) => timestamps += stream.packetIdentifier -> ts
            case Right(None)     => ()
    val result = errors.result()
    if result.nonEmpty then Left(result) else Right(timestamps.result())

  private def parsePts(
      payload: Array[Byte]
  ): Either[Int => MpegTsProgramError, Option[Long]] =
    if payload.length < 9 || !payload.take(3).sameElements(Array[Byte](0, 0, 1)) then
      Left(MpegTsProgramError.InvalidPesStartCode(_))
    else
      val ptsPresent = (payload(7) & 0x80) != 0
      if !ptsPresent then Right(None)
      else if payload.length < 14 then Left(MpegTsProgramError.InvalidPts(_))
      else
        val p            = payload.drop(9)
        val markersValid = (p(0) & 1) == 1 && (p(2) & 1) == 1 && (p(4) & 1) == 1
        if !markersValid then Left(MpegTsProgramError.InvalidPts(_))
        else
          val value = ((p(0).toLong & 0x0e) << 29) |
            ((p(1).toLong & 0xff) << 22) |
            ((p(2).toLong & 0xfe) << 14) |
            ((p(3).toLong & 0xff) << 7) |
            ((p(4).toLong & 0xfe) >>> 1)
          Right(Some(value))

  private def mpegCrc32(bytes: Array[Byte]): Long =
    bytes.foldLeft(0xffffffffL): (crc, byte) =>
      (0 until 8).foldLeft(crc ^ ((byte & 0xffL) << 24)): (value, _) =>
        if (value & 0x80000000L) != 0 then ((value << 1) ^ 0x04c11db7L) & 0xffffffffL
        else (value << 1) & 0xffffffffL

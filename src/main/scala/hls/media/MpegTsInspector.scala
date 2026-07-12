package hls.media

/** One structural MPEG-2 Transport Stream validation failure. */
enum MpegTsError:
  case Empty
  case MisalignedBytes(length: Int)
  case InvalidSyncByte(packetIndex: Int, actual: Int)
  case ContinuityDiscontinuity(packetIndex: Int, pid: Int, expected: Int, actual: Int)

/** Facts collected without decoding elementary audio or video payloads. */
final case class MpegTsReport(packetCount: Int, packetIdentifiers: Set[Int])

/**
 * Structural inspector for the MPEG-TS Media Segment requirements in
 * [[https://www.rfc-editor.org/rfc/rfc8216#section-3.2 RFC 8216 §3.2]].
 *
 * It validates 188-byte packet framing, sync bytes, and continuity counters for payload-bearing
 * packets. It does not decode PAT/PMT sections, PES data, codecs, timestamps, or encryption.
 */
object MpegTsInspector:
  val PacketSize: Int = 188

  /** Inspects a complete segment and accumulates every framing/continuity error. */
  def inspect(bytes: Array[Byte]): Either[Vector[MpegTsError], MpegTsReport] =
    if bytes.isEmpty then Left(Vector(MpegTsError.Empty))
    else if bytes.length % PacketSize != 0 then
      Left(Vector(MpegTsError.MisalignedBytes(bytes.length)))
    else
      val errors       = Vector.newBuilder[MpegTsError]
      val identifiers  = Set.newBuilder[Int]
      var continuityBy = Map.empty[Int, Int]
      bytes
        .grouped(PacketSize)
        .zipWithIndex
        .foreach: (packet, index) =>
          val sync = packet(0) & 0xff
          if sync != 0x47 then errors += MpegTsError.InvalidSyncByte(index, sync)
          else
            val pid = ((packet(1) & 0x1f) << 8) | (packet(2) & 0xff)
            identifiers += pid
            val adaptationControl = (packet(3) >>> 4) & 0x03
            val hasPayload        = adaptationControl == 1 || adaptationControl == 3
            val discontinuity     = adaptationControl == 2 || adaptationControl == 3 match
              case true if packet(4) > 0 => (packet(5) & 0x80) != 0
              case _                     => false
            if hasPayload && pid != 0x1fff then
              val actual = packet(3) & 0x0f
              continuityBy
                .get(pid)
                .foreach: previous =>
                  val expected = (previous + 1) & 0x0f
                  if actual != expected && !discontinuity then
                    errors += MpegTsError.ContinuityDiscontinuity(index, pid, expected, actual)
              continuityBy = continuityBy.updated(pid, actual)
      val result = errors.result()
      if result.nonEmpty then Left(result)
      else Right(MpegTsReport(bytes.length / PacketSize, identifiers.result()))

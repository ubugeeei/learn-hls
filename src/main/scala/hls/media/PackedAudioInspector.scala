package hls.media

import java.nio.charset.StandardCharsets

/** ID3/timestamp failure for a packed audio Media Segment. */
enum PackedAudioError:
  case MissingId3
  case TruncatedId3
  case InvalidSynchsafeSize
  case MissingTransportTimestamp
  case InvalidTransportTimestamp

/** HLS packed-audio timestamp in the 33-bit MPEG-2 timestamp domain. */
final case class PackedAudioReport(transportTimestamp: Long)

/**
 * Inspector for the ID3 PRIV timestamp required by
 * [[https://www.rfc-editor.org/rfc/rfc8216#section-3.4 RFC 8216 §3.4]].
 *
 * It parses ID3v2.3/v2.4 frame boundaries only far enough to locate the
 * `com.apple.streaming.transportStreamTimestamp` PRIV owner and its eight-byte payload. It does not
 * decode the following AAC, AC-3, E-AC-3, or MP3 frames.
 */
object PackedAudioInspector:
  private val TimestampOwner = "com.apple.streaming.transportStreamTimestamp"

  /** Extracts the mandatory timestamp from a packed audio segment prefix. */
  def inspect(bytes: Array[Byte]): Either[Vector[PackedAudioError], PackedAudioReport] =
    if bytes.length < 10 || !bytes.take(3).sameElements("ID3".getBytes(StandardCharsets.US_ASCII))
    then Left(Vector(PackedAudioError.MissingId3))
    else
      synchsafe(bytes.slice(6, 10)) match
        case None => Left(Vector(PackedAudioError.InvalidSynchsafeSize))
        case Some(tagSize) if bytes.length < 10 + tagSize =>
          Left(Vector(PackedAudioError.TruncatedId3))
        case Some(tagSize) => findTimestamp(bytes, tagSize)

  private def findTimestamp(
      bytes: Array[Byte],
      tagSize: Int
  ): Either[Vector[PackedAudioError], PackedAudioReport] =
    val version              = bytes(3) & 0xff
    var offset               = 10
    val end                  = 10 + tagSize
    var result: Option[Long] = None
    while offset + 10 <= end && result.isEmpty && bytes(offset) != 0 do
      val frameId   = String(bytes, offset, 4, StandardCharsets.US_ASCII)
      val frameSize =
        if version == 4 then synchsafe(bytes.slice(offset + 4, offset + 8)).getOrElse(-1)
        else java.nio.ByteBuffer.wrap(bytes, offset + 4, 4).getInt
      val payloadStart = offset + 10
      val payloadEnd   = payloadStart + frameSize
      if frameSize < 0 || payloadEnd > end then return Left(Vector(PackedAudioError.TruncatedId3))
      if frameId == "PRIV" then result = parsePriv(bytes.slice(payloadStart, payloadEnd))
      offset = payloadEnd
    result.toRight(Vector(PackedAudioError.MissingTransportTimestamp)).map(PackedAudioReport(_))

  private def parsePriv(payload: Array[Byte]): Option[Long] =
    val zero = payload.indexOf(0)
    if zero < 0 then None
    else
      val owner = String(payload, 0, zero, StandardCharsets.ISO_8859_1)
      val data  = payload.drop(zero + 1)
      Option.when(owner == TimestampOwner && data.length == 8):
        data.foldLeft(0L)((value, byte) => (value << 8) | (byte & 0xffL)) & 0x1ffffffffL

  private def synchsafe(bytes: Array[Byte]): Option[Int] =
    Option.when(bytes.length == 4 && bytes.forall(byte => (byte & 0x80) == 0)):
      bytes.foldLeft(0)((value, byte) => (value << 7) | (byte & 0x7f))

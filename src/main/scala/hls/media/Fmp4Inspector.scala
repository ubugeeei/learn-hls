package hls.media

import java.nio.ByteBuffer
import java.nio.charset.StandardCharsets

/** One bounded ISO Base Media File Format box and its parsed container children. */
final case class IsoBox(
    boxType: String,
    offset: Long,
    size: Long,
    headerSize: Int,
    children: Vector[IsoBox] = Vector.empty
):
  /** Absolute byte offset of this box's payload. */
  def payloadOffset: Long = offset + headerSize

  /** Depth-first descendants having the requested four-character type. */
  def descendants(boxType: String): Vector[IsoBox] =
    children.flatMap(child =>
      Option.when(child.boxType == boxType)(child) ++ child.descendants(boxType)
    )

/** Structural fMP4 parsing or HLS profile failure. */
enum Fmp4Error:
  case TruncatedHeader(offset: Long)
  case InvalidSize(offset: Long, size: Long)
  case BoxExceedsInput(offset: Long, size: Long, inputLength: Long)
  case LimitExceeded(kind: String, maximum: Int)
  case MissingRequiredBox(boxType: String)
  case MissingChild(parentType: String, childType: String)
  case UnexpectedBoxOrder(expectedBefore: String, expectedAfter: String)
  case IncompatibleFileType(brands: Vector[String])
  case NonZeroDuration(boxType: String, duration: Long)
  case NonZeroSampleCount(count: Long)
  case TrackIdMismatch(initialization: Set[Long], fragment: Set[Long])
  case AbsoluteDataOffset(trackId: Long)

/**
 * Bounded recursive ISO BMFF inspector for
 * [[https://www.rfc-editor.org/rfc/rfc8216#section-3.3 RFC 8216 §3.3]].
 *
 * Parsing is limited by depth and total box count. Validation covers compatible `ftyp`, zero
 * initialization durations/sample counts, `trak`/`mvex` order, track-ID agreement, mandatory
 * `tfdt`, and movie-fragment-relative `tfhd`. Codec sample entries and external data-reference
 * tables remain separate work.
 */
object Fmp4Inspector:
  private val Containers = Set("moov", "trak", "mdia", "minf", "stbl", "mvex", "moof", "traf")
  private val MaxDepth   = 16
  private val MaxBoxes   = 10000

  /** Parses a bounded tree of ISO BMFF boxes, including 64-bit sizes. */
  def boxes(bytes: Array[Byte]): Either[Vector[Fmp4Error], Vector[IsoBox]] =
    var count                                                                             = 0
    def parseRange(start: Long, end: Long, depth: Int): Either[Fmp4Error, Vector[IsoBox]] =
      if depth > MaxDepth then Left(Fmp4Error.LimitExceeded("nesting depth", MaxDepth))
      else
        val result                   = Vector.newBuilder[IsoBox]
        var offset                   = start
        var error: Option[Fmp4Error] = None
        while offset < end && error.isEmpty do
          count += 1
          if count > MaxBoxes then error = Some(Fmp4Error.LimitExceeded("box count", MaxBoxes))
          else
            readHeader(bytes, offset, end) match
              case Left(value)                    => error = Some(value)
              case Right((boxType, size, header)) =>
                val payloadStart = offset + header
                val boxEnd       = offset + size
                val children     =
                  if Containers(boxType) then parseRange(payloadStart, boxEnd, depth + 1)
                  else Right(Vector.empty)
                children match
                  case Left(value)   => error = Some(value)
                  case Right(values) =>
                    result += IsoBox(boxType, offset, size, header, values)
                    offset = boxEnd
        error.toLeft(result.result())
    parseRange(0, bytes.length, 0).left.map(Vector(_))

  /** Validates a standalone fMP4 Media Initialization Section. */
  def initialization(bytes: Array[Byte]): Either[Vector[Fmp4Error], Vector[IsoBox]] =
    boxes(bytes).flatMap: parsed =>
      val errors = Vector.newBuilder[Fmp4Error]
      requireTop(parsed, "ftyp", errors)
      requireTop(parsed, "moov", errors)
      for
        ftyp <- parsed.find(_.boxType == "ftyp")
        if !compatibleBrands(bytes, ftyp).exists(isIso6OrCmaf)
      do errors += Fmp4Error.IncompatibleFileType(compatibleBrands(bytes, ftyp))
      parsed
        .find(_.boxType == "moov")
        .foreach: moov =>
          requireChild(moov, "trak", errors)
          requireChild(moov, "mvex", errors)
          val lastTrack = moov.children.lastIndexWhere(_.boxType == "trak")
          val mvex      = moov.children.indexWhere(_.boxType == "mvex")
          if mvex >= 0 && lastTrack >= mvex then
            errors += Fmp4Error.UnexpectedBoxOrder("trak", "mvex")
          moov
            .descendants("mvhd")
            .flatMap(duration(bytes, _))
            .filter(_ != 0)
            .foreach(value => errors += Fmp4Error.NonZeroDuration("mvhd", value))
          moov
            .descendants("tkhd")
            .flatMap(duration(bytes, _))
            .filter(_ != 0)
            .foreach(value => errors += Fmp4Error.NonZeroDuration("tkhd", value))
          moov
            .descendants("stsz")
            .flatMap(sampleCount(bytes, _))
            .filter(_ != 0)
            .foreach(value => errors += Fmp4Error.NonZeroSampleCount(value))
      finish(parsed, errors.result())

  /** Validates one fragment and optionally checks track IDs against its map. */
  def mediaSegment(
      bytes: Array[Byte],
      initializationBytes: Option[Array[Byte]] = None
  ): Either[Vector[Fmp4Error], Vector[IsoBox]] =
    boxes(bytes).flatMap: parsed =>
      val errors = Vector.newBuilder[Fmp4Error]
      requireTop(parsed, "moof", errors)
      requireTop(parsed, "mdat", errors)
      val moofIndex = parsed.indexWhere(_.boxType == "moof")
      val mdatIndex = parsed.indexWhere(_.boxType == "mdat")
      if moofIndex >= 0 && mdatIndex >= 0 && moofIndex >= mdatIndex then
        errors += Fmp4Error.UnexpectedBoxOrder("moof", "mdat")
      val fragmentTrackIds = parsed
        .filter(_.boxType == "moof")
        .flatMap: moof =>
          requireChild(moof, "traf", errors)
          moof.children
            .filter(_.boxType == "traf")
            .flatMap: traf =>
              requireChild(traf, "tfhd", errors)
              requireChild(traf, "tfdt", errors)
              requireChild(traf, "trun", errors)
              traf.children
                .find(_.boxType == "tfhd")
                .flatMap: tfhd =>
                  val id = trackId(bytes, tfhd)
                  if hasBaseDataOffset(bytes, tfhd) then
                    id.foreach(value => errors += Fmp4Error.AbsoluteDataOffset(value))
                  id
        .toSet
      initializationBytes.foreach: init =>
        boxes(init).toOption.foreach: initBoxes =>
          val initializationTrackIds =
            initBoxes.flatMap(_.descendants("tkhd")).flatMap(trackId(init, _)).toSet
          if initializationTrackIds != fragmentTrackIds then
            errors += Fmp4Error.TrackIdMismatch(initializationTrackIds, fragmentTrackIds)
      finish(parsed, errors.result())

  private def readHeader(
      bytes: Array[Byte],
      offset: Long,
      containingEnd: Long
  ): Either[Fmp4Error, (String, Long, Int)] =
    if containingEnd - offset < 8 then Left(Fmp4Error.TruncatedHeader(offset))
    else
      val index    = offset.toInt
      val size32   = Integer.toUnsignedLong(ByteBuffer.wrap(bytes, index, 4).getInt)
      val boxType  = String(bytes, index + 4, 4, StandardCharsets.US_ASCII)
      val extended = size32 == 1
      val header   = if extended then 16 else 8
      if extended && containingEnd - offset < 16 then Left(Fmp4Error.TruncatedHeader(offset))
      else
        val declared =
          if size32 == 0 then containingEnd - offset
          else if extended then ByteBuffer.wrap(bytes, index + 8, 8).getLong
          else size32
        if declared < header then Left(Fmp4Error.InvalidSize(offset, declared))
        else if declared > containingEnd - offset then
          Left(Fmp4Error.BoxExceedsInput(offset, declared, containingEnd))
        else Right((boxType, declared, header))

  private def requireTop(
      boxes: Vector[IsoBox],
      boxType: String,
      errors: scala.collection.mutable.Builder[Fmp4Error, Vector[Fmp4Error]]
  ): Unit =
    if !boxes.exists(_.boxType == boxType) then errors += Fmp4Error.MissingRequiredBox(boxType)

  private def requireChild(
      parent: IsoBox,
      childType: String,
      errors: scala.collection.mutable.Builder[Fmp4Error, Vector[Fmp4Error]]
  ): Unit = if !parent.children.exists(_.boxType == childType) then
    errors += Fmp4Error.MissingChild(parent.boxType, childType)

  private def finish(
      parsed: Vector[IsoBox],
      errors: Vector[Fmp4Error]
  ): Either[Vector[Fmp4Error], Vector[IsoBox]] = Either.cond(errors.isEmpty, parsed, errors)

  private def compatibleBrands(bytes: Array[Byte], ftyp: IsoBox): Vector[String] =
    val start = ftyp.payloadOffset.toInt
    val end   = (ftyp.offset + ftyp.size).toInt
    if end - start < 8 then Vector.empty
    else
      Vector(String(bytes, start, 4, StandardCharsets.US_ASCII)) ++
        (start + 8 until end by 4)
          .filter(_ + 4 <= end)
          .map(index => String(bytes, index, 4, StandardCharsets.US_ASCII))

  private def isIso6OrCmaf(brand: String): Boolean =
    brand.matches("iso[6-9]") || brand.startsWith("cmf")

  private def fullBoxVersion(bytes: Array[Byte], box: IsoBox): Option[Int] =
    Option.when(box.payloadOffset >= 0 && box.payloadOffset < bytes.length):
      bytes(box.payloadOffset.toInt) & 0xff

  private def duration(bytes: Array[Byte], box: IsoBox): Option[Long] =
    fullBoxVersion(bytes, box).flatMap: version =>
      val relative = box.boxType match
        case "mvhd" => Some(if version == 1 then 28 else 16)
        case "tkhd" => Some(if version == 1 then 28 else 20)
        case _      => None
      relative.flatMap(value =>
        unsigned(bytes, box.payloadOffset + value, if version == 1 then 8 else 4)
      )

  private def trackId(bytes: Array[Byte], box: IsoBox): Option[Long] =
    fullBoxVersion(bytes, box).flatMap: version =>
      val relative = box.boxType match
        case "tkhd" => Some(if version == 1 then 20 else 12)
        case "tfhd" => Some(4)
        case _      => None
      relative.flatMap(value => unsigned(bytes, box.payloadOffset + value, 4))

  private def sampleCount(bytes: Array[Byte], box: IsoBox): Option[Long] =
    unsigned(bytes, box.payloadOffset + 8, 4)

  private def hasBaseDataOffset(bytes: Array[Byte], tfhd: IsoBox): Boolean =
    val start = tfhd.payloadOffset.toInt
    if start < 0 || start + 4 > bytes.length then false
    else
      val flags = ((bytes(start + 1) & 0xff) << 16) |
        ((bytes(start + 2) & 0xff) << 8) | (bytes(start + 3) & 0xff)
      (flags & 0x000001) != 0

  private def unsigned(bytes: Array[Byte], offset: Long, length: Int): Option[Long] =
    Option.when(offset >= 0 && offset + length <= bytes.length):
      (0 until length).foldLeft(0L)((value, index) =>
        (value << 8) | (bytes(offset.toInt + index) & 0xffL)
      )

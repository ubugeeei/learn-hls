package hls.model

import hls.model.ValueTypes.*
import java.time.OffsetDateTime

/** Preferred playback offset from the beginning (positive) or end (negative).
  * [[https://www.rfc-editor.org/rfc/rfc8216#section-4.3.5.2 RFC 8216 §4.3.5.2]]
  */
final case class StartOffset(seconds: BigDecimal, precise: Boolean = false)

/** An `EXT-X-DATERANGE` association between a date range and media timeline.
  *
  * Client attributes beginning with `X-` are retained verbatim. SCTE-35 values
  * are represented as hexadecimal strings because interpreting splice commands
  * belongs to the SCTE-35 domain, not the playlist grammar.
  * [[https://www.rfc-editor.org/rfc/rfc8216#section-4.3.2.7.1 RFC 8216 §4.3.2.7.1]]
  */
final case class DateRange(
    id: String,
    startDate: OffsetDateTime,
    className: Option[String] = None,
    endDate: Option[OffsetDateTime] = None,
    duration: Option[Duration] = None,
    plannedDuration: Option[Duration] = None,
    endOnNext: Boolean = false,
    scte35Cmd: Option[String] = None,
    scte35Out: Option[String] = None,
    scte35In: Option[String] = None,
    clientAttributes: Map[String, String] = Map.empty
)


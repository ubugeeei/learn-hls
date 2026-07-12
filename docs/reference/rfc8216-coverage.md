# RFC 8216 coverage matrix

“Supports HLS” is too vague to evaluate. This matrix separates playlist syntax,
semantic validation, client behavior, media-container inspection, transport,
and extensions. The normative baseline is
[RFC 8216](https://www.rfc-editor.org/rfc/rfc8216).

Status meanings:

- **Covered** — represented by a public type, parsed/rendered or executed,
  validated where the RFC defines a local invariant, and tested.
- **Structural** — outer format is inspected, but nested semantics or codecs are
  deliberately not decoded.
- **Boundary** — owned by another system; the library documents the contract.
- **Not covered** — known missing functionality rather than an implied promise.

## Playlist tags

| RFC section | Tag | Status | Implementation |
|---|---|---|---|
| §4.3.1.1 | `EXTM3U` | Covered | Header and UTF-8 checks in `PlaylistParser` |
| §4.3.1.2 | `EXT-X-VERSION` | Covered | Both playlist kinds; minimum Media version validation |
| §4.3.2.1 | `EXTINF` | Covered | Decimal duration, optional title, following-URI state |
| §4.3.2.2 | `EXT-X-BYTERANGE` | Covered | Explicit and inherited offsets; canonical explicit rendering |
| §4.3.2.3 | `EXT-X-DISCONTINUITY` | Covered | One-shot segment boundary state |
| §4.3.2.4 | `EXT-X-KEY` | Covered | `NONE`, `AES-128`, `SAMPLE-AES`, URI, IV, key format/version |
| §4.3.2.5 | `EXT-X-MAP` | Covered | URI and explicit map byte range |
| §4.3.2.6 | `EXT-X-PROGRAM-DATE-TIME` | Covered | Offset date-time parsing and rendering |
| §4.3.2.7 | `EXT-X-DATERANGE` | Covered | Standard attributes, SCTE-35 hex carriage, `X-` client attributes |
| §4.3.3.1 | `EXT-X-TARGETDURATION` | Covered | Required positive integer and rounded-duration validation |
| §4.3.3.2 | `EXT-X-MEDIA-SEQUENCE` | Covered | Refined non-negative value and live-window advancement |
| §4.3.3.3 | `EXT-X-DISCONTINUITY-SEQUENCE` | Covered | Non-negative value |
| §4.3.3.4 | `EXT-X-ENDLIST` | Covered | Live finalization and VOD validation |
| §4.3.3.5 | `EXT-X-PLAYLIST-TYPE` | Covered | EVENT and VOD |
| §4.3.3.6 | `EXT-X-I-FRAMES-ONLY` | Covered | Model, parse, render, version inference |
| §4.3.4.1 | `EXT-X-MEDIA` | Covered | All RFC attributes and type-specific URI/INSTREAM-ID rules |
| §4.3.4.2 | `EXT-X-STREAM-INF` | Covered | All non-deprecated RFC attributes, including explicit captions NONE |
| §4.3.4.3 | `EXT-X-I-FRAME-STREAM-INF` | Covered | All RFC attributes |
| §4.3.4.4 | `EXT-X-SESSION-DATA` | Covered | VALUE/URI exclusivity and duplicate identity validation |
| §4.3.4.5 | `EXT-X-SESSION-KEY` | Covered | Encryption attributes; METHOD=NONE rejection |
| §4.3.5.1 | `EXT-X-INDEPENDENT-SEGMENTS` | Covered | Both playlist kinds |
| §4.3.5.2 | `EXT-X-START` | Covered | Both playlist kinds, signed offset and PRECISE |

`EXT-X-GAP` is also supported as a version-6 HLS extension commonly present in
current playlists, although it is not one of RFC 8216's original tag sections.
Unknown extension tags are ignored as required for forward-compatible clients.

## Attribute and grammar behavior

| Area | Status | Notes |
|---|---|---|
| UTF-8 | Covered | Malformed UTF-8 rejected by HTTP client; BOM accepted defensively |
| Line endings | Covered | LF, CRLF, and CR normalized |
| Quoted commas | Covered | Attribute tokenizer splits only outside quoted strings |
| Duplicate attributes | Covered | Rejected with source line |
| Decimal integers | Covered | Refined positive/non-negative types by domain |
| Decimal floating point | Covered | `BigDecimal`, avoiding binary floating-point drift |
| Hexadecimal sequences | Covered as text | IV and SCTE-35 shape is carried, not semantically decoded |
| Unknown tags | Covered | Ignored; known implemented tags remain strict |
| Formatting preservation | Not covered | Renderer is canonical, not a source-preserving editor |

## Semantic validation

Covered rules include target-duration rounding, VOD end marker, minimum
compatibility version, rendition group references, DEFAULT/AUTOSELECT,
FORCED/SUBTITLES, closed-caption URI and INSTREAM-ID constraints, subtitle URI,
SESSION-DATA identity, date-range IDs and END-ON-NEXT, and consistent explicit
`CLOSED-CAPTIONS=NONE` use.

Not every authoring recommendation is a local playlist invariant. Codec profile,
measured bitrate tolerance, aligned keyframes, aspect-ratio consistency, and
accessibility quality require inspecting real media or authoring policy. They are
not falsely reported as parser validation.

## Client behavior

| Capability | Status | Implementation |
|---|---|---|
| HTTP load and redirect | Covered | Bounded connect/request timeouts |
| gzip and strict UTF-8 | Covered | Compressed and expanded size limits |
| URI resolution | Covered | Segments, maps, keys, variants, renditions, session data/keys |
| Conditional reload | Covered | ETag precedence, Last-Modified fallback, 304 reuse |
| Snapshot reconciliation | Covered | append, eviction, rewind, overlap inconsistency |
| Variant admission/ranking | Covered | codec, resolution, bandwidth safety fraction |
| Throughput estimation | Boundary | Caller supplies an estimate from its transfer measurements |
| Automatic reload scheduler | Boundary | Caller integrates timing/backoff with its effect system |
| Media decoding/rendering | Boundary | Platform/player decoder responsibility |
| Redundant-stream failover | Not covered | Requires multi-origin health policy |
| AES-128 segment cryptography | Covered | 16-byte key, explicit/derived IV, CBC, PKCS#7 padding |
| SAMPLE-AES cryptography | Not covered | Requires format-specific subsample encryption |

## Media segments

| RFC section | Format | Status | Checked |
|---|---|---|---|
| §3.2 | MPEG-TS | Structural | 188-byte framing, sync byte, PID set, payload continuity counters |
| §3.3 | fragmented MP4 | Structural | top-level box bounds, extended size, `ftyp`/`moov`, `moof` before `mdat` |
| §3.4 | packed audio | Structural | ID3v2 boundary and Apple transport-stream timestamp PRIV frame |
| §3.5 | WebVTT | Structural | UTF-8 text shape, WEBVTT header, cue timing, X-TIMESTAMP-MAP |

Nested MPEG-TS PAT/PMT/PES parsing, PCR/PTS validation, nested MP4 `tfdt` and
track-fragment rules, packed-audio codec frames, the complete WebVTT rendering
model, codec sample validation, and SAMPLE-AES remain missing. The inspectors
therefore say **structurally acceptable**, never “playable.”

## Server and publication

Covered: MIME mapping, GET/HEAD, gzip, single closed/open/suffix byte ranges,
404/405/416, traversal prevention, ETag and Last-Modified, conditional 304,
mutable-playlist versus immutable-segment cache policy, validation before
publication, same-directory temporary write, fsync, and atomic replacement.

Not covered: TLS termination, authentication/authorization, multipart ranges,
distributed locking, object-store conditional writes, CDN invalidation, metrics,
rate limiting, and multi-region replication. These belong to deployment policy,
not a portable filesystem origin.

## HLS versions beyond RFC 8216

Low-Latency HLS (`EXT-X-PART`, server-control, preload hints, rendition reports,
blocking reload, delta updates), content steering, interstitial asset lists,
variable substitution, and newer immersive/spatial attributes are **not
covered**. They belong to the evolving HLS 2 drafts and separate Apple
specifications, not the stable RFC 8216 completeness claim.

This matrix is the release gate: a future README must not claim broader support
unless the corresponding row becomes Covered with source links and tests.

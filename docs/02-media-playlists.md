# 2. Extended M3U and Media Playlists

Every playlist begins with `#EXTM3U`, exactly and on the first non-empty line.
It is UTF-8 text; [RFC 8216 §4.1](https://www.rfc-editor.org/rfc/rfc8216#section-4.1)
forbids a Byte Order Mark, although this parser accepts one defensively.

```m3u8
#EXTM3U
#EXT-X-VERSION:7
#EXT-X-TARGETDURATION:6
#EXT-X-MEDIA-SEQUENCE:42
#EXTINF:5.005,Opening
segment42.m4s
#EXTINF:4.5,
segment43.m4s
#EXT-X-ENDLIST
```

`EXTINF` describes the URI on the following line. Decimal duration matters:
rounding it early causes timeline drift. `Duration` is therefore an opaque
`BigDecimal`, not a `Double`. `EXT-X-TARGETDURATION` is the upper bound after
rounding every segment duration to the nearest integer; see
[RFC 8216 §4.3.3.1](https://www.rfc-editor.org/rfc/rfc8216#section-4.3.3.1).

## Stateful tags

Some tags apply only to the next segment (`EXTINF`, `EXT-X-BYTERANGE`). Others
change state for every segment that follows (`EXT-X-KEY`, `EXT-X-MAP`) until a
new tag replaces them. A useful parser therefore has two pieces of state:

```scala
// one-shot metadata, cleared after consuming a segment URI
Pending(duration, byteRange, discontinuity, programDateTime)

// persistent context copied into each MediaSegment
encryption
initializationMap
```

This is why parsing independent lines with a regular expression is insufficient.
The implementation in `PlaylistParser` performs a single ordered fold and
returns a line-numbered `ParseError` at the first malformed transition.

## Attribute lists

Tags such as `EXT-X-KEY` use comma-separated `NAME=VALUE` attributes, but a
quoted value may itself contain commas:

```m3u8
#EXT-X-KEY:METHOD=SAMPLE-AES,URI="key",KEYFORMATVERSIONS="1,2"
```

The tokenizer tracks whether it is inside quotes. It also rejects duplicate
attribute names, which [RFC 8216 §4.2](https://www.rfc-editor.org/rfc/rfc8216#section-4.2)
forbids.


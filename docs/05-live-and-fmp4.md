# 5. Live windows, encryption, and fMP4

`LivePlaylist` stores an immutable `MediaPlaylist` inside an `AtomicReference`.
Appending is a compare-and-set loop: concurrent producers cannot overwrite one
another, and readers always observe a complete snapshot.

```scala
val live = LivePlaylist.create(targetDurationSeconds = 6, windowSize = 5).toOption.get
live.append(MediaSegment(PlaylistUri.unsafe("42.ts"), Duration.unsafe(6)))
val text = PlaylistRenderer.renderMedia(live.snapshot)
```

Keep at least three target durations available. Remove segments only in playlist
order, increment the media sequence by the number removed, and never mutate a
published segment URI in place. The normative update algorithm is
[RFC 8216 §6.2.2](https://www.rfc-editor.org/rfc/rfc8216#section-6.2.2).

## fMP4 initialization

Fragmented MP4 segments need a Media Initialization Section named by
`EXT-X-MAP`. The map persists until replaced:

```m3u8
#EXT-X-MAP:URI="init.mp4"
#EXTINF:6,
segment42.m4s
```

The requirements for compatible fMP4 fragments and `tfdt` boxes are in
[RFC 8216 §3.3](https://www.rfc-editor.org/rfc/rfc8216#section-3.3), and the tag
is specified in [§4.3.2.5](https://www.rfc-editor.org/rfc/rfc8216#section-4.3.2.5).

## Encryption

`EXT-X-KEY` applies to every subsequent segment until another key tag. `AES-128`
encrypts whole segments with AES-128 CBC and PKCS7 padding. If `IV` is absent,
the media sequence number is encoded as a 128-bit big-endian IV. `SAMPLE-AES`
encrypts media samples according to their container.

Never serve keys beside public segments without authorization. Use TLS, rotate
credentials, and avoid logging key URLs. This library models and renders key
metadata; it intentionally does not perform cryptography. See
[RFC 8216 §4.3.2.4](https://www.rfc-editor.org/rfc/rfc8216#section-4.3.2.4) and
[§10 Security Considerations](https://www.rfc-editor.org/rfc/rfc8216#section-10).


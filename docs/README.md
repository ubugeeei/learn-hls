# Building HTTP Live Streaming with Scala 3

This is a practical, from-first-principles book about implementing the playlist
and delivery layers of HTTP Live Streaming. The chapters are deliberately
ordered: each one adds one concept and connects it to executable code.

1. [The HLS mental model](01-mental-model.md)
2. [Extended M3U and Media Playlists](02-media-playlists.md)
3. [Adaptive bitrate and Multivariant Playlists](03-multivariant-playlists.md)
4. [A typed Scala 3 implementation](04-scala-implementation.md)
5. [Live windows, encryption, and fMP4](05-live-and-fmp4.md)
6. [HTTP delivery and production checks](06-delivery.md)

The normative baseline is [RFC 8216](https://www.rfc-editor.org/rfc/rfc8216).
Apple continues to evolve HLS beyond that RFC; consult the current
[HLS authoring specification](https://developer.apple.com/documentation/http-live-streaming/hls-authoring-specification-for-apple-devices)
when targeting current Apple platforms. This project implements the stable RFC
8216 playlist core, not Low-Latency HLS extensions from the evolving HLS 2
draft.

## What this implementation owns

HLS is a system, not a video codec. This project owns:

- playlist domain models, parsing, validation, and canonical rendering;
- immutable VOD construction and a concurrency-safe live sliding window;
- HTTP delivery with standard MIME types, ranges, and playlist compression.

It does not encode H.264/AAC, cut frames into MPEG-TS, or mux CMAF fragments.
Those operations belong to a media pipeline such as FFmpeg. The boundary is
valuable: feed this library the URIs and measured durations produced by the
media pipeline, and it produces the protocol metadata clients consume.


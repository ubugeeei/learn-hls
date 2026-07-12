# Purpose, audience, and boundaries

Most HLS tutorials teach an FFmpeg command and stop when a player displays a
video. That is useful operational knowledge, but it hides the protocol. Here we
take the opposite route: read the bytes and tags, model their meaning, and
implement the decisions an authoring server must make.

By the end you should be able to:

- explain why HLS has two playlist kinds and several segment formats;
- implement a strict playlist parser without treating it as “CSV with tags”;
- publish VOD, EVENT, and bounded live Media Playlists safely;
- construct rendition groups and variants from measured media properties;
- inspect timestamps and boundaries in MPEG-TS and fragmented MP4;
- reason about reload cadence, caching, encryption state, and failure recovery;
- navigate [RFC 8216](https://www.rfc-editor.org/rfc/rfc8216) and current Apple
  authoring guidance without guessing which statement is normative.

## What “from scratch” means

We implement the HLS protocol layers ourselves: grammar, domain model, state
machines, validation, serialization, publication, and enough container
inspection to verify boundaries. We do not implement H.264, HEVC, AV1, or AAC
encoders. A codec turns pictures and sound into compressed samples; HLS packages
and indexes those samples. Reimplementing a modern codec would obscure the
protocol we are trying to learn.

## Baseline and extensions

The stable baseline is RFC 8216 (August 2017). Apple evolves HLS through the
HLS 2 draft and its
[authoring specification](https://developer.apple.com/documentation/http-live-streaming/hls-authoring-specification-for-apple-devices).
Every chapter labels features as one of:

- **RFC** — required or defined by RFC 8216;
- **current HLS** — from a later draft or Apple specification;
- **implementation policy** — a deliberate choice made by chibihls.

That distinction prevents a common documentation bug: presenting a sensible
local policy as though every conforming HLS implementation must follow it.


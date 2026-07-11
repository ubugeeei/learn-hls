# 1. The HLS mental model

Start with an ordinary HTTP file server. Instead of asking it for one enormous
movie, an HLS client first downloads a small UTF-8 playlist. That playlist names
short media resources called segments. While one segment plays, the client can
download the next. HLS therefore works through ordinary HTTP caches and CDNs.

The protocol has four roles:

1. An encoder compresses samples using a codec such as H.264 and AAC.
2. A segmenter packages independently retrievable intervals as MPEG-TS or fMP4.
3. An origin publishes playlists and segments over HTTP.
4. A client chooses a variant, buffers segments, and switches variants as the
   network changes.

[RFC 8216 §3](https://www.rfc-editor.org/rfc/rfc8216#section-3) defines Media
Segments. A playlist URI can be relative to the playlist, which makes the whole
presentation relocatable between a local server and a CDN.

## Two playlist kinds

A **Media Playlist** is a time-ordered index of segments for one rendition. A
**Multivariant Playlist** (called a Master Playlist in RFC 8216) is a menu of
Media Playlists at different bitrates, resolutions, codecs, or languages.

```text
master.m3u8
  ├── 360p/video.m3u8 ── segment000.ts, segment001.ts, ...
  ├── 720p/video.m3u8 ── segment000.ts, segment001.ts, ...
  └── audio/en.m3u8   ── segment000.aac, segment001.aac, ...
```

A file must not mix tags from both kinds. The rule is normative in
[RFC 8216 §4.3.1.1](https://www.rfc-editor.org/rfc/rfc8216#section-4.3.1.1),
and this library represents the alternatives with a Scala `enum Playlist`.

## VOD, EVENT, and live

- VOD is immutable and has `EXT-X-PLAYLIST-TYPE:VOD` plus `EXT-X-ENDLIST`.
- EVENT grows by appending segments and never removes old ones.
- A live playlist is a moving window. New segments enter at the end and old
  segments leave at the beginning.

When a live server removes a segment it increments `EXT-X-MEDIA-SEQUENCE`.
Otherwise the client may mistake a new segment for an old one. Server update
rules are in [RFC 8216 §6.2.2](https://www.rfc-editor.org/rfc/rfc8216#section-6.2.2).


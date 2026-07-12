# 6. HTTP delivery and production checks

HLS uses ordinary HTTP but details affect interoperability. `HlsFileServer`
provides a local/reference origin:

```scala
val server = HlsFileServer.create(java.nio.file.Path.of("public"), port = 8080)
server.start()
```

It maps `.m3u8` to `application/vnd.apple.mpegurl`, `.ts` to `video/mp2t`,
`.m4s` to `video/iso.segment`, and supports single byte ranges. It compresses
playlists when the client accepts gzip, sets `Vary`, and prevents paths from
escaping the configured root. Apple's
[delivery requirements](https://developer.apple.com/documentation/http-live-streaming/hls-authoring-specification-for-apple-devices)
recommend these MIME types and require playlist gzip for Apple-device authoring.

This server is deliberately small. In production add TLS 1.2+, authorization
for keys and private content, cache policies, observability, rate limiting, and
a CDN. Do not expose filesystem directories as an origin without a path policy.

## End-to-end implementation recipe

1. Encode variants with closed GOPs and keyframes at aligned boundaries.
2. Segment into MPEG-TS or CMAF/fMP4 and record actual segment durations.
3. Build one Media Playlist per rendition; validate target duration and version.
4. Build a Multivariant Playlist using measured peak and average aggregate rates.
5. Render playlists atomically: write a temporary object, then publish/rename it.
6. Serve correct MIME types, gzip playlists, ranges for media, and TLS.
7. Test parse/render round trips and malformed input in CI.
8. Play under throttling, packet loss, seek, language switch, and live-edge cases.
9. Run Apple's `mediastreamvalidator` and `hlsreport` on representative output.

The RFC's authoring responsibilities are summarized in
[RFC 8216 §6.2](https://www.rfc-editor.org/rfc/rfc8216#section-6.2). Apple's
[testing appendix](https://developer.apple.com/documentation/http-live-streaming/hls-authoring-specification-for-apple-devices-appendixes/)
describes validator tooling and measured bitrate tolerances. Automated playlist
tests cannot replace inspection of encoded media timestamps and visual quality.

## Useful test commands

```bash
sbt test
scala-cli --power test . --server=false
curl --compressed -i http://localhost:8080/master.m3u8
curl -H 'Range: bytes=0-1023' -i http://localhost:8080/segment.m4s
```


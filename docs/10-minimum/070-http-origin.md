# Serve it over HTTP

HLS relies on ordinary HTTP, which means ordinary HTTP correctness matters. The
reference `HlsFileServer` serves a directory and implements the behaviors our
tests need to observe:

- `application/vnd.apple.mpegurl` for `.m3u8`;
- media-specific MIME types for TS, fMP4, AAC, and WebVTT;
- gzip playlist responses with `Vary: Accept-Encoding`;
- single byte-range requests and `Content-Range`;
- `HEAD`, `404`, `405`, and `416` responses;
- normalized paths that cannot escape the configured root.

```scala
val server = HlsFileServer.create(Path.of("public"), port = 8080)
server.start()
```

Try the protocol rather than trusting a browser:

```bash
curl --compressed -i http://localhost:8080/index.m3u8
curl -H 'Range: bytes=0-1023' -i http://localhost:8080/segment.m4s
```

## This is an origin, not a production edge

The implementation reads each file into memory and supports only one range. It
does not provide TLS, authorization, conditional requests, configurable cache
control, rate limiting, or metrics. Those omissions are visible by design. A
later production chapter will add policies after we understand the base response.

Apple's current MIME and compression guidance is in the
[HLS authoring specification](https://developer.apple.com/documentation/http-live-streaming/hls-authoring-specification-for-apple-devices).

### Exercise

Request `bytes=1-3`, an unsatisfiable range, and two comma-separated ranges.
Write down the expected status and headers before running each curl command.


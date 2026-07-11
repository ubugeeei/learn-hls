# learn-hls

A small, strongly typed Scala 3 implementation of the playlist layer of
[HTTP Live Streaming (RFC 8216)](https://www.rfc-editor.org/rfc/rfc8216).

The library parses, validates, renders, builds, and serves HLS media and
multivariant playlists. It deliberately leaves media encoding and segmentation
to tools such as FFmpeg: HLS standardizes how already-segmented media is
described and delivered over HTTP.

## Run the tests

```bash
sbt test
# or
scala-cli test .
```

The tutorial starts at [docs/README.md](docs/README.md).


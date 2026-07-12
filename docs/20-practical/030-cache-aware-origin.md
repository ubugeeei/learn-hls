# Publish with cache-aware HTTP semantics

The reference origin now distinguishes mutable indexes from immutable media.

For playlists:

```http
Content-Type: application/vnd.apple.mpegurl
Cache-Control: no-cache
ETag: "8a-19f..."
Last-Modified: Sun, 12 Jul 2026 12:00:00 GMT
Vary: Accept-Encoding
```

`no-cache` does **not** mean “never store.” It permits storage but requires
revalidation before reuse, which is exactly what a changing live playlist needs.
For segment and initialization resources the server emits:

```http
Cache-Control: public, max-age=31536000, immutable
```

That policy is safe only if published media URIs are content-immutable. Never
overwrite `segment42.m4s` with different bytes. Use new names or content hashes.
Otherwise CDN nodes and players can combine a new playlist with old cached media.

## Validators and ranges

The origin derives an entity tag from file size and modification time and emits
an RFC 1123 `Last-Modified` date. `If-None-Match` is checked first; matching
requests return 304 without reading the file body. Single ranges support:

- closed ranges: `bytes=100-199`;
- open-ended ranges: `bytes=100-`;
- suffix ranges: `bytes=-100`;
- 416 plus `Content-Range: bytes */length` for unsatisfiable input.

Multiple ranges deliberately return 416 because implementing multipart byte
ranges correctly is outside this reference origin. The public behavior is
captured as a declarative table in `HlsFileServerSuite`, so adding a syntax case
requires one row rather than a copied test method.

HTTP range semantics are defined in
[RFC 9110 §14](https://www.rfc-editor.org/rfc/rfc9110#section-14). HLS byte-range
tags and HTTP range requests are related but distinct: the playlist selects a
subrange of a media resource, while HTTP transports that selection.

## Atomic publication remains mandatory

Correct cache headers cannot repair a partially written playlist. Render to a
temporary file in the same filesystem, flush it, and atomically rename it over
the public playlist. Publish media bytes before publishing the snapshot that
references them. Deletion runs last and must respect the live retention window.

`AtomicPlaylistPublisher` implements this sequence as a reusable boundary:

```scala
val publisher = AtomicPlaylistPublisher.create()
publisher.publish(Playlist.Media(snapshot), Path.of("public/live/index.m3u8"))
```

It rejects semantically invalid playlists and non-`.m3u8` destinations before
touching the old snapshot. It creates the temporary file in the destination
directory (so rename remains on one filesystem), forces file bytes, performs an
`ATOMIC_MOVE` with replacement, then forces the directory entry. On failure it
best-effort removes the temporary file and returns a typed `PublicationError`.

### Exercise

Write a loop that repeatedly replaces a playlist atomically while another thread
loads it one thousand times. Assert every observed body parses; then repeat with
in-place writes and observe why partial publication is an operational bug.

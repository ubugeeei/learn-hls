# Reload without downloading unchanged playlists

Live clients reload Media Playlists repeatedly. Fetching and reparsing identical
bodies wastes bandwidth and CPU, while unconditional caches risk hiding updates.
HTTP validators solve both problems.

```scala
client.reload(previous) match
  case Right(ReloadResult.NotModified(snapshot)) =>
    // Keep scheduling from the existing immutable snapshot.
  case Right(ReloadResult.Modified(snapshot)) =>
    // Reconcile the new media sequence and enqueue new segments.
  case Left(error) =>
    // Apply the caller's retry/backoff policy.
```

If the previous response had an `ETag`, the request sends `If-None-Match`. If it
did not, but had `Last-Modified`, it sends `If-Modified-Since`. Entity tags take
precedence because timestamps have coarse precision and can collide during fast
playlist publication. A `304 Not Modified` returns the exact previous snapshot;
there is no body to parse and no new fetch instant to invent.

Conditional request semantics come from
[RFC 9110 §13](https://www.rfc-editor.org/rfc/rfc9110#section-13). They complement,
rather than replace, the HLS reload cadence in
[RFC 8216 §6.3.4](https://www.rfc-editor.org/rfc/rfc8216#section-6.3.4).

## What the caller still owns

`HlsClient` performs one fetch or reload. It intentionally does not run a hidden
background thread. A playback or monitoring application must decide:

- when the next reload is due from target duration and observed changes;
- exponential backoff and jitter after transport errors;
- how many consecutive failures make a rendition unhealthy;
- whether to fail over to a redundant variant or base URL;
- cancellation and lifecycle integration with its effect system.

This boundary keeps protocol I/O reusable from Cats Effect, ZIO, Akka/Pekko, or
plain Java executors without embedding one concurrency model in the library.

## Declarative integration tests

`HlsClientSuite` uses a local HTTP server as a protocol fixture. Each test states
a complete behavior:

- gzip body + ETag → parsed snapshot with every URI resolved;
- previous ETag + 304 → `NotModified(previous)`;
- oversized body → `BodyTooLarge`;
- malformed UTF-8 → `InvalidUtf8`.

This is more valuable than mocking `HttpClient.send`: the test observes actual
headers, compression bytes, redirects, and status behavior at the boundary where
production failures occur.

### Exercise

Add a fixture with `Last-Modified` but no `ETag`. On reload, capture the request
header and return 304. Then add both validators and prove only the entity tag is
used.


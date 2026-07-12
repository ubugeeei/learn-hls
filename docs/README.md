# chibihls: Build HTTP Live Streaming from Scratch with Scala 3

This book follows one rule: **the program must work at the end of every
chapter**. We begin with five lines of Extended M3U, then repeatedly replace a
shortcut with the real protocol mechanism. The approach is inspired by
[the chibivue book](https://book.chibivue.land/): small victories, executable
checkpoints, and a gradual bridge from a toy to production-shaped source code.

You only need basic Scala syntax and HTTP familiarity. MPEG containers,
adaptive bitrate, encryption, and live publication are introduced when first
needed. Normative statements link directly to the specification.

## Part 0 — Getting started

1. [Purpose, audience, and boundaries](00-introduction/010-about.md)
2. [How to use the book](00-introduction/020-how-to-read.md)
3. [Set up Scala and run the first test](00-introduction/030-setup.md)
4. [Map the HLS system before writing it](00-introduction/040-architecture.md)

## Part 1 — The smallest HLS

5. [Publish one four-second segment](10-minimum/010-one-segment.md)
6. [Turn strings into a typed model](10-minimum/020-domain-model.md)
7. [Render Extended M3U](10-minimum/030-renderer.md)
8. [Parse untrusted playlists](10-minimum/040-parser.md)
9. [Validate rules spanning multiple lines](10-minimum/050-validation.md)
10. [Build and slide a live window](10-minimum/060-live-window.md)
11. [Serve it over HTTP](10-minimum/070-http-origin.md)
12. [Minimum-section checkpoint](10-minimum/080-checkpoint.md)

## Part 2 — Practical Media Playlists

- Segment state: byte ranges, discontinuities, program date-time, and gaps
- fMP4 initialization sections and compatibility versions
- AES-128 and SAMPLE-AES key state
- date ranges, SCTE-35 carriage, and interstitial metadata
- EVENT, VOD, I-frame playlists, and start offsets

The existing focused references remain useful while Part 2 is expanded:
[Media Playlists](../docs/02-media-playlists.md),
[live and fMP4](../docs/05-live-and-fmp4.md).

## Part 3 — Multivariant and adaptive playback

- Variant streams, measured bandwidth, codecs, and resolution
- audio, video, subtitle, and closed-caption rendition groups
- aligned timelines and safe variant switching
- a small client-side selection algorithm and failure recovery

See the current [Multivariant Playlist chapter](../docs/03-multivariant-playlists.md).

## Part 4 — Media containers

- MPEG-TS packets, PAT/PMT, PES, PTS, continuity counters, and segment boundaries
- ISO Base Media File Format boxes, CMAF fragments, `moof`/`mdat`, and `tfdt`
- WebVTT timestamp mapping and packed audio timestamps

This part explains enough binary structure to inspect and validate segments. It
does not attempt to implement video codecs; encoding samples is a distinct and
far larger domain.

## Part 5 — Production delivery

- atomic publication, caching, gzip, byte ranges, and MIME types
- live reload timing and stale playlist detection
- TLS, key authorization, URI design, and CDN behavior
- property tests, fuzzing, Apple validator tools, and failure drills

Practical implementation chapters already available:

- [load a real playlist over HTTP](20-practical/010-http-client.md)
- [conditional live reloads](20-practical/020-conditional-reloads.md)
- [cache-aware origin delivery](20-practical/030-cache-aware-origin.md)

See the current [delivery chapter](../docs/06-delivery.md).

## Checkpoint source

Every hands-on chapter links to source in `examples/steps`. CI compiles and
tests these files so the book cannot silently rot. Run all checkpoints with:

```bash
scala-cli --power test . --server=false
```

The final library lives in `src/main/scala`. Every `FooSuite.scala` sits beside
the `Foo.scala` it specifies; build filters keep suites out of production
artifacts while compiling them in Test scope. Source files stay below roughly
350 lines so a reader can finish one unit without losing its local context.

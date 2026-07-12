# Minimum-section checkpoint

We began with five lines of text. We now have a vertical slice:

```text
measured segment facts
        ↓
refined immutable model ← parser ← untrusted M3U8
        ↓                    ↓
cross-playlist validation    line-numbered errors
        ↓
canonical renderer
        ↓
atomic live snapshots
        ↓
HTTP origin → repeated client reloads
```

Run the complete suite:

```bash
scala-cli --power test . --server=false
# CI uses the equivalent:
sbt test
```

The checkpoint examples are compiled alongside the library and their colocated
`ChapterCheckpointsSuite` verifies them. Documentation code that drifts is worse
than no code; this arrangement catches renamed APIs and invalid examples in CI.

## What we simplified

- Segment bytes are supplied rather than generated or inspected.
- The model covers the central RFC tags but not every multivariant/session tag.
- Live retention uses count rather than a duration policy.
- The server is a learning origin, not a hardened deployment.
- We have not implemented how a client selects and switches variants.

These are now concrete seams, not mysterious missing pieces. Part 2 deepens the
Media Playlist state machine: byte ranges, discontinuities, wall-clock mapping,
encryption, fMP4 initialization, timed metadata, and trick-play indexes. Each
feature will begin with a playlist that fails for a specific reason and end with
a runnable test.

## Review questions

1. Which tags create one-shot state and which create persistent state?
2. Why is semantic validation not part of the attribute tokenizer?
3. What must change together when the live window evicts two segments?
4. Why can canonical rendering preserve meaning without preserving bytes?
5. Which responsibilities belong to a codec, a segmenter, and an HLS author?

If you can answer these without opening the source, the minimum implementation
has done its job: you have a map on which the deeper machinery has a place.

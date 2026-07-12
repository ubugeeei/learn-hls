# Render Extended M3U

The model is useful only if clients can consume it. A renderer is a total
function from a valid model to canonical UTF-8 text:

```scala
def render(playlist: Playlist): String = playlist match
  case Playlist.Media(value)        => renderMedia(value)
  case Playlist.Multivariant(value) => renderMultivariant(value)
```

Pattern matching is exhaustive. Adding a third playlist kind would force us to
decide how it renders rather than falling through a default branch.

## Canonical does not mean byte-preserving

These inputs have the same meaning:

```m3u8
#EXTINF:4.000,
#EXTINF:4,
```

The renderer chooses `4`. It orders tags deterministically, writes one final
newline, emits explicit byte-range offsets, and quotes string attributes. This
is ideal for generated playlists, caching, diffs, and round-trip tests. It is
not suitable for a formatting-preserving editor; that would need a concrete
syntax tree retaining comments and original tokens.

## Persistent output state

`EXT-X-KEY` and `EXT-X-MAP` persist across segments. Repeating them before every
segment is legal but noisy. The renderer remembers the last emitted value and
writes a new tag only when state changes. This mirrors the parser in reverse.

The tag scope rules are collected in
[RFC 8216 §4.3](https://www.rfc-editor.org/rfc/rfc8216#section-4.3).

### Exercise

Construct three segments where the first two share an AES-128 key and the third
is clear. Render them. Explain why the third needs `METHOD=NONE` even though its
model says `Encryption.None`.


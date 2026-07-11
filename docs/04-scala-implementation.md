# 4. A typed Scala 3 implementation

The model is colocated by domain: `model`, `parser`, `render`, `validation`,
`builder`, `live`, and `http`. Tests mirror those packages. A change to live
window behavior therefore lives next to its implementation and tests rather
than in a generic utility layer.

## Refine primitive values

Scala 3 opaque types preserve zero-cost JVM representations while preventing
category mistakes:

```scala
opaque type Bandwidth = Long
object Bandwidth:
  def from(value: Long): Either[String, Bandwidth] =
    Either.cond(value > 0, value, "bandwidth must be positive")
```

Callers cannot pass a sequence number where a bandwidth is expected even though
both are `Long` internally. Smart constructors keep invalid values outside the
domain. Algebraic data types make protocol alternatives exhaustive:

```scala
enum Encryption:
  case None
  case Aes128(uri: PlaylistUri, iv: Option[String])
  case SampleAes(uri: PlaylistUri, keyFormat: Option[String],
                 versions: Option[String], iv: Option[String])
```

## Parse, validate, render

Keep syntax and semantics distinct:

1. Tokenize lines and attribute lists.
2. Convert text into refined values and domain cases.
3. Validate relationships across the completed playlist.
4. Render the domain in a deterministic canonical order.

This separation lets programmatically built playlists reuse the same semantic
validator. It also makes `parse(render(value)) == Right(value)` a powerful test.

```scala
val parsed: Either[ParseError, Playlist] = PlaylistParser.parse(source)
val canonical: String = parsed.fold(_.toString, PlaylistRenderer.render)
```

Unknown `EXT-X-` tags are ignored for forward compatibility, following client
behavior in [RFC 8216 §6.3.2](https://www.rfc-editor.org/rfc/rfc8216#section-6.3.2).
Known tags are parsed strictly so a typo never silently changes playback.


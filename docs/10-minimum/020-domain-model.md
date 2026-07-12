# Turn strings into a typed model

The protocol uses “decimal-integer” for both bandwidth and sequence numbers,
but exchanging them would be nonsense. Primitive obsession makes such mistakes
easy, so we refine values at the boundary.

```scala
opaque type Duration = BigDecimal
object Duration:
  def from(value: BigDecimal): Either[String, Duration] =
    Either.cond(value >= 0, value, s"duration must be non-negative: $value")
```

Opaque types disappear at runtime but remain distinct to the compiler. We use
`BigDecimal` because binary floating point cannot exactly represent many decimal
fractions written in playlists.

## Model one segment

```scala
final case class MediaSegment(
  uri: PlaylistUri,
  duration: Duration,
  title: Option[String] = None
)
```

The final class has more fields, but this is the smallest useful shape. Optional
fields have defaults so ordinary segments remain pleasant to construct. The
playlist owns sequence-wide facts such as target duration and end state.

Run [Step02TypedPlaylist.scala](../../examples/steps/Step02TypedPlaylist.scala):

```bash
scala-cli --power run . --server=false --main-class examples.steps.printTypedPlaylist
```

`MediaPlaylistBuilder.create` returns `Either`: a non-positive target never
creates a builder. `build` returns every semantic validation failure so an
authoring pipeline can report all bad measurements in one run.

## Unsafe constructors are a boundary marker

Examples use `Duration.unsafe(4)` for readability. Production ingestion should
use `Duration.from` or `parse` and preserve the `Left`. An `unsafe` constructor
is not validation; it says “the caller has already validated this literal.”

### Exercise

Try to construct `Bandwidth` and pass it as `MediaSequence`. Observe the compile
error. Then try `Duration.from(-1)` and inspect the domain error without throwing.


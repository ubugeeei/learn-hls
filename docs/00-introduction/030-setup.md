# Set up Scala and run the first test

The final project uses Scala 3.7, sbt, MUnit, and JDK 21. Scala CLI offers the
fastest start because `project.scala` contains the same language and test
settings as `build.sbt`.

```bash
git clone https://github.com/ubugeeei/learn-hls
cd learn-hls
scala-cli --power test . --server=false
```

Or use the build that CI runs:

```bash
sbt test
```

## Why Scala 3?

HLS contains many values with identical runtime representations but distinct
meanings: a bandwidth and a media sequence are both non-negative integers; a
segment duration and a start offset are both decimals. Scala 3 gives us:

- opaque types for zero-cost refined scalars;
- enums and pattern matching for exhaustive protocol alternatives;
- immutable case classes for snapshots;
- extension methods for domain-specific rendering;
- significant indentation that keeps small state machines readable.

## Verify the first checkpoint

```bash
scala-cli --power run . --server=false --main-class examples.steps.printOneSegment
```

You should see a five-line playlist. At this point it is only a string. In the
next chapters we will earn every abstraction by finding a concrete problem the
string cannot solve.


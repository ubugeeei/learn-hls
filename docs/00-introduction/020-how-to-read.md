# How to use this book

Each chapter follows the same loop:

1. **Observe a problem.** We begin with output that is missing one capability.
2. **Read the smallest relevant specification section.** No front-loaded RFC
   memorization is required.
3. **Change one concept.** The source remains executable.
4. **Write a test from a protocol example or invariant.**
5. **Run the checkpoint.** Inspect the text or HTTP response yourself.
6. **Compare with the final library.** Follow links into `src/main/scala`.

The examples are intentionally smaller than the final implementation. Copying a
finished parser teaches less than discovering why it needs persistent state,
one-shot state, quoted-string tokenization, and cross-line validation.

## Three reading paths

**Tour:** complete Part 1. You will understand the whole HLS control loop with a
small implementation.

**Authoring server:** continue through Media Playlists, Multivariant Playlists,
container inspection, and production delivery.

**Protocol specialist:** read every specification note, implement the exercises,
and compare behavior against independent players and validator tools.

## Exercises are design work

Exercises do not merely ask you to retype code. Typical tasks are “decide what
state this tag changes,” “construct the smallest counterexample,” or “predict
what a client sees during a concurrent update.” These are the skills needed to
work on protocol software after the book ends.


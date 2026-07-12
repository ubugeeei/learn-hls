# Media basics: pictures, samples, and containers

This chapter gives only the media knowledge needed for the rest of the book.
Nothing here asks you to implement a video codec.

## From pictures to compressed bytes

A video starts as a sequence of pictures called **frames**. Raw frames are very
large, so an **encoder** compresses them using a **codec** such as H.264. Audio
is similarly divided into samples and compressed, often with AAC.

```mermaid
flowchart LR
    Frames["Raw video frames"] --> VideoEncoder["H.264 encoder"]
    Sound["Raw audio samples"] --> AudioEncoder["AAC encoder"]
    VideoEncoder --> V["Compressed video samples"]
    AudioEncoder --> A["Compressed audio samples"]
    V --> Muxer["Muxer"]
    A --> Muxer
    Muxer --> Container["MPEG-TS or fragmented MP4"]
```

A codec defines how one kind of media is compressed. A **container** stores
compressed video, audio, timestamps, and descriptive information together. The
verb **mux** means combining tracks into a container; **demux** means separating
them again.

## Why timestamps exist

Audio and video must agree on when each sample is presented. A timestamp is a
number on a timeline, not a wall-clock date.

```mermaid
timeline
    title One short synchronized timeline
    0 seconds : video frame A : audio samples A
    1 second  : video frame B : audio samples B
    2 seconds : video frame C : audio samples C
```

If timestamps jump unexpectedly, audio may drift away from video. If a segment
begins in the middle of data needed to decode its first picture, switching to it
may show corruption. This is why HLS implementation eventually has to inspect
media boundaries, not only playlist text.

## Keyframes and independent segments

Many compressed video frames depend on earlier frames. A **keyframe** (commonly
an IDR picture for H.264) can begin decoding without earlier picture data. A
segment intended for clean startup or bitrate switching normally begins at such
a boundary.

```mermaid
flowchart LR
    K1["Keyframe"] --> D1["Dependent frame"] --> D2["Dependent frame"]
    K2["Keyframe"] --> D3["Dependent frame"] --> D4["Dependent frame"]
    Cut1["Segment 1"] -. contains .-> K1
    Cut2["Segment 2"] -. should start here .-> K2
```

`EXT-X-INDEPENDENT-SEGMENTS` is therefore a promise about media bytes. Adding
the tag does not magically make segments independent. The encoder and segmenter
must create the correct boundary first.

## What this project implements

The project implements playlist parsing, validation, rendering, publication,
HTTP delivery, and retrieval. Later chapters inspect MPEG-TS and fragmented MP4
structure. It does not compress pictures or sound; mature encoders such as
FFmpeg provide sample data for our experiments.


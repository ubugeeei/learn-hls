# Glossary in plain language

Use this page as a lookup table. Definitions intentionally favor intuition over
formal completeness; chapter links lead to the precise behavior.

## Media words

| Term | Plain-language definition |
|---|---|
| Bitrate | How many bits are used per second. Higher is not automatically better, but usually costs more bandwidth. |
| Codec | Rules for compressing and decoding one kind of media, such as H.264 video or AAC audio. |
| Container | A structure that stores compressed tracks, timestamps, and metadata, such as MPEG-TS or MP4. |
| Frame | One video picture. |
| Keyframe | A picture from which decoding can begin without earlier pictures. |
| Mux | Combine video, audio, and metadata into a container. |
| Sample | A compressed unit belonging to a media track. |
| Timestamp | A position on a media timeline used to synchronize presentation. |
| Track | One stream inside a container, such as video, English audio, or subtitles. |

## HLS words

| Term | Plain-language definition |
|---|---|
| HLS | HTTP Live Streaming, a protocol for presenting media through playlists and HTTP resources. |
| Media Playlist | The ordered segment list for one rendition. |
| Multivariant Playlist | A menu of alternative Media Playlists, such as several video qualities and languages. RFC 8216 calls it a Master Playlist. |
| Segment | A short portion of media that can be requested. |
| Rendition | One alternative component, such as English audio or subtitles. |
| Variant | A playable combination described by bitrate, codecs, resolution, and rendition groups. |
| Live window | The currently advertised portion of an ongoing stream. Old segments leave as new ones arrive. |
| Media sequence | The number assigned to the first segment in a Media Playlist snapshot. |
| Target duration | An integer upper bound related to segment durations and reload timing. |
| Discontinuity | A declared boundary where timestamps, encoding, or other media properties change abruptly. |

## HTTP and delivery words

| Term | Plain-language definition |
|---|---|
| Client | Software making requests; in this book, usually a player or playlist loader. |
| Origin | The authoritative server that publishes playlists and media. |
| CDN | A distributed network of caches that serves content closer to users. |
| Cache | Stored response data reused to avoid repeating work or network transfer. |
| ETag | A server-provided identifier for one version of a resource. |
| Conditional request | A request asking the server to send a body only if the resource changed. |
| MIME type | A label such as `video/mp2t` describing the kind of response body. |
| URI | A resource identifier used in playlists and HTTP requests. A URL is a familiar kind of URI. |
| Relative URI | A reference such as `segments/0.ts` resolved against the playlist's own URI. |
| Byte range | A continuous interval of bytes within a resource. |
| Atomic publication | Replacing a visible playlist so readers see either the complete old version or complete new version. |

## Scala implementation words

| Term | Plain-language definition |
|---|---|
| ADT | A closed set of data alternatives, represented here with enums and case classes. |
| Opaque type | A Scala type distinct at compile time but represented without a wrapper at runtime. |
| Immutable | A value that cannot change after construction; an update creates a new value. |
| Smart constructor | A function that validates input before constructing a domain value. |
| Snapshot | One coherent immutable view of playlist state at a moment in time. |


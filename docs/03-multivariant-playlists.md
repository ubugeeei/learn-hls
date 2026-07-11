# 3. Adaptive bitrate and Multivariant Playlists

Adaptive bitrate streaming begins with alternatives. `EXT-X-STREAM-INF`
describes one variant and the URI on the next line identifies its Media
Playlist.

```m3u8
#EXTM3U
#EXT-X-MEDIA:TYPE=AUDIO,GROUP-ID="aac",NAME="English",URI="audio/en.m3u8",DEFAULT=YES,AUTOSELECT=YES
#EXT-X-STREAM-INF:BANDWIDTH=1280000,AVERAGE-BANDWIDTH=1000000,CODECS="avc1.4d401f,mp4a.40.2",RESOLUTION=1280x720,AUDIO="aac"
720p/video.m3u8
```

`BANDWIDTH` is required and is a peak aggregate bitrate, not merely the video
track bitrate. A client uses it to avoid choosing a stream it cannot sustain.
The full attribute contract is in
[RFC 8216 §4.3.4.2](https://www.rfc-editor.org/rfc/rfc8216#section-4.3.4.2).

`EXT-X-MEDIA` declares renditions such as alternate audio and subtitles. A
variant references a rendition `GROUP-ID`; the library validates that each
referenced group exists. It also enforces two easy-to-miss rules:

- `DEFAULT=YES` requires `AUTOSELECT=YES`.
- `FORCED` only applies to subtitles.

See [RFC 8216 §4.3.4.1](https://www.rfc-editor.org/rfc/rfc8216#section-4.3.4.1).

## Choosing a bitrate ladder

Do not invent a universal ladder. Encode representative content, measure peak
and average rates, and verify visual quality. Variants should normally share an
aspect ratio and aligned segment boundaries so switches do not interrupt
playback. Apple's current
[authoring specification](https://developer.apple.com/documentation/http-live-streaming/hls-authoring-specification-for-apple-devices)
adds device-oriented bitrate, codec, frame-rate, and accessibility requirements
that go beyond RFC 8216.


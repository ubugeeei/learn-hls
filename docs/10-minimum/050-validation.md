# Validate rules spanning multiple lines

Parsing proves that individual tokens have the right shape. Validation proves
that the complete playlist is coherent. Keeping these phases separate lets
programmatically built playlists reuse the same rules.

Examples of cross-value invariants:

- a segment duration rounded to the nearest integer cannot exceed target;
- `PLAYLIST-TYPE:VOD` requires `ENDLIST`;
- the declared compatibility version cannot be below the features in use;
- a variant's rendition group references must exist;
- `DEFAULT=YES` on a rendition requires `AUTOSELECT=YES`;
- `END-ON-NEXT` date ranges require a class and forbid explicit end/duration.

`PlaylistValidator` returns `Vector[String]` rather than throwing. The parser
turns its first failure into a `ParseError`, while builders expose the whole
vector to authoring applications.

## Compatibility version is capability negotiation

`EXT-X-VERSION` does not announce the author's software version. It declares the
minimum protocol compatibility needed to interpret the playlist. Byte ranges
need version 4; some key attributes need 5; maps, gaps, and date ranges need 6.
See [RFC 8216 §7](https://www.rfc-editor.org/rfc/rfc8216#section-7).

The builder infers a minimum when the caller omits one. If the caller explicitly
chooses a lower version, validation rejects it rather than silently rewriting a
deliberate declaration.

### Exercise

Build an fMP4 playlist with `EXT-X-MAP` and explicit version 3. Compare the
builder error with the same playlist after removing the explicit version.


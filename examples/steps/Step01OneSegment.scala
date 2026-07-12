package examples.steps

/** Chapter 1 checkpoint: the smallest useful HLS Media Playlist. */
object Step01OneSegment:
  val playlist: String =
    """#EXTM3U
      |#EXT-X-TARGETDURATION:4
      |#EXTINF:4,
      |segment0.ts
      |#EXT-X-ENDLIST
      |""".stripMargin

  @main def printOneSegment(): Unit = print(playlist)

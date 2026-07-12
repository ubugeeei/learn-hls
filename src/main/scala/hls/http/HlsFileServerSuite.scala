//> using target.scope test
package hls.http

import java.net.URI
import java.net.http.{HttpClient, HttpRequest, HttpResponse}
import java.nio.file.Files

final class HlsFileServerSuite extends munit.FunSuite:
  private final case class RangeCase(header: String, status: Int, expected: Vector[Byte])

  test("serve playlists with HLS MIME and gzip, and segments with ranges"):
    val root = Files.createTempDirectory("hls-server")
    Files.writeString(root.resolve("index.m3u8"), "#EXTM3U\n")
    Files.write(root.resolve("segment.ts"), Array[Byte](0, 1, 2, 3, 4))
    val server = HlsFileServer.create(root)
    server.start()
    try
      val client          = HttpClient.newHttpClient()
      val playlistRequest = HttpRequest
        .newBuilder(URI.create(s"http://127.0.0.1:${server.port}/index.m3u8"))
        .header("Accept-Encoding", "gzip")
        .build()
      val playlist = client.send(playlistRequest, HttpResponse.BodyHandlers.ofByteArray())
      assertEquals(playlist.statusCode(), 200)
      assertEquals(
        playlist.headers().firstValue("Content-Type").orElse(""),
        "application/vnd.apple.mpegurl"
      )
      assertEquals(playlist.headers().firstValue("Content-Encoding").orElse(""), "gzip")
      assertEquals(playlist.headers().firstValue("Cache-Control").orElse(""), "no-cache")
      val entityTag = playlist.headers().firstValue("ETag").orElseThrow()

      val conditionalRequest = HttpRequest
        .newBuilder(URI.create(s"http://127.0.0.1:${server.port}/index.m3u8"))
        .header("If-None-Match", entityTag)
        .build()
      val conditional = client.send(conditionalRequest, HttpResponse.BodyHandlers.discarding())
      assertEquals(conditional.statusCode(), 304)

      val rangeRequest = HttpRequest
        .newBuilder(URI.create(s"http://127.0.0.1:${server.port}/segment.ts"))
        .header("Range", "bytes=1-3")
        .build()
      val range = client.send(rangeRequest, HttpResponse.BodyHandlers.ofByteArray())
      assertEquals(range.statusCode(), 206)
      assertEquals(range.body().toVector, Vector[Byte](1, 2, 3))
      assertEquals(range.headers().firstValue("Content-Range").orElse(""), "bytes 1-3/5")
      assertEquals(
        range.headers().firstValue("Cache-Control").orElse(""),
        "public, max-age=31536000, immutable"
      )

      val suffixRequest = HttpRequest
        .newBuilder(URI.create(s"http://127.0.0.1:${server.port}/segment.ts"))
        .header("Range", "bytes=-2")
        .build()
      val suffix = client.send(suffixRequest, HttpResponse.BodyHandlers.ofByteArray())
      assertEquals(suffix.statusCode(), 206)
      assertEquals(suffix.body().toVector, Vector[Byte](3, 4))
    finally server.stop()

  test("reject path traversal"):
    val root   = Files.createTempDirectory("hls-server")
    val server = HlsFileServer.create(root)
    server.start()
    try
      val request =
        HttpRequest.newBuilder(URI.create(s"http://127.0.0.1:${server.port}/../secret")).build()
      val response =
        HttpClient.newHttpClient().send(request, HttpResponse.BodyHandlers.discarding())
      assertEquals(response.statusCode(), 404)
    finally server.stop()

  test("range behavior is defined by a declarative case table"):
    val cases = Vector(
      RangeCase("bytes=0-0", 206, Vector(0)),
      RangeCase("bytes=2-", 206, Vector(2, 3, 4)),
      RangeCase("bytes=-2", 206, Vector(3, 4)),
      RangeCase("bytes=9-10", 416, Vector.empty),
      RangeCase("bytes=0-1,3-4", 416, Vector.empty)
    )
    val root = Files.createTempDirectory("hls-range-table")
    Files.write(root.resolve("segment.ts"), Array[Byte](0, 1, 2, 3, 4))
    val server = HlsFileServer.create(root)
    server.start()
    try
      val client = HttpClient.newHttpClient()
      cases.foreach: example =>
        val request = HttpRequest
          .newBuilder(URI.create(s"http://127.0.0.1:${server.port}/segment.ts"))
          .header("Range", example.header)
          .build()
        val response = client.send(request, HttpResponse.BodyHandlers.ofByteArray())
        assertEquals(response.statusCode(), example.status, clues(example.header))
        assertEquals(response.body().toVector, example.expected, clues(example.header))
    finally server.stop()

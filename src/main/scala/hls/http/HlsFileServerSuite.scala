//> using target.scope test
package hls.http

import java.net.URI
import java.net.http.{HttpClient, HttpRequest, HttpResponse}
import java.nio.file.Files

final class HlsFileServerSuite extends munit.FunSuite:
  test("serve playlists with HLS MIME and gzip, and segments with ranges"):
    val root = Files.createTempDirectory("hls-server")
    Files.writeString(root.resolve("index.m3u8"), "#EXTM3U\n")
    Files.write(root.resolve("segment.ts"), Array[Byte](0, 1, 2, 3, 4))
    val server = HlsFileServer.create(root)
    server.start()
    try
      val client = HttpClient.newHttpClient()
      val playlistRequest = HttpRequest.newBuilder(URI.create(s"http://127.0.0.1:${server.port}/index.m3u8"))
        .header("Accept-Encoding", "gzip").build()
      val playlist = client.send(playlistRequest, HttpResponse.BodyHandlers.ofByteArray())
      assertEquals(playlist.statusCode(), 200)
      assertEquals(playlist.headers().firstValue("Content-Type").orElse(""), "application/vnd.apple.mpegurl")
      assertEquals(playlist.headers().firstValue("Content-Encoding").orElse(""), "gzip")

      val rangeRequest = HttpRequest.newBuilder(URI.create(s"http://127.0.0.1:${server.port}/segment.ts"))
        .header("Range", "bytes=1-3").build()
      val range = client.send(rangeRequest, HttpResponse.BodyHandlers.ofByteArray())
      assertEquals(range.statusCode(), 206)
      assertEquals(range.body().toVector, Vector[Byte](1, 2, 3))
      assertEquals(range.headers().firstValue("Content-Range").orElse(""), "bytes 1-3/5")
    finally server.stop()

  test("reject path traversal"):
    val root = Files.createTempDirectory("hls-server")
    val server = HlsFileServer.create(root)
    server.start()
    try
      val request = HttpRequest.newBuilder(URI.create(s"http://127.0.0.1:${server.port}/../secret")).build()
      val response = HttpClient.newHttpClient().send(request, HttpResponse.BodyHandlers.discarding())
      assertEquals(response.statusCode(), 404)
    finally server.stop()

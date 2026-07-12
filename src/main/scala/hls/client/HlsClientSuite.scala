//> using target.scope test
package hls.client

import com.sun.net.httpserver.HttpServer
import hls.model.*
import java.io.ByteArrayOutputStream
import java.net.{InetSocketAddress, URI}
import java.net.http.HttpClient
import java.time.Instant
import java.util.zip.GZIPOutputStream

final class HlsClientSuite extends munit.FunSuite:
  private val source = """#EXTM3U
    |#EXT-X-VERSION:6
    |#EXT-X-TARGETDURATION:4
    |#EXT-X-MAP:URI="init.mp4"
    |#EXT-X-KEY:METHOD=AES-128,URI="../keys/key.bin"
    |#EXTINF:4,
    |segments/0.m4s
    |""".stripMargin

  test("load gzip, preserve metadata, and resolve every URI"):
    withServer: (server, base) =>
      server.createContext(
        "/live/index.m3u8",
        exchange =>
          val bytes = gzip(source.getBytes(java.nio.charset.StandardCharsets.UTF_8))
          exchange.getResponseHeaders.set("Content-Encoding", "gzip")
          exchange.getResponseHeaders.set("ETag", "\"v1\"")
          exchange.sendResponseHeaders(200, bytes.length)
          exchange.getResponseBody.write(bytes)
          exchange.close()
      )
      val now      = Instant.parse("2026-07-12T00:00:00Z")
      val client   = HlsClient.testing(HttpClient.newHttpClient(), 1024 * 1024, () => now)
      val snapshot = client.load(base.resolve("/live/index.m3u8")).toOption.get
      val media    = snapshot.playlist.asInstanceOf[Playlist.Media].value
      assertEquals(snapshot.entityTag, Some("\"v1\""))
      assertEquals(snapshot.fetchedAt, now)
      assertEquals(media.segments.head.uri.toString, base.resolve("/live/segments/0.m4s").toString)
      assertEquals(
        media.segments.head.initializationMap.get.uri.toString,
        base.resolve("/live/init.mp4").toString
      )
      media.segments.head.encryption match
        case Encryption.Aes128(uri, _) =>
          assertEquals(uri.toString, base.resolve("/keys/key.bin").toString)
        case other => fail(s"unexpected encryption: $other")

  test("reload sends If-None-Match and reuses a snapshot on 304"):
    withServer: (server, base) =>
      var conditionalHeader: Option[String] = None
      server.createContext(
        "/index.m3u8",
        exchange =>
          conditionalHeader = Option(exchange.getRequestHeaders.getFirst("If-None-Match"))
          if conditionalHeader.contains("\"v1\"") then exchange.sendResponseHeaders(304, -1)
          else
            exchange.getResponseHeaders.set("ETag", "\"v1\"")
            val bytes = source.getBytes(java.nio.charset.StandardCharsets.UTF_8)
            exchange.sendResponseHeaders(200, bytes.length)
            exchange.getResponseBody.write(bytes)
          exchange.close()
      )
      val client   = HlsClient.create(maximumBytes = 1024 * 1024)
      val first    = client.load(base.resolve("/index.m3u8")).toOption.get
      val reloaded = client.reload(first).toOption.get
      assertEquals(conditionalHeader, Some("\"v1\""))
      assertEquals(reloaded, ReloadResult.NotModified(first))

  test("reject oversized and malformed UTF-8 bodies"):
    withServer: (server, base) =>
      server.createContext(
        "/large.m3u8",
        exchange =>
          val bytes = Array.fill[Byte](100)(1)
          exchange.sendResponseHeaders(200, bytes.length)
          exchange.getResponseBody.write(bytes)
          exchange.close()
      )
      server.createContext(
        "/utf8.m3u8",
        exchange =>
          val bytes = Array[Byte](0xc3.toByte, 0x28)
          exchange.sendResponseHeaders(200, bytes.length)
          exchange.getResponseBody.write(bytes)
          exchange.close()
      )
      val client = HlsClient.create(maximumBytes = 32)
      assert(
        client
          .load(base.resolve("/large.m3u8"))
          .left
          .toOption
          .exists(_.isInstanceOf[ClientError.BodyTooLarge])
      )
      assert(
        client
          .load(base.resolve("/utf8.m3u8"))
          .left
          .toOption
          .exists(_.isInstanceOf[ClientError.InvalidUtf8])
      )

  private def withServer(test: (HttpServer, URI) => Unit): Unit =
    val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
    try
      server.start()
      test(server, URI.create(s"http://127.0.0.1:${server.getAddress.getPort}"))
    finally server.stop(0)

  private def gzip(bytes: Array[Byte]): Array[Byte] =
    val output = ByteArrayOutputStream()
    val gzip   = GZIPOutputStream(output)
    gzip.write(bytes)
    gzip.close()
    output.toByteArray

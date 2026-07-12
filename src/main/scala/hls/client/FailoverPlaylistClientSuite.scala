//> using target.scope test
package hls.client

import com.sun.net.httpserver.HttpServer
import java.net.{InetSocketAddress, URI}
import java.time.{Duration, Instant}

final class FailoverPlaylistClientSuite extends munit.FunSuite:
  private val valid = """#EXTM3U
    |#EXT-X-TARGETDURATION:4
    |#EXTINF:4,
    |segment.ts
    |""".stripMargin

  test("retryable failure selects backup and makes it preferred"):
    withServer: (server, base) =>
      var primaryRequests = 0
      var backupRequests  = 0
      server.createContext(
        "/primary.m3u8",
        exchange =>
          primaryRequests += 1
          exchange.sendResponseHeaders(503, -1)
          exchange.close()
      )
      server.createContext(
        "/backup.m3u8",
        exchange =>
          backupRequests += 1
          respond(exchange, 200, valid)
      )
      val origins = Vector(base.resolve("/primary.m3u8"), base.resolve("/backup.m3u8"))
      val initial = FailoverState.create(origins).toOption.get
      val client  = FailoverPlaylistClient.create(HlsClient.create())
      val now     = Instant.parse("2026-07-12T12:00:00Z")

      val first  = client.load(initial, now).toOption.get
      val second = client.load(first.state, now.plusMillis(100)).toOption.get

      assertEquals(first.attempts.map(_.uri), Vector(origins.head))
      assertEquals(first.state.preferredIndex, 1)
      assertEquals(second.snapshot.requestedUri, origins(1))
      assertEquals(primaryRequests, 1)
      assertEquals(backupRequests, 2)

  test("conditional reload falls back with a fresh load on another origin"):
    withServer: (server, base) =>
      var primaryHealthy = true
      server.createContext(
        "/primary.m3u8",
        exchange =>
          if primaryHealthy then
            exchange.getResponseHeaders.set("ETag", "\"v1\"")
            respond(exchange, 200, valid)
          else
            exchange.sendResponseHeaders(503, -1)
            exchange.close()
      )
      server.createContext(
        "/backup.m3u8",
        exchange => respond(exchange, 200, valid.replace("segment.ts", "backup.ts"))
      )
      val origins = Vector(base.resolve("/primary.m3u8"), base.resolve("/backup.m3u8"))
      val initial = FailoverState.create(origins).toOption.get
      val client  = FailoverPlaylistClient.create(
        HlsClient.create(),
        FailoverPolicy(Duration.ofSeconds(1), Duration.ofSeconds(8))
      )
      val now   = Instant.parse("2026-07-12T12:00:00Z")
      val first = client.load(initial, now).toOption.get
      primaryHealthy = false

      val reloaded = client.reload(first.snapshot, first.state, now.plusSeconds(1)).toOption.get

      val modified = reloaded.result.asInstanceOf[ReloadResult.Modified].snapshot
      assertEquals(modified.requestedUri, origins(1))
      assertEquals(reloaded.state.preferredIndex, 1)
      assertEquals(reloaded.attempts.map(_.uri), Vector(origins.head))

  test("content errors are terminal and never hidden by backup content"):
    withServer: (server, base) =>
      var backupRequests = 0
      server.createContext("/invalid.m3u8", exchange => respond(exchange, 200, "not a playlist"))
      server.createContext(
        "/backup.m3u8",
        exchange =>
          backupRequests += 1
          respond(exchange, 200, valid)
      )
      val origins = Vector(base.resolve("/invalid.m3u8"), base.resolve("/backup.m3u8"))
      val state   = FailoverState.create(origins).toOption.get
      val result  = FailoverPlaylistClient
        .create(HlsClient.create())
        .load(state, Instant.parse("2026-07-12T12:00:00Z"))

      assert(result.left.toOption.exists(_.isInstanceOf[FailoverError.Terminal]))
      assertEquals(backupRequests, 0)

  test("origin-state requirements reject degenerate pools"):
    val uri = URI.create("https://example.test/index.m3u8")
    assert(FailoverState.create(Vector(uri)).isLeft)
    assert(FailoverState.create(Vector(uri, uri)).isLeft)

  private def withServer(test: (HttpServer, URI) => Unit): Unit =
    val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
    try
      server.start()
      test(server, URI.create(s"http://127.0.0.1:${server.getAddress.getPort}"))
    finally server.stop(0)

  private def respond(
      exchange: com.sun.net.httpserver.HttpExchange,
      status: Int,
      body: String
  ): Unit =
    val bytes = body.getBytes(java.nio.charset.StandardCharsets.UTF_8)
    exchange.sendResponseHeaders(status, bytes.length)
    exchange.getResponseBody.write(bytes)
    exchange.close()

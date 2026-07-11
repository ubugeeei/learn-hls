package hls.http

import com.sun.net.httpserver.{HttpExchange, HttpServer}
import java.net.InetSocketAddress
import java.nio.file.{Files, Path}
import java.util.concurrent.Executors
import java.util.zip.GZIPOutputStream

/** Minimal HLS origin server for a directory of playlists and media segments.
  *
  * It emits the MIME types recommended by Apple's HLS authoring specification,
  * supports byte range requests, gzip-compresses playlists when accepted, and
  * rejects paths escaping the configured root. Production deployments should
  * normally put TLS, authentication, and CDN caching in front of this origin.
  */
final class HlsFileServer private (server: HttpServer):
  def port: Int = server.getAddress.getPort
  def start(): Unit = server.start()
  def stop(delaySeconds: Int = 0): Unit = server.stop(delaySeconds)

object HlsFileServer:
  def create(root: Path, port: Int = 0): HlsFileServer =
    val normalizedRoot = root.toAbsolutePath.normalize
    require(Files.isDirectory(normalizedRoot), s"not a directory: $root")
    val server = HttpServer.create(InetSocketAddress(port), 0)
    server.setExecutor(Executors.newCachedThreadPool())
    server.createContext("/", exchange => handle(normalizedRoot, exchange))
    HlsFileServer(server)

  private def handle(root: Path, exchange: HttpExchange): Unit =
    try
      if exchange.getRequestMethod != "GET" && exchange.getRequestMethod != "HEAD" then respond(exchange, 405, Array.emptyByteArray)
      else
        val relative = exchange.getRequestURI.getPath.stripPrefix("/")
        val file = root.resolve(relative).normalize
        if !file.startsWith(root) || !Files.isRegularFile(file) then respond(exchange, 404, Array.emptyByteArray)
        else serve(exchange, file)
    catch case _: Exception => respond(exchange, 500, Array.emptyByteArray)
    finally exchange.close()

  private def serve(exchange: HttpExchange, file: Path): Unit =
    val contentType = mediaType(file)
    exchange.getResponseHeaders.set("Content-Type", contentType)
    exchange.getResponseHeaders.set("Accept-Ranges", "bytes")
    exchange.getResponseHeaders.set("X-Content-Type-Options", "nosniff")
    val bytes = Files.readAllBytes(file)
    parseRange(exchange.getRequestHeaders.getFirst("Range"), bytes.length) match
      case Left(_) =>
        exchange.getResponseHeaders.set("Content-Range", s"bytes */${bytes.length}")
        respond(exchange, 416, Array.emptyByteArray)
      case Right(Some((start, end))) =>
        val slice = bytes.slice(start, end + 1)
        exchange.getResponseHeaders.set("Content-Range", s"bytes $start-$end/${bytes.length}")
        respond(exchange, 206, slice)
      case Right(None) if contentType == "application/vnd.apple.mpegurl" && acceptsGzip(exchange) =>
        exchange.getResponseHeaders.set("Content-Encoding", "gzip")
        exchange.getResponseHeaders.set("Vary", "Accept-Encoding")
        respond(exchange, 200, gzip(bytes))
      case Right(None) => respond(exchange, 200, bytes)

  private def respond(exchange: HttpExchange, status: Int, body: Array[Byte]): Unit =
    val length = if exchange.getRequestMethod == "HEAD" then -1 else body.length.toLong
    exchange.sendResponseHeaders(status, length)
    if length >= 0 then exchange.getResponseBody.write(body)

  private def parseRange(value: String, length: Int): Either[String, Option[(Int, Int)]] =
    Option(value) match
      case None => Right(None)
      case Some(header) if header.startsWith("bytes=") && !header.contains(',') =>
        header.drop(6).split("-", -1).toList match
          case start :: end :: Nil if start.nonEmpty =>
            for
              first <- start.toIntOption.filter(i => i >= 0 && i < length).toRight("invalid range")
              last <- (if end.isEmpty then Some(length - 1) else end.toIntOption).filter(i => i >= first && i < length).toRight("invalid range")
            yield Some(first -> last)
          case _ => Left("unsupported range")
      case _ => Left("unsupported range")

  private def acceptsGzip(exchange: HttpExchange): Boolean =
    Option(exchange.getRequestHeaders.getFirst("Accept-Encoding")).exists(_.split(',').exists(_.trim.startsWith("gzip")))

  private def gzip(bytes: Array[Byte]): Array[Byte] =
    val output = java.io.ByteArrayOutputStream()
    val gzip = GZIPOutputStream(output)
    gzip.write(bytes)
    gzip.close()
    output.toByteArray

  private def mediaType(path: Path): String = path.getFileName.toString.toLowerCase match
    case name if name.endsWith(".m3u8") => "application/vnd.apple.mpegurl"
    case name if name.endsWith(".ts") => "video/mp2t"
    case name if name.endsWith(".m4s") => "video/iso.segment"
    case name if name.endsWith(".mp4") => "video/mp4"
    case name if name.endsWith(".aac") => "audio/aac"
    case name if name.endsWith(".vtt") => "text/vtt"
    case _ => "application/octet-stream"

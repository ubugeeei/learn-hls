package hls.client

import hls.model.Playlist
import hls.parser.{ParseError, PlaylistParser}
import java.io.{ByteArrayInputStream, ByteArrayOutputStream}
import java.net.URI
import java.net.http.{HttpClient, HttpRequest, HttpResponse}
import java.nio.charset.{CodingErrorAction, StandardCharsets}
import java.time.{Duration, Instant}
import java.util.zip.GZIPInputStream
import scala.util.{Try, Using}

/** A successfully fetched, parsed, validated, and URI-resolved playlist. */
final case class PlaylistSnapshot(
    requestedUri: URI,
    effectiveUri: URI,
    playlist: Playlist,
    entityTag: Option[String],
    lastModified: Option[String],
    fetchedAt: Instant
)

/** Typed failures produced before a playlist can become a snapshot. */
enum ClientError:
  case Transport(uri: URI, cause: Throwable)
  case UnexpectedStatus(uri: URI, status: Int)
  case BodyTooLarge(uri: URI, maximumBytes: Int)
  case UnsupportedEncoding(uri: URI, encoding: String)
  case InvalidUtf8(uri: URI)
  case InvalidPlaylist(uri: URI, error: ParseError)

/** Result of a conditional reload. */
enum ReloadResult:
  case Modified(snapshot: PlaylistSnapshot)
  case NotModified(snapshot: PlaylistSnapshot)

/**
 * Production-shaped synchronous HTTP client for HLS playlists.
 *
 * The client applies bounded request timeouts, bounded response bodies, explicit UTF-8 decoding,
 * gzip decoding, redirect policy, conditional GET, parsing, semantic validation, and URI
 * resolution. Network calls are synchronous by design; callers can place them in their preferred
 * effect or execution abstraction without this library choosing one.
 */
final class HlsClient private (
    http: HttpClient,
    requestTimeout: Duration,
    maximumBytes: Int,
    clock: () => Instant
):
  /**
   * Loads a playlist without prior cache metadata.
   *
   * @return
   *   a fully validated snapshot or a typed failure; network and parsing failures are never thrown
   *   by this method
   */
  def load(uri: URI): Either[ClientError, PlaylistSnapshot] = request(uri, None).flatMap:
    case Response.NotModified => Left(ClientError.UnexpectedStatus(uri, 304))
    case Response.Content(effectiveUri, headers, bytes) => decode(uri, effectiveUri, headers, bytes)

  /**
   * Revalidates a previous snapshot using `ETag` or `Last-Modified`.
   *
   * `ETag` takes precedence, matching HTTP conditional request semantics. A 304 response returns
   * the exact previous immutable snapshot.
   */
  def reload(previous: PlaylistSnapshot): Either[ClientError, ReloadResult] =
    request(previous.effectiveUri, Some(previous)).flatMap:
      case Response.NotModified => Right(ReloadResult.NotModified(previous))
      case Response.Content(effectiveUri, headers, bytes) =>
        decode(previous.requestedUri, effectiveUri, headers, bytes).map(ReloadResult.Modified(_))

  private enum Response:
    case NotModified
    case Content(effectiveUri: URI, headers: java.net.http.HttpHeaders, bytes: Array[Byte])

  private def request(uri: URI, previous: Option[PlaylistSnapshot]): Either[ClientError, Response] =
    val builder = HttpRequest
      .newBuilder(uri)
      .timeout(requestTimeout)
      .header(
        "Accept",
        "application/vnd.apple.mpegurl, application/x-mpegURL, audio/mpegurl, */*;q=0.1"
      )
      .header("Accept-Encoding", "gzip")
      .header("User-Agent", "learn-hls/0.1")
      .GET()
    previous.flatMap(_.entityTag).foreach(builder.header("If-None-Match", _))
    if previous.flatMap(_.entityTag).isEmpty then
      previous.flatMap(_.lastModified).foreach(builder.header("If-Modified-Since", _))
    Try(http.send(builder.build(), HttpResponse.BodyHandlers.ofByteArray())).toEither.left
      .map(ClientError.Transport(uri, _))
      .flatMap: response =>
        response.statusCode() match
          case 304 if previous.nonEmpty                => Right(Response.NotModified)
          case status if status >= 200 && status < 300 =>
            Either.cond(
              response.body().length <= maximumBytes,
              Response.Content(response.uri(), response.headers(), response.body()),
              ClientError.BodyTooLarge(response.uri(), maximumBytes)
            )
          case status => Left(ClientError.UnexpectedStatus(response.uri(), status))

  private def decode(
      requested: URI,
      effective: URI,
      headers: java.net.http.HttpHeaders,
      body: Array[Byte]
  ): Either[ClientError, PlaylistSnapshot] =
    for
      decompressed <- contentEncoding(headers) match
        case None | Some("identity") => Right(body)
        case Some("gzip")            => gunzip(body).left.map(ClientError.Transport(effective, _))
        case Some(other)             => Left(ClientError.UnsupportedEncoding(effective, other))
      _ <- Either.cond(
        decompressed.length <= maximumBytes,
        (),
        ClientError.BodyTooLarge(effective, maximumBytes)
      )
      text   <- utf8(decompressed).toRight(ClientError.InvalidUtf8(effective))
      parsed <- PlaylistParser.parse(text).left.map(ClientError.InvalidPlaylist(effective, _))
    yield PlaylistSnapshot(
      requested,
      effective,
      PlaylistResolver.resolve(parsed, effective),
      firstHeader(headers, "ETag"),
      firstHeader(headers, "Last-Modified"),
      clock()
    )

  private def contentEncoding(headers: java.net.http.HttpHeaders): Option[String] =
    firstHeader(headers, "Content-Encoding").map(_.trim.toLowerCase)

  private def firstHeader(headers: java.net.http.HttpHeaders, name: String): Option[String] =
    Option(headers.firstValue(name).orElse(null))

  private def utf8(bytes: Array[Byte]): Option[String] =
    Try(
      StandardCharsets.UTF_8
        .newDecoder()
        .onMalformedInput(CodingErrorAction.REPORT)
        .onUnmappableCharacter(CodingErrorAction.REPORT)
        .decode(java.nio.ByteBuffer.wrap(bytes))
        .toString
    ).toOption

  private def gunzip(bytes: Array[Byte]): Either[Throwable, Array[Byte]] = Try:
    Using.resource(GZIPInputStream(ByteArrayInputStream(bytes))): input =>
      val output = ByteArrayOutputStream()
      val buffer = Array.ofDim[Byte](8192)
      var read   = input.read(buffer)
      var total  = 0
      while read >= 0 do
        total += read
        if total > maximumBytes then
          throw IllegalArgumentException("decompressed playlist exceeds maximum size")
        output.write(buffer, 0, read)
        read = input.read(buffer)
      output.toByteArray
  .toEither

object HlsClient:
  /**
   * Constructs an HTTP playlist client with bounded time and memory use.
   *
   * @param connectTimeout
   *   maximum time for establishing a connection
   * @param requestTimeout
   *   maximum time for one complete playlist request
   * @param maximumBytes
   *   maximum compressed and decompressed playlist size
   */
  def create(
      connectTimeout: Duration = Duration.ofSeconds(5),
      requestTimeout: Duration = Duration.ofSeconds(10),
      maximumBytes: Int = 2 * 1024 * 1024
  ): HlsClient =
    require(
      !connectTimeout.isNegative && !connectTimeout.isZero,
      "connect timeout must be positive"
    )
    require(
      !requestTimeout.isNegative && !requestTimeout.isZero,
      "request timeout must be positive"
    )
    require(maximumBytes > 0, "maximum bytes must be positive")
    val http = HttpClient
      .newBuilder()
      .connectTimeout(connectTimeout)
      .followRedirects(HttpClient.Redirect.NORMAL)
      .build()
    HlsClient(http, requestTimeout, maximumBytes, () => Instant.now())

  private[client] def testing(
      http: HttpClient,
      maximumBytes: Int,
      clock: () => Instant
  ): HlsClient =
    HlsClient(http, Duration.ofSeconds(2), maximumBytes, clock)

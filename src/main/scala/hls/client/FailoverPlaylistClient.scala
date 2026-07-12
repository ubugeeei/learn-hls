package hls.client

import java.net.URI
import java.time.{Duration, Instant}

/** Retry/backoff policy for redundant playlist origins. */
final case class FailoverPolicy(
    initialCooldown: Duration = Duration.ofSeconds(1),
    maximumCooldown: Duration = Duration.ofSeconds(30)
):
  require(
    !initialCooldown.isNegative && !initialCooldown.isZero,
    "initial cooldown must be positive"
  )
  require(maximumCooldown.compareTo(initialCooldown) >= 0, "maximum cooldown must not be smaller")

/** Health state for one redundant playlist URI. */
final case class OriginHealth(
    uri: URI,
    consecutiveFailures: Int = 0,
    retryAfter: Option[Instant] = None
)

/** Immutable failover state persisted by the caller between operations. */
final case class FailoverState private[client] (
    origins: Vector[OriginHealth],
    preferredIndex: Int
)

object FailoverState:
  /** Creates state for two or more equivalent playlist URIs. */
  def create(origins: Vector[URI]): Either[String, FailoverState] =
    Either.cond(
      origins.size >= 2 && origins.distinct.size == origins.size,
      FailoverState(origins.map(OriginHealth(_)), preferredIndex = 0),
      "failover requires at least two distinct origins"
    )

/** One failed source attempt, retained for diagnostics without losing ordering. */
final case class OriginAttempt(uri: URI, error: ClientError)

/** Failure after applying retry classification and origin health. */
enum FailoverError:
  /** A content/configuration error that trying an equivalent origin must not hide. */
  case Terminal(attempt: OriginAttempt, previousAttempts: Vector[OriginAttempt])

  /** Every eligible origin failed with a retryable error. */
  case Exhausted(attempts: Vector[OriginAttempt], state: FailoverState)

/** Successful initial load plus the next state and preceding failed attempts. */
final case class FailoverLoad(
    snapshot: PlaylistSnapshot,
    state: FailoverState,
    attempts: Vector[OriginAttempt]
)

/** Successful conditional reload/fallback plus updated failover state. */
final case class FailoverReload(
    result: ReloadResult,
    state: FailoverState,
    attempts: Vector[OriginAttempt]
)

/**
 * Deterministic redundant-origin orchestration around [[HlsClient]].
 *
 * Transport failures, timeout-like statuses, 429, and 5xx responses cool an origin down
 * exponentially and allow another equivalent URI. Invalid UTF-8, invalid playlists, unsupported
 * encodings, oversized bodies, and ordinary 4xx responses are terminal because silently
 * substituting another body can hide a publishing or authorization error. The class owns no thread
 * or mutable state.
 */
final class FailoverPlaylistClient private (client: HlsClient, policy: FailoverPolicy):
  /** Loads the first healthy origin, preferring the last successful source. */
  def load(state: FailoverState, now: Instant): Either[FailoverError, FailoverLoad] =
    attemptOrigins(state, now, (origin, _) => client.load(origin.uri)).map: success =>
      FailoverLoad(success.value, success.state, success.attempts)

  /**
   * Reloads the preferred source conditionally and performs an unconditional load when falling back
   * to a different origin.
   */
  def reload(
      previous: PlaylistSnapshot,
      state: FailoverState,
      now: Instant
  ): Either[FailoverError, FailoverReload] =
    attemptOrigins[ReloadResult](
      state,
      now,
      (origin, index) =>
        if index == state.preferredIndex then client.reload(previous)
        else client.load(origin.uri).map(snapshot => ReloadResult.Modified(snapshot))
    ).map(success => FailoverReload(success.value, success.state, success.attempts))

  private final case class Success[A](
      value: A,
      state: FailoverState,
      attempts: Vector[OriginAttempt]
  )

  private def attemptOrigins[A](
      initial: FailoverState,
      now: Instant,
      operation: (OriginHealth, Int) => Either[ClientError, A]
  ): Either[FailoverError, Success[A]] =
    var state                                    = initial
    var attempts                                 = Vector.empty[OriginAttempt]
    val order                                    = eligibleOrder(initial, now)
    var success: Option[Success[A]]              = None
    var terminal: Option[FailoverError.Terminal] = None
    order.iterator
      .takeWhile(_ => success.isEmpty && terminal.isEmpty)
      .foreach: index =>
        val origin = state.origins(index)
        operation(origin, index) match
          case Right(value) =>
            val healthy = origin.copy(consecutiveFailures = 0, retryAfter = None)
            state =
              state.copy(origins = state.origins.updated(index, healthy), preferredIndex = index)
            success = Some(Success(value, state, attempts))
          case Left(error) =>
            val attempt = OriginAttempt(origin.uri, error)
            if retryable(error) then
              attempts :+= attempt
              state = recordFailure(state, index, now)
            else terminal = Some(FailoverError.Terminal(attempt, attempts))
    success.toRight(terminal.getOrElse(FailoverError.Exhausted(attempts, state)))

  private def eligibleOrder(state: FailoverState, now: Instant): Vector[Int] =
    val preferredFirst =
      state.preferredIndex +: state.origins.indices.filterNot(_ == state.preferredIndex)
    val eligible = preferredFirst
      .filter(index => state.origins(index).retryAfter.forall(!_.isAfter(now)))
      .toVector
    if eligible.nonEmpty then eligible
    else
      Vector(
        state.origins.indices.minBy(index =>
          state.origins(index).retryAfter.map(_.toEpochMilli).getOrElse(0L)
        )
      )

  private def recordFailure(state: FailoverState, index: Int, now: Instant): FailoverState =
    val origin   = state.origins(index)
    val failures = origin.consecutiveFailures + 1
    val exponent = math.min(30, failures - 1)
    val scaled   = TryDuration.multiply(policy.initialCooldown, 1L << exponent)
    val cooldown =
      if scaled.compareTo(policy.maximumCooldown) > 0 then policy.maximumCooldown else scaled
    state.copy(origins =
      state.origins.updated(
        index,
        origin.copy(consecutiveFailures = failures, retryAfter = Some(now.plus(cooldown)))
      )
    )

  private def retryable(error: ClientError): Boolean = error match
    case ClientError.Transport(_, _)             => true
    case ClientError.UnexpectedStatus(_, status) =>
      status == 408 || status == 425 || status == 429 || status >= 500
    case _ => false

private object TryDuration:
  def multiply(duration: Duration, factor: Long): Duration =
    try duration.multipliedBy(factor)
    catch case _: ArithmeticException => Duration.ofSeconds(Long.MaxValue)

object FailoverPlaylistClient:
  /** Wraps a configured playlist client with immutable failover orchestration. */
  def create(client: HlsClient, policy: FailoverPolicy = FailoverPolicy()): FailoverPlaylistClient =
    FailoverPlaylistClient(client, policy)

package uk.co.cricrelay.stream

/**
 * Backoff schedule for the mid-broadcast RTMP self-heal. RootEncoder owns the socket
 * reconnect (streamClient.reTry keeps the encoder running and re-dials); this owns how
 * long each attempt waits and when the engine gives up and tears the session down.
 */
object StreamReconnectPolicy {
    // 5 attempts (1s/2s/4s/8s/8s ≈ 30-45s with connect timeouts): a real dropout at a ground
    // runs 10-60s, and YouTube/Twitch hold the broadcast session server-side for over a
    // minute — 3 attempts (~9s) proved too short in the field (2026-07-03 smoke test).
    const val MAX_ATTEMPTS = 5
    private const val BASE_DELAY_MS = 1_000L
    private const val MAX_DELAY_MS = 8_000L

    /** Delay before the (0-based) [attempt]: 1s, 2s, 4s… capped at [MAX_DELAY_MS]. */
    fun backoffMs(attempt: Int): Long =
        (BASE_DELAY_MS shl attempt.coerceIn(0, 30)).coerceAtMost(MAX_DELAY_MS)
}

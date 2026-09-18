package uk.co.cricrelay.shared.remote

/**
 * Privacy-safe remote-control telemetry. No tokens, emails, URLs, or free-text errors —
 * only event names and integer timings / counters suitable for later aggregation.
 */
object RemoteControlMetrics {
    /** Allowed event names (keep this list short and stable). */
    val ALLOWED = setOf(
        "pair_ok",
        "pair_fail",
        "preview_first_frame_ms",
        "command_ack_ms",
        "command_ack_timeout",
        "take_live",
        "share_watch",
    )

    data class Event(
        val name: String,
        val value: Int? = null,
    )

    fun sanitize(name: String, value: Int? = null): Event? {
        val key = name.trim().lowercase()
        if (key !in ALLOWED) return null
        val clamped = value?.coerceIn(0, 600_000)
        return Event(key, clamped)
    }
}

expect fun remoteMetricsNowMs(): Long

/**
 * Session-scoped collector for companion remote control. Timings are relative to
 * [markPairSuccess] / [markCommandSent]; nothing personally identifying is retained.
 */
class RemoteControlSessionMetrics(
    private val emit: (RemoteControlMetrics.Event) -> Unit = {},
) {
    private var pairedAtMs: Long? = null
    private var firstPreviewLogged = false
    private var pendingCommandAtMs: Long? = null

    fun markPairSuccess(nowMs: Long = remoteMetricsNowMs()) {
        pairedAtMs = nowMs
        firstPreviewLogged = false
        emit(RemoteControlMetrics.Event("pair_ok"))
    }

    fun markPairFailure() {
        emit(RemoteControlMetrics.Event("pair_fail"))
    }

    fun onPreviewFrame(nowMs: Long = remoteMetricsNowMs()) {
        val start = pairedAtMs ?: return
        if (firstPreviewLogged) return
        firstPreviewLogged = true
        val ms = (nowMs - start).toInt().coerceAtLeast(0)
        emit(RemoteControlMetrics.Event("preview_first_frame_ms", ms))
    }

    fun markCommandSent(nowMs: Long = remoteMetricsNowMs()) {
        pendingCommandAtMs = nowMs
    }

    fun markCommandAcked(nowMs: Long = remoteMetricsNowMs()) {
        val start = pendingCommandAtMs ?: return
        val ms = (nowMs - start).toInt().coerceAtLeast(0)
        pendingCommandAtMs = null
        emit(RemoteControlMetrics.Event("command_ack_ms", ms))
    }

    fun markCommandTimeout() {
        if (pendingCommandAtMs == null) return
        pendingCommandAtMs = null
        emit(RemoteControlMetrics.Event("command_ack_timeout"))
    }

    fun markTakeLive() = emit(RemoteControlMetrics.Event("take_live"))

    fun markShareWatch() = emit(RemoteControlMetrics.Event("share_watch"))
}

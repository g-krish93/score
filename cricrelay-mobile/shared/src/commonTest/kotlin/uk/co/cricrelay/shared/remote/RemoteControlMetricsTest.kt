package uk.co.cricrelay.shared.remote

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class RemoteControlMetricsTest {
    @Test
    fun sanitize_allows_known_events_and_clamps() {
        val ok = RemoteControlMetrics.sanitize("pair_ok")
        assertEquals("pair_ok", ok?.name)
        assertNull(ok?.value)

        val timed = RemoteControlMetrics.sanitize("preview_first_frame_ms", 1_500)
        assertEquals(1_500, timed?.value)

        val clamped = RemoteControlMetrics.sanitize("command_ack_ms", 999_999)
        assertEquals(600_000, clamped?.value)

        assertNull(RemoteControlMetrics.sanitize("user_email"))
        assertNull(RemoteControlMetrics.sanitize("pair_ok_extra"))
    }

    @Test
    fun session_tracks_pair_preview_and_ack_latency() {
        val seen = mutableListOf<RemoteControlMetrics.Event>()
        val session = RemoteControlSessionMetrics { seen += it }

        session.markPairSuccess(nowMs = 1_000)
        session.onPreviewFrame(nowMs = 1_450)
        session.onPreviewFrame(nowMs = 1_800) // ignored after first
        session.markCommandSent(nowMs = 2_000)
        session.markCommandAcked(nowMs = 2_220)

        assertEquals(
            listOf("pair_ok", "preview_first_frame_ms", "command_ack_ms"),
            seen.map { it.name },
        )
        assertEquals(450, seen[1].value)
        assertEquals(220, seen[2].value)
    }

    @Test
    fun session_timeout_clears_pending_ack() {
        val seen = mutableListOf<RemoteControlMetrics.Event>()
        val session = RemoteControlSessionMetrics { seen += it }
        session.markCommandSent(nowMs = 10)
        session.markCommandTimeout()
        session.markCommandAcked(nowMs = 99) // no pending — no event
        assertTrue(seen.any { it.name == "command_ack_timeout" })
        assertEquals(1, seen.size)
    }
}

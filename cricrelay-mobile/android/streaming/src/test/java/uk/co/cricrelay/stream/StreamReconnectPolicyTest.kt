package uk.co.cricrelay.stream

import org.junit.Assert.assertEquals
import org.junit.Test

class StreamReconnectPolicyTest {

    @Test
    fun `backoff doubles per attempt`() {
        assertEquals(1_000L, StreamReconnectPolicy.backoffMs(0))
        assertEquals(2_000L, StreamReconnectPolicy.backoffMs(1))
        assertEquals(4_000L, StreamReconnectPolicy.backoffMs(2))
    }

    @Test
    fun `backoff is capped for late attempts`() {
        assertEquals(8_000L, StreamReconnectPolicy.backoffMs(3))
        assertEquals(8_000L, StreamReconnectPolicy.backoffMs(10))
    }

    @Test
    fun `negative attempt clamps to the base delay`() {
        assertEquals(1_000L, StreamReconnectPolicy.backoffMs(-1))
    }

    @Test
    fun `huge attempt does not overflow the shift`() {
        assertEquals(8_000L, StreamReconnectPolicy.backoffMs(Int.MAX_VALUE))
    }
}

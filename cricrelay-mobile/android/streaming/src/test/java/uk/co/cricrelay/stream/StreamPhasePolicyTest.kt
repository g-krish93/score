package uk.co.cricrelay.stream

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import uk.co.cricrelay.stream.StreamPhasePolicy.Intent

class StreamPhasePolicyTest {

    @Test
    fun `prepare moves idle to prepared and is idempotent`() {
        assertEquals(StreamPhase.Prepared, StreamPhasePolicy.next(StreamPhase.Idle, Intent.Prepare))
        assertEquals(StreamPhase.Prepared, StreamPhasePolicy.next(StreamPhase.Prepared, Intent.Prepare))
    }

    @Test
    fun `prepare is refused while live — mid-stream re-prepare is the golden-path crash`() {
        assertNull(StreamPhasePolicy.next(StreamPhase.Live(), Intent.Prepare))
        assertNull(StreamPhasePolicy.next(StreamPhase.Live(background = true), Intent.Prepare))
    }

    @Test
    fun `release tears down a prepared pipeline`() {
        assertEquals(StreamPhase.Idle, StreamPhasePolicy.next(StreamPhase.Prepared, Intent.Release))
        assertEquals(StreamPhase.Idle, StreamPhasePolicy.next(StreamPhase.Idle, Intent.Release))
    }

    @Test
    fun `release is refused while live — surface-loss race must not kill the encoder`() {
        // Regression guard: a second surface-loss callback while background rendering used to
        // clear encoderPrepared under a live broadcast.
        assertNull(StreamPhasePolicy.next(StreamPhase.Live(background = true), Intent.Release))
        assertNull(StreamPhasePolicy.next(StreamPhase.Live(), Intent.Release))
    }

    @Test
    fun `go live requires a prepared pipeline`() {
        assertEquals(StreamPhase.Live(), StreamPhasePolicy.next(StreamPhase.Prepared, Intent.GoLive))
        assertNull(StreamPhasePolicy.next(StreamPhase.Idle, Intent.GoLive))
        assertNull(StreamPhasePolicy.next(StreamPhase.Live(), Intent.GoLive))
    }

    @Test
    fun `stop ends any live variant and is idempotent when down`() {
        assertEquals(StreamPhase.Idle, StreamPhasePolicy.next(StreamPhase.Live(), Intent.Stop))
        assertEquals(
            StreamPhase.Idle,
            StreamPhasePolicy.next(StreamPhase.Live(paused = true, background = true), Intent.Stop),
        )
        assertEquals(StreamPhase.Prepared, StreamPhasePolicy.next(StreamPhase.Prepared, Intent.Stop))
        assertEquals(StreamPhase.Idle, StreamPhasePolicy.next(StreamPhase.Idle, Intent.Stop))
    }

    @Test
    fun `pause and resume only exist within live and preserve the background flag`() {
        assertEquals(
            StreamPhase.Live(paused = true, background = true),
            StreamPhasePolicy.next(StreamPhase.Live(background = true), Intent.Pause),
        )
        assertEquals(
            StreamPhase.Live(paused = false, background = true),
            StreamPhasePolicy.next(StreamPhase.Live(paused = true, background = true), Intent.Resume),
        )
        assertNull(StreamPhasePolicy.next(StreamPhase.Prepared, Intent.Pause))
        assertNull(StreamPhasePolicy.next(StreamPhase.Idle, Intent.Resume))
    }

    @Test
    fun `background swap only exists within live and preserves the paused flag`() {
        assertEquals(
            StreamPhase.Live(paused = true, background = true),
            StreamPhasePolicy.next(StreamPhase.Live(paused = true), Intent.EnterBackground),
        )
        assertEquals(
            StreamPhase.Live(paused = true, background = false),
            StreamPhasePolicy.next(StreamPhase.Live(paused = true, background = true), Intent.ExitBackground),
        )
        assertNull(StreamPhasePolicy.next(StreamPhase.Prepared, Intent.EnterBackground))
        assertNull(StreamPhasePolicy.next(StreamPhase.Idle, Intent.ExitBackground))
    }
}

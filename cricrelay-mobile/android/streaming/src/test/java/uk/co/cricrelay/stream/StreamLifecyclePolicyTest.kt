package uk.co.cricrelay.stream

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class StreamLifecyclePolicyTest {

    @Test
    fun `surface present renders on view`() {
        assertEquals(
            StreamLifecyclePolicy.RenderTarget.OnView,
            StreamLifecyclePolicy.renderTarget(surfacePresent = true, inPip = false),
        )
    }

    @Test
    fun `pip renders on view even without a tracked surface flag`() {
        assertEquals(
            StreamLifecyclePolicy.RenderTarget.OnView,
            StreamLifecyclePolicy.renderTarget(surfacePresent = false, inPip = true),
        )
    }

    @Test
    fun `no surface and no pip renders offscreen`() {
        assertEquals(
            StreamLifecyclePolicy.RenderTarget.Offscreen,
            StreamLifecyclePolicy.renderTarget(surfacePresent = false, inPip = false),
        )
    }

    @Test
    fun `enter background only while streaming with no surface and not in pip`() {
        assertTrue(
            StreamLifecyclePolicy.shouldEnterBackground(
                isStreaming = true,
                surfacePresent = false,
                inPip = false,
            ),
        )
        // Not streaming: nothing to keep alive.
        assertFalse(
            StreamLifecyclePolicy.shouldEnterBackground(
                isStreaming = false,
                surfacePresent = false,
                inPip = false,
            ),
        )
        // PiP keeps a real surface — stay on view.
        assertFalse(
            StreamLifecyclePolicy.shouldEnterBackground(
                isStreaming = true,
                surfacePresent = false,
                inPip = true,
            ),
        )
        // Surface is fine — no need to go offscreen.
        assertFalse(
            StreamLifecyclePolicy.shouldEnterBackground(
                isStreaming = true,
                surfacePresent = true,
                inPip = false,
            ),
        )
    }

    @Test
    fun `exit background only when streaming, currently offscreen, and surface returned`() {
        assertTrue(
            StreamLifecyclePolicy.shouldExitBackground(
                isStreaming = true,
                backgroundRendering = true,
                surfacePresent = true,
            ),
        )
        // Already on view — nothing to restore.
        assertFalse(
            StreamLifecyclePolicy.shouldExitBackground(
                isStreaming = true,
                backgroundRendering = false,
                surfacePresent = true,
            ),
        )
        // Surface not back yet — wait.
        assertFalse(
            StreamLifecyclePolicy.shouldExitBackground(
                isStreaming = true,
                backgroundRendering = true,
                surfacePresent = false,
            ),
        )
    }

    @Test
    fun `pip on leave only while streaming and supported`() {
        assertTrue(StreamLifecyclePolicy.shouldEnterPipOnLeave(isStreaming = true, pipSupported = true))
        assertFalse(StreamLifecyclePolicy.shouldEnterPipOnLeave(isStreaming = false, pipSupported = true))
        assertFalse(StreamLifecyclePolicy.shouldEnterPipOnLeave(isStreaming = true, pipSupported = false))
    }
}

package uk.co.cricrelay.stream

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class OverlayCaptureDimensionsTest {

    @Test
    fun `compute uses taller reference height than legacy strip`() {
        val (_, h) = OverlayCaptureDimensions.compute(1080)
        assertTrue("capture height should fit full relay board", h >= 250)
    }

    @Test
    fun `compute scales with canvas width`() {
        val (w720, h720) = OverlayCaptureDimensions.compute(720)
        val (w1080, h1080) = OverlayCaptureDimensions.compute(1080)
        assertTrue(w1080 > w720)
        assertTrue(h1080 > h720)
        assertEquals(
            OverlayCaptureDimensions.REF_HEIGHT_FRACTION,
            h1080.toFloat() / w1080.toFloat(),
            0.02f,
        )
    }
}

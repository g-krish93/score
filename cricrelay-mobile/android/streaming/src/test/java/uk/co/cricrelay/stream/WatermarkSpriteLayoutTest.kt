package uk.co.cricrelay.stream

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class WatermarkSpriteLayoutTest {

    private val defaults = WatermarkSpriteLayout.Params(
        canvasW = 1280,
        canvasH = 720,
        bitmapWidth = 520,
        bitmapHeight = 80,
        heightPct = 5.5f,
        rightEdgePct = 84f,
        topPct = 13f,
        maxWidthPct = 68f,
    )

    @Test
    fun landscape_rightEdgeAnchoredBelowTopCrop() {
        val s = WatermarkSpriteLayout.compute(defaults)
        assertEquals(84f, s.positionX + s.scaleX, 0.01f)
        assertEquals(13f, s.positionY, 0.01f)
        assertTrue(s.scaleX <= 68f)
    }

    @Test
    fun portrait_fitsWithinMaxWidth() {
        val s = WatermarkSpriteLayout.compute(
            defaults.copy(canvasW = 720, canvasH = 1280),
        )
        assertTrue(s.scaleX <= 68f)
        assertEquals(84f, s.positionX + s.scaleX, 0.01f)
        assertTrue(s.positionX >= 0f)
    }

    @Test
    fun portrait_matchesLandscapePixelSize() {
        val landscape = WatermarkSpriteLayout.compute(defaults)
        val portrait = WatermarkSpriteLayout.compute(
            defaults.copy(canvasW = 720, canvasH = 1280),
        )
        val landW = landscape.scaleX / 100f * defaults.canvasW
        val landH = landscape.scaleY / 100f * defaults.canvasH
        val portW = portrait.scaleX / 100f * 720
        val portH = portrait.scaleY / 100f * 1280
        assertEquals(landW, portW, 1f)
        assertEquals(landH, portH, 1f)
    }

    @Test
    fun longText_shrinksProportionally() {
        val wide = WatermarkSpriteLayout.compute(
            defaults.copy(
                canvasW = 720,
                canvasH = 1280,
                bitmapWidth = 2000,
            ),
        )
        assertEquals(68f, wide.scaleX, 0.01f)
        assertTrue(wide.scaleY < defaults.heightPct)
    }
}

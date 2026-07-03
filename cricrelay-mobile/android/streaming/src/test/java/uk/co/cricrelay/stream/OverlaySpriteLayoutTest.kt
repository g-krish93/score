package uk.co.cricrelay.stream

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class OverlaySpriteLayoutTest {

    @Test
    fun `defaultScale matches RootEncoder setDefaultScale integer math`() {
        // Replaces filter.setDefaultScale (which reads the recycle()'d bitmap); must reproduce
        // RootEncoder 2.4.8's `getWidth() * 100 / streamWidth` — integer division, truncated
        // BEFORE the int-to-float cast, so 340*100/720 = 47, not 47.2.
        val s = OverlaySpriteLayout.defaultScale(1280, 340, 1280, 720)
        assertEquals(100f, s.x, 0f)
        assertEquals("y must truncate like RootEncoder's idiv", 47f, s.y, 0f)
    }

    @Test
    fun `defaultScale truncates on both axes and handles oversized bitmaps`() {
        // 800x160 logo on 1280x720: 800*100/1280 = 62 (62.5 truncated), 160*100/720 = 22.
        val logo = OverlaySpriteLayout.defaultScale(800, 160, 1280, 720)
        assertEquals(62f, logo.x, 0f)
        assertEquals(22f, logo.y, 0f)
        // 1280-wide capture on a 720-wide portrait canvas: base may exceed 100% (fitScale
        // shrinks it later), exactly as RootEncoder's setDefaultScale behaves.
        val portrait = OverlaySpriteLayout.defaultScale(1280, 340, 720, 1280)
        assertEquals(177f, portrait.x, 0f)
        assertEquals(26f, portrait.y, 0f)
    }

    @Test
    fun `computePosition places cricket scoreboard strip near bottom`() {
        val pos = OverlaySpriteLayout.computePosition(
            OverlaySpriteLayout.Params(
                scaleX = 88f,
                scaleY = 22f,
                anchorX = 0.5f,
                anchorY = 0.85f,
                bottomMarginFraction = 0.02f,
                horizontalInsetFraction = 0.02f,
            ),
        )
        assertTrue("scoreboard Y should be in lower half, was ${pos.y}", pos.y >= 70f)
        assertTrue("scoreboard X should be centered-ish, was ${pos.x}", pos.x in 1f..15f)
    }

    @Test
    fun `higher bottom margin moves scoreboard up`() {
        val low = OverlaySpriteLayout.computePosition(
            OverlaySpriteLayout.Params(
                scaleX = 88f,
                scaleY = 22f,
                bottomMarginFraction = 0f,
            ),
        )
        val high = OverlaySpriteLayout.computePosition(
            OverlaySpriteLayout.Params(
                scaleX = 88f,
                scaleY = 22f,
                bottomMarginFraction = 48f / 720f,
            ),
        )
        assertTrue("higher margin should reduce Y, low=${low.y} high=${high.y}", high.y < low.y)
    }

    @Test
    fun `zero bottom margin puts the strip flush to the frame bottom`() {
        // Part 1 fix: with the transparent band trimmed and bottomMargin 0, the sprite's bottom
        // edge (posY + scaleY) must equal 100 — the frame bottom — so the board hugs it.
        val scaleY = 20f
        val pos = OverlaySpriteLayout.computePosition(
            OverlaySpriteLayout.Params(scaleX = 88f, scaleY = scaleY, bottomMarginFraction = 0f),
        )
        assertTrue("bottom edge should be flush, posY=${pos.y}", pos.y + scaleY in 99.9f..100.1f)
    }

    @Test
    fun `uniform board scale preserves aspect at any multiplier`() {
        // Part 2: pinch feeds one multiplier to both axes; aspect ratio must be invariant.
        val baseX = 100f
        val baseY = 12f
        val baseAspect = baseY / baseX
        for (mul in listOf(0.4f, 0.6f, 0.8f, 1.0f)) {
            val s = OverlaySpriteLayout.fitScale(baseX, baseY, mul, mul, maxPercent = 100f)
            val aspect = s.y / s.x
            assertTrue(
                "aspect must hold at mul=$mul ($baseAspect vs $aspect)",
                kotlin.math.abs(baseAspect - aspect) < 0.01f,
            )
        }
    }

    @Test
    fun `computePosition stays within sprite percent bounds`() {
        val pos = OverlaySpriteLayout.computePosition(
            OverlaySpriteLayout.Params(scaleX = 40f, scaleY = 18f),
        )
        assertTrue(pos.x in 0f..100f)
        assertTrue(pos.y in 0f..100f)
    }

    @Test
    fun `legacy gl normalized coords would be invalid for Sprite API`() {
        // Regression guard: old code passed values like (0.5f - 0.87f) * 2 = -0.74
        val legacyY = (0.5f - 0.87f) * 2f
        assertTrue("legacy formula produced negative Y", legacyY < 0f)
        val fixed = OverlaySpriteLayout.computePosition(
            OverlaySpriteLayout.Params(scaleX = 88f, scaleY = 22f, anchorY = 0.87f),
        )
        assertFalse("fixed formula must not use negative coords", fixed.y < 0f)
    }

    @Test
    fun `fitScale keeps native size when it fits`() {
        // 1280x340 bitmap on a 1280x720 canvas => base 100% x 47.2%, default multipliers.
        val s = OverlaySpriteLayout.fitScale(100f, 47.2f, 1f, 1f, maxPercent = 100f)
        assertTrue(kotlin.math.abs(s.x - 100f) < 0.01f)
        assertTrue(kotlin.math.abs(s.y - 47.2f) < 0.01f)
    }

    @Test
    fun `fitScale shrinks both axes proportionally on overflow`() {
        // 1280px-wide bitmap on a 720px-wide portrait canvas => base x = 177.8%.
        val s = OverlaySpriteLayout.fitScale(177.8f, 26.5f, 1f, 1f)
        assertTrue("x must be clamped, was ${s.x}", s.x <= 96f)
        val aspectBefore = 26.5f / 177.8f
        val aspectAfter = s.y / s.x
        assertTrue(
            "aspect ratio must be preserved ($aspectBefore vs $aspectAfter)",
            kotlin.math.abs(aspectBefore - aspectAfter) < 0.01f,
        )
    }

    @Test
    fun `fitScale never returns values outside sprite percent range`() {
        val s = OverlaySpriteLayout.fitScale(75f, 40f, 2f, 3f)
        assertTrue(s.x in 1f..100f)
        assertTrue(s.y in 1f..100f)
    }

    @Test
    fun `shouldForceTransparentBackground detects compose hosts`() {
        assertTrue(
            OverlaySpriteLayout.shouldForceTransparentBackground(
                "androidx.compose.ui.platform.AndroidComposeView",
            ),
        )
        assertFalse(
            OverlaySpriteLayout.shouldForceTransparentBackground(
                "com.pedro.library.view.OpenGlView",
            ),
        )
    }
}

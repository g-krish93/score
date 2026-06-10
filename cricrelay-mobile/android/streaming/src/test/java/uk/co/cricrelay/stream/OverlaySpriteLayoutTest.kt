package uk.co.cricrelay.stream

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class OverlaySpriteLayoutTest {

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

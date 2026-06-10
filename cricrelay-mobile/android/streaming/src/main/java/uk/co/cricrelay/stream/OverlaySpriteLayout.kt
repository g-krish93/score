package uk.co.cricrelay.stream

/**
 * RootEncoder [com.pedro.encoder.input.gl.Sprite] uses 0–100% screen coordinates
 * (0,0 = top-left; 100,100 = bottom-right). This helper keeps positioning testable.
 */
object OverlaySpriteLayout {

    data class SpritePosition(val x: Float, val y: Float)

    data class Params(
        val scaleX: Float,
        val scaleY: Float,
        val anchorX: Float = 0.5f,
        val anchorY: Float = 0.85f,
        val bottomMarginFraction: Float = 0.02f,
        val horizontalInsetFraction: Float = 0.02f,
    )

    fun computePosition(params: Params): SpritePosition {
        val scaleX = params.scaleX.coerceIn(1f, 100f)
        val scaleY = params.scaleY.coerceIn(1f, 100f)
        val insetX = params.horizontalInsetFraction * 100f
        val bottomMargin = params.bottomMarginFraction * 100f
        // When the sprite is (near) full width, the valid X range collapses. Build the
        // range so min never exceeds max — otherwise coerceIn throws and the sprite is
        // left at its default top-left position (the "scoreboard pinned to the top" bug
        // seen with full-width portrait overlays).
        val maxX = (100f - scaleX - insetX).coerceAtLeast(0f)
        val minX = insetX.coerceAtMost(maxX)
        val posX = (params.anchorX * 100f - scaleX / 2f).coerceIn(minX, maxX)
        val maxY = (100f - scaleY).coerceAtLeast(0f)
        val posY = (100f - scaleY - bottomMargin).coerceIn(0f, maxY)
        return SpritePosition(posX, posY)
    }

    /** Returns true if [viewClassName] looks like a Compose host that must be transparent. */
    fun shouldForceTransparentBackground(viewClassName: String): Boolean {
        return viewClassName.contains("Compose", ignoreCase = true) ||
            viewClassName.contains("AndroidComposeView", ignoreCase = true)
    }
}

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

    data class SpriteScale(val x: Float, val y: Float)

    /**
     * The base scale RootEncoder's `BaseObjectFilterRender.setDefaultScale` would set —
     * bitmap size as a percentage of the canvas, INTEGER division included (verified against
     * the 2.4.8 bytecode: `getWidth() * 100 / streamWidth` truncates before the int-to-float
     * cast). Computed locally from dimensions cached at setImage time because RootEncoder's
     * TextureLoader recycles the bitmap right after the GL upload, so calling setDefaultScale
     * afterwards reads a recycle()'d bitmap and logs a warning per call.
     */
    fun defaultScale(bitmapWidth: Int, bitmapHeight: Int, canvasW: Int, canvasH: Int): SpriteScale =
        SpriteScale(
            (bitmapWidth * 100 / canvasW).toFloat(),
            (bitmapHeight * 100 / canvasH).toFloat(),
        )

    /**
     * Scale the sprite from its native (base) size by the user width/height multipliers,
     * shrinking BOTH axes proportionally if the result would overflow the frame — this
     * preserves the bitmap aspect ratio (e.g. a 1280px-wide capture on a 720px-wide
     * portrait canvas must not be squashed horizontally only).
     */
    fun fitScale(
        baseX: Float,
        baseY: Float,
        wMul: Float,
        hMul: Float,
        maxPercent: Float = 96f,
    ): SpriteScale {
        var sx = (baseX * wMul).coerceAtLeast(1f)
        var sy = (baseY * hMul).coerceAtLeast(1f)
        val overflow = maxOf(sx / maxPercent, sy / 100f)
        if (overflow > 1f) {
            sx /= overflow
            sy /= overflow
        }
        return SpriteScale(sx.coerceIn(1f, 100f), sy.coerceIn(1f, 100f))
    }

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

package uk.co.cricrelay.stream

/**
 * Viewport size for rasterizing the scoreboard WebView before it is scaled onto the
 * stream/preview. Height must fit the tallest relay widget (batters row), not just the
 * compact pre-match placeholder.
 */
object OverlayCaptureDimensions {
    const val REF_WIDTH_FRACTION = 0.92f
    /** Tall enough for relay scoreboard with batters; was 0.16 and clipped the strip. */
    const val REF_HEIGHT_FRACTION = 0.28f

    fun compute(canvasWidth: Int, maxCaptureWidth: Int = 1920): Pair<Int, Int> {
        val w = (canvasWidth * REF_WIDTH_FRACTION).toInt()
            .coerceIn(720, maxCaptureWidth.coerceAtMost(1920))
        val h = (w * REF_HEIGHT_FRACTION).toInt().coerceIn(140, 640)
        return w to h
    }
}

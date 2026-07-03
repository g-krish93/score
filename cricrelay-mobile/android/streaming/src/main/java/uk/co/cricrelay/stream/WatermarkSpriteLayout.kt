package uk.co.cricrelay.stream

/**
 * Positions the brand watermark in RootEncoder's 0–100% sprite space.
 * Keeps the pill inside the aspect-fill crop-safe zone on tall phones in both orientations.
 */
object WatermarkSpriteLayout {

    // Crop-safe geometry shared by the watermark pill and the sponsor logos. The preview
    // renders AspectRatioMode.Fill (aspect-fill), so the frame edges are cropped off-screen —
    // portrait crops the sides, landscape crops top/bottom. These keep burn-ins inside the
    // crop-safe zone in BOTH orientations on tall (up to ~22:9) phones.
    internal const val HEIGHT_PCT = 5.5f
    internal const val RIGHT_EDGE_PCT = 84f
    internal const val TOP_PCT = 13f
    internal const val MAX_WIDTH_PCT = 68f
    internal const val BMP_HEIGHT = 80

    data class Sprite(
        val scaleX: Float,
        val scaleY: Float,
        val positionX: Float,
        val positionY: Float,
    )

    data class Params(
        val canvasW: Int,
        val canvasH: Int,
        val bitmapWidth: Int,
        val bitmapHeight: Int,
        val heightPct: Float,
        val rightEdgePct: Float,
        val topPct: Float,
        val maxWidthPct: Float,
    )

    fun compute(params: Params): Sprite {
        require(params.canvasW > 0 && params.canvasH > 0) { "canvas size must be positive" }
        require(params.bitmapWidth > 0 && params.bitmapHeight > 0) { "bitmap size must be positive" }
        val aspect = params.bitmapWidth.toFloat() / params.bitmapHeight
        // Calibrate height against the short side so the pill keeps the same pixel size in
        // portrait and landscape — heightPct is tuned for 720p landscape and must not balloon
        // when the encoded frame swaps to 720×1280.
        val shortSide = minOf(params.canvasW, params.canvasH)
        val heightPx = params.heightPct / 100f * shortSide
        val widthPx = heightPx * aspect
        var scaleY = heightPx / params.canvasH * 100f
        var scaleX = widthPx / params.canvasW * 100f
        if (scaleX > params.maxWidthPct) {
            val shrink = params.maxWidthPct / scaleX
            scaleX = params.maxWidthPct
            scaleY *= shrink
        }
        return Sprite(
            scaleX = scaleX,
            scaleY = scaleY,
            positionX = params.rightEdgePct - scaleX,
            positionY = params.topPct,
        )
    }
}

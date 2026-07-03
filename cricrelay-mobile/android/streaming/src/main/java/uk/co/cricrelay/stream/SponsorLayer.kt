package uk.co.cricrelay.stream

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.os.Handler
import com.pedro.encoder.input.gl.render.filters.`object`.ImageObjectFilterRender
import com.pedro.library.rtmp.RtmpCamera2
import java.util.concurrent.Executors

/**
 * Sponsor burn-ins: logo slots on the GL filter chain, disk-cached logo fetches, the carousel
 * rotation, and the marquee scroll. Extracted from [StreamCameraEngine]; owns no camera or
 * layout state — both are read through the injected providers so the engine stays the single
 * source of truth. Main-thread discipline matches the engine: every mutation runs on (or is
 * posted to) the main handler by the callers, exactly as before the extraction.
 */
internal class SponsorLayer(
    private val mainHandler: Handler,
    private val camera: () -> RtmpCamera2?,
    private val appContext: () -> Context?,
    private val layout: () -> StreamCameraEngine.OverlayLayout,
    /** Effective encoded canvas (post rotation swap), width to height. */
    private val canvasSize: () -> Pair<Int, Int>,
    /** Board sprite's real on-screen band (topPct to bottomPct) for scroll_above/below_board. */
    private val boardBand: () -> Pair<Float, Float>,
    private val applyOpacity: (Bitmap, Float) -> Bitmap,
    private val warn: (key: String, message: String) -> Unit,
) {

    private data class Slot(
        val filter: ImageObjectFilterRender,
        var bitmapWidth: Int = 160,
        var bitmapHeight: Int = 80,
    )

    private val slots = LinkedHashMap<String, Slot>()
    private val fetchInFlight = mutableSetOf<String>()
    private var scrollRunnable: Runnable? = null
    private var scrollOffsetPct: Float = 100f
    // Direction the marquee timer is currently running, so repeated starts stay idempotent.
    private var scrollActiveDir: String? = null
    private var carouselRunnable: Runnable? = null
    private var carouselUrls: List<String> = emptyList()
    private var carouselIndex = 0
    private val fetchExecutor = Executors.newSingleThreadExecutor()

    /** Attach/refresh the sponsor sprites for the current layout (or clear when disabled). */
    fun ensure() {
        val cam = camera() ?: return
        val urls = effectiveUrls()
        val l = layout()
        if (!l.sponsorEnabled || urls.isEmpty()) {
            clear()
            return
        }
        if (!cam.isOnPreview && !cam.isStreaming) return
        when (l.sponsorLayoutMode) {
            "multi" -> ensureMulti(cam, urls)
            "carousel" -> ensureCarousel(cam, urls)
            else -> ensureSingle(cam, urls.first())
        }
    }

    /** Remove every sponsor filter from the live GL chain and stop the timers. */
    fun clear() {
        stopCarousel()
        stopScroll()
        val cam = camera()
        slots.values.forEach { slot ->
            if (cam != null) {
                try {
                    cam.glInterface.removeFilter(slot.filter)
                } catch (_: Exception) {
                }
            }
        }
        slots.clear()
        fetchInFlight.clear()
    }

    /**
     * stopPreview / prepareVideo already tore down the GL filter chain — drop the references
     * (and timers) WITHOUT touching glInterface so the next [ensure] re-attaches fresh filters
     * instead of reusing detached objects.
     */
    fun dropStaleRefs() {
        slots.clear()
        fetchInFlight.clear()
        stopCarousel()
        stopScroll()
    }

    private fun effectiveUrls(): List<String> {
        val l = layout()
        val urls = l.sponsorLogoUrls.filter { it.isNotBlank() }
        if (urls.isNotEmpty()) return urls.take(6)
        return l.sponsorLogoUrl.takeIf { it.isNotBlank() }?.let { listOf(it) } ?: emptyList()
    }

    private fun isScrollMode(): Boolean = layout().sponsorDisplayMode.startsWith("scroll")

    // Direction strings arrive already sanitized from OverlayLayoutPrefs (feature/studio maps them
    // via SponsorScrollDirection); this module has no dependency on :shared, so compare locally.
    private fun scrollDirection(): String = layout().sponsorScrollDirection.trim().lowercase()

    private fun scrollAxisHorizontal(): Boolean =
        scrollDirection().let { it == SCROLL_DIR_LTR || it == SCROLL_DIR_RTL }

    private fun visibleUrls(): List<String> {
        val all = effectiveUrls()
        return when (layout().sponsorLayoutMode) {
            "carousel" -> all.take(1)
            else -> all
        }
    }

    /** Re-apply every visible sponsor sprite at the current [scrollOffsetPct]. */
    private fun refreshScrollFrame() {
        val urls = visibleUrls()
        urls.forEachIndexed { index, url ->
            slots[url]?.let { slot ->
                applySprite(slot.filter, slot, index, urls.size)
            }
        }
    }

    private fun startScroll() {
        if (!layout().sponsorEnabled || !isScrollMode()) {
            stopScroll()
            return
        }
        val dir = scrollDirection()
        // "fixed" = a scroll-band strip pinned in place (no travel): render once, no timer.
        if (dir == SCROLL_DIR_FIXED) {
            stopScroll()
            scrollActiveDir = dir
            scrollOffsetPct = 0f
            refreshScrollFrame()
            return
        }
        // Idempotent: studio init calls updateOverlay/syncSponsorLayer several times as prefs and
        // logos load. If the marquee is already running this direction, keep the current offset so
        // the logo doesn't jump back to the entry edge (the "cutting at the first few seconds" bug).
        if (scrollRunnable != null && scrollActiveDir == dir) return
        stopScroll()
        scrollActiveDir = dir
        scrollOffsetPct = 0f
        val runnable = object : Runnable {
            override fun run() {
                // A monotonic distance accumulator in RootEncoder's 0–100% canvas space. Direction
                // (which edge it enters from) is applied per-sprite in applySprite via a
                // modulo marquee, so the strip slides fully edge-to-edge and tiles seamlessly.
                // Percent-based => same visual pace on every resolution (720p/1080p).
                val speed = layout().sponsorScrollSpeed.coerceIn(0.3f, 3f)
                scrollOffsetPct += speed * SCROLL_STEP_PCT
                if (scrollOffsetPct > SCROLL_WRAP_PCT) scrollOffsetPct = 0f
                refreshScrollFrame()
                mainHandler.postDelayed(this, SCROLL_FRAME_MS)
            }
        }
        scrollRunnable = runnable
        mainHandler.post(runnable)
    }

    /**
     * Marquee position (%) for sprite [index] of [total] along a [period]-wide loop. Evenly
     * spaces the sprites around the loop so N logos form one continuous, seamless stream.
     */
    private fun marqueePhase(period: Float, index: Int, total: Int): Float {
        val spacing = period / total.coerceAtLeast(1)
        val raw = (scrollOffsetPct + index * spacing) % period
        return if (raw < 0f) raw + period else raw
    }

    private fun stopScroll() {
        scrollRunnable?.let { mainHandler.removeCallbacks(it) }
        scrollRunnable = null
        scrollActiveDir = null
    }

    private fun stopCarousel() {
        carouselRunnable?.let { mainHandler.removeCallbacks(it) }
        carouselRunnable = null
        carouselUrls = emptyList()
        carouselIndex = 0
    }

    private fun ensureSingle(cam: RtmpCamera2, url: String) {
        stopCarousel()
        syncSlotKeys(setOf(url))
        val slot = slots[url] ?: createSlot(cam, url) ?: return
        if (!fetchInFlight.contains(url)) {
            fetchAndApply(url, slot, 0, 1)
        } else {
            applySprite(slot.filter, slot, 0, 1)
        }
        if (isScrollMode()) startScroll() else stopScroll()
    }

    private fun ensureMulti(cam: RtmpCamera2, urls: List<String>) {
        stopCarousel()
        syncSlotKeys(urls.toSet())
        urls.forEachIndexed { index, url ->
            val slot = slots[url] ?: createSlot(cam, url) ?: return
            if (!fetchInFlight.contains(url)) {
                fetchAndApply(url, slot, index, urls.size)
            } else {
                applySprite(slot.filter, slot, index, urls.size)
            }
        }
        if (isScrollMode()) startScroll() else stopScroll()
    }

    private fun ensureCarousel(cam: RtmpCamera2, urls: List<String>) {
        stopCarousel()
        carouselUrls = urls
        carouselIndex = 0
        showCarouselUrl(cam, urls.first())
        if (urls.size > 1) {
            val intervalMs = (layout().sponsorCarouselIntervalSec.coerceIn(2f, 30f) * 1000).toLong()
            val runnable = object : Runnable {
                override fun run() {
                    if (layout().sponsorLayoutMode != "carousel" || carouselUrls.size <= 1) return
                    carouselIndex = (carouselIndex + 1) % carouselUrls.size
                    camera()?.let { showCarouselUrl(it, carouselUrls[carouselIndex]) }
                    carouselRunnable?.let { mainHandler.postDelayed(it, intervalMs) }
                }
            }
            carouselRunnable = runnable
            mainHandler.postDelayed(runnable, intervalMs)
        }
        if (isScrollMode()) startScroll() else stopScroll()
    }

    private fun showCarouselUrl(cam: RtmpCamera2, url: String) {
        syncSlotKeys(setOf(url))
        val slot = slots[url] ?: createSlot(cam, url) ?: return
        fetchAndApply(url, slot, 0, 1)
    }

    private fun createSlot(cam: RtmpCamera2, url: String): Slot? = try {
        val filter = ImageObjectFilterRender()
        cam.glInterface.addFilter(filter)
        Slot(filter).also { slots[url] = it }
    } catch (e: Exception) {
        CricrelayLog.w("Sponsor filter failed: ${e.message}")
        warn("sponsor_filter", "Sponsor overlay could not be added to the stream.")
        null
    }

    private fun syncSlotKeys(want: Set<String>) {
        val cam = camera() ?: return
        val remove = slots.keys.filter { it !in want }
        for (url in remove) {
            slots.remove(url)?.let { slot ->
                try {
                    cam.glInterface.removeFilter(slot.filter)
                } catch (_: Exception) {
                }
            }
        }
    }

    private fun fetchAndApply(url: String, slot: Slot, index: Int, total: Int) {
        fetchInFlight.add(url)
        fetchExecutor.execute {
            val bmp = fetchBitmap(url)
            mainHandler.post {
                fetchInFlight.remove(url)
                if (!effectiveUrls().contains(url)) return@post
                val live = slots[url] ?: return@post
                if (bmp != null) {
                    try {
                        live.bitmapWidth = bmp.width
                        live.bitmapHeight = bmp.height
                        val opaque = applyOpacity(bmp, layout().sponsorOpacity.coerceIn(0.2f, 1f))
                        live.filter.setImage(opaque)
                        applySprite(live.filter, live, index, total)
                    } catch (e: Exception) {
                        CricrelayLog.w("Sponsor image failed: ${e.message}")
                        warn("sponsor:$url", "A sponsor logo could not be drawn on the stream.")
                    }
                } else {
                    // No cached copy and the download failed — the slot stays empty and the
                    // operator would otherwise never know the sponsor is missing on air.
                    warn("sponsor:$url", "A sponsor logo failed to load and is missing from the stream.")
                }
            }
        }
    }

    /**
     * Load a sponsor logo, resilient to network/DNS blips at a ground: return the on-disk cached
     * copy immediately when present (and refresh it in the background), otherwise download once and
     * persist it. Once a logo has loaded on any prior session it keeps showing even fully offline.
     */
    private fun fetchBitmap(url: String): Bitmap? {
        val cacheFile = cacheFile(url)
        if (cacheFile != null && cacheFile.exists() && cacheFile.length() > 0L) {
            val cached = runCatching { BitmapFactory.decodeFile(cacheFile.absolutePath) }.getOrNull()
            if (cached != null) {
                // Refresh the cache for next time without blocking the current broadcast.
                fetchExecutor.execute {
                    downloadBytes(url)?.let { bytes ->
                        runCatching { cacheFile.writeBytes(bytes) }
                    }
                }
                return cached
            }
        }
        val bytes = downloadBytes(url) ?: return null
        val bmp = runCatching { BitmapFactory.decodeByteArray(bytes, 0, bytes.size) }.getOrNull()
            ?: return null
        if (cacheFile != null) runCatching { cacheFile.writeBytes(bytes) }
        return bmp
    }

    private fun downloadBytes(url: String): ByteArray? = try {
        val conn = java.net.URL(url).openConnection() as java.net.HttpURLConnection
        conn.connectTimeout = 5000
        conn.readTimeout = 5000
        conn.inputStream.use { it.readBytes() }
    } catch (e: Exception) {
        CricrelayLog.w("Sponsor logo fetch failed: ${e.message}")
        null
    }

    /** Stable per-URL cache file under the app cache dir; null if no context yet. */
    private fun cacheFile(url: String): java.io.File? {
        val ctx = appContext() ?: return null
        return try {
            val dir = java.io.File(ctx.cacheDir, "sponsor_logos").apply { mkdirs() }
            java.io.File(dir, "logo_" + Integer.toHexString(url.hashCode()) + ".img")
        } catch (_: Exception) {
            null
        }
    }

    private fun heightPct(total: Int): Float {
        val base = WatermarkSpriteLayout.HEIGHT_PCT * layout().sponsorSizeScale.coerceIn(0.3f, 3f)
        return when {
            total <= 1 -> base
            total == 2 -> base * 0.85f
            else -> base * 0.7f
        }
    }

    private fun scrollYPercent(spriteScaleY: Float): Float {
        val (boardTopPct, boardBottomPct) = boardBand()
        return when (layout().sponsorDisplayMode) {
            "scroll_top" -> 4f
            "scroll_bottom" -> 96f - spriteScaleY
            // Sit just above the board's real top edge.
            "scroll_above_board" -> (boardTopPct - spriteScaleY - 2f).coerceAtLeast(2f)
            // Just below the board's real bottom; if the board is flush to the frame bottom there is
            // no room, so tuck it into the board's lower band instead of pushing it off-screen.
            "scroll_below_board" -> (boardBottomPct + 2f).coerceAtMost(100f - spriteScaleY)
            else -> layout().sponsorPositionY * 100f
        }
    }

    private fun applySprite(
        filter: ImageObjectFilterRender,
        slot: Slot,
        index: Int = 0,
        total: Int = 1,
    ) {
        val (canvasW, canvasH) = canvasSize()
        filter.setDefaultScale(canvasW, canvasH)
        val sprite = WatermarkSpriteLayout.compute(
            WatermarkSpriteLayout.Params(
                canvasW = canvasW,
                canvasH = canvasH,
                bitmapWidth = slot.bitmapWidth.coerceAtLeast(160),
                bitmapHeight = slot.bitmapHeight.coerceAtLeast(WatermarkSpriteLayout.BMP_HEIGHT),
                heightPct = heightPct(total),
                rightEdgePct = WatermarkSpriteLayout.RIGHT_EDGE_PCT,
                topPct = WatermarkSpriteLayout.TOP_PCT,
                maxWidthPct = WatermarkSpriteLayout.MAX_WIDTH_PCT,
            ),
        )
        filter.setScale(sprite.scaleX, sprite.scaleY)
        if (isScrollMode()) {
            val dir = scrollDirection()
            if (scrollAxisHorizontal()) {
                // Horizontal ticker: X marquees edge-to-edge, Y band comes from the display mode.
                val period = 100f + sprite.scaleX + SCROLL_GAP_PCT
                val phase = marqueePhase(period, index, total)
                // rtl: enter from right (x=100) → exit left; ltr: enter from left → exit right.
                val x = if (dir == SCROLL_DIR_LTR) {
                    -sprite.scaleX - SCROLL_GAP_PCT + phase
                } else {
                    100f - phase
                }
                filter.setPosition(x, scrollYPercent(sprite.scaleY))
            } else {
                // Vertical crawl: Y marquees over the full frame, X comes from the drag position.
                val period = 100f + sprite.scaleY + SCROLL_GAP_PCT
                val phase = marqueePhase(period, index, total)
                // ttb: enter from top → exit bottom; btt: enter from bottom (y=100) → exit top.
                val y = if (dir == SCROLL_DIR_TTB) {
                    -sprite.scaleY - SCROLL_GAP_PCT + phase
                } else {
                    100f - phase
                }
                val x = (layout().sponsorPositionX.coerceIn(0f, 1f) * 100f - sprite.scaleX / 2f)
                    .coerceIn(0f, 100f - sprite.scaleX)
                filter.setPosition(x, y)
            }
        } else {
            val cx = if (total <= 1) {
                layout().sponsorPositionX.coerceIn(0f, 1f) * 100f
            } else {
                ((index + 0.5f) / total) * 100f
            }
            val cy = layout().sponsorPositionY.coerceIn(0f, 1f) * 100f
            filter.setPosition(cx - sprite.scaleX / 2f, cy - sprite.scaleY / 2f)
        }
    }

    private companion object {
        // Sponsor scroll direction tokens (mirror uk.co.cricrelay.shared.model.SponsorScrollDirection;
        // duplicated here because :streaming does not depend on :shared).
        // ltr/ttb travel forward; rtl/btt travel back (the implicit "else"); fixed = pinned.
        const val SCROLL_DIR_LTR = "ltr"
        const val SCROLL_DIR_RTL = "rtl"
        const val SCROLL_DIR_TTB = "ttb"
        const val SCROLL_DIR_FIXED = "fixed"

        // Sponsor scroll animation, in RootEncoder's 0–100% canvas space (resolution-independent).
        const val SCROLL_STEP_PCT = 0.45f
        const val SCROLL_FRAME_MS = 33L
        const val SCROLL_GAP_PCT = 8f
        // Reset the accumulator on a multiple of common periods to avoid float drift over long streams.
        const val SCROLL_WRAP_PCT = 100_000f
    }
}

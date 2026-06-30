package uk.co.cricrelay.stream

import android.app.Activity
import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.PixelFormat
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.FrameLayout
import org.json.JSONObject
import org.json.JSONTokener
import java.util.concurrent.Executors

/**
 * Off-screen WebView used to rasterize the scoreboard for the GL overlay and the
 * studio preview.
 *
 * Deterministic capture design (do not reintroduce per-frame JS / cropping):
 * - The embed page is pinned ONCE per page load via a persistent <style> in <head>:
 *   the scoreboard renders top-centered in a fixed-width viewport. The <style> tag
 *   survives the page's 2s DOM rebuilds (only #content.innerHTML is rewritten).
 * - The WebView renders at a fixed initial scale so the CSS viewport is always
 *   [CSS_VIEWPORT_WIDTH] px wide regardless of device density — every size preset
 *   (overlay-size-1..5) fits and looks identical across phones.
 * - A low-frequency measure loop (every 2s, OFF the capture path) asks the page for
 *   the widget height once and latches the capture height. The per-frame capture is
 *   then only measure/layout/draw of the WebView — zero JS, zero cropping, zero
 *   Choreographer waits — so frames are cheap and dimensions are stable.
 */
class OverlayWebViewCapture(private val activity: Activity) {

    private val mainHandler = Handler(Looper.getMainLooper())
    private val bitmapExecutor = Executors.newSingleThreadExecutor { r ->
        Thread(r, "overlay-bitmap").apply { isDaemon = true }
    }
    private val windowManager =
        activity.getSystemService(Context.WINDOW_SERVICE) as WindowManager
    private var webView: WebView? = null
    private var attached = false
    private var pageLoaded = false
    @Volatile private var captureInFlight = false

    /** Latched capture height (physical px). Updated only by the measure loop. */
    @Volatile private var captureHeightPx = 0
    private var measureRunnable: Runnable? = null

    /** Fired on the main thread after the embed page finishes loading. */
    var onPageReady: (() -> Unit)? = null

    @Volatile private var fontScale: Float = 1.0f
    @Volatile private var bgColor: String = ""
    @Volatile private var textColor: String = ""

    companion object {
        /** Fixed bitmap width — both the GL sprite and the Compose preview consume this. */
        const val CAPTURE_WIDTH_PX = 960

        /**
         * CSS viewport width; initial scale = CAPTURE_WIDTH_PX / CSS_VIEWPORT_WIDTH.
         *
         * MUST equal the overlay's native design width (cricket_overlay.html lays out at
         * 1280px and its own applyOverlayScale() is a no-op at >= 1280). Rendering at the
         * design width means the captured strip is byte-for-byte what Chrome shows; the GL
         * sprite then scales the bitmap by aspect ratio + width/height sliders. Using a
         * narrower viewport (the old 480) crammed 1280px of content into 480px and clipped
         * the team names — the exact bug this fixes.
         */
        private const val CSS_VIEWPORT_WIDTH = 1280
        private const val INITIAL_SCALE_PERCENT = CAPTURE_WIDTH_PX * 100 / CSS_VIEWPORT_WIDTH

        // The new overlay strip is a thin lower-third (~78 CSS px tall at 1280); keep the floor
        // low so the measure loop's true height isn't clamped up into dead transparent space
        // (which would float the strip away from the bottom edge once composited).
        private const val MIN_CAPTURE_HEIGHT_PX = 40
        private const val MAX_CAPTURE_HEIGHT_PX = 640
        private const val MEASURE_INTERVAL_MS = 2000L

        /** CSS padding below the widget so drop shadows are not clipped. */
        private const val BOTTOM_PAD_CSS = 10
    }

    fun setStyle(fontScale: Float, bgColor: String, textColor: String) {
        this.fontScale = fontScale.coerceIn(0.6f, 2.0f)
        this.bgColor = bgColor.trim()
        this.textColor = textColor.trim()
        runOnMain { webView?.evaluateJavascript(measureScript(), null) }
    }

    /**
     * Pin the scoreboard top-anchored at its native design width + apply user style prefs.
     * Injected once per load (and re-applied on style changes via the measure loop).
     *
     * Targets cricket_overlay.html's structure: #overlay is a bottom-anchored broadcast strip
     * sized in rem off the root font-size, themed through CSS variables (--bg/--bg2/--text).
     * We re-pin it to the TOP at full design width so the off-screen capture renders the entire
     * strip with no scaling/cropping and measures its height from y=0.
     */
    private fun buildInjectedCss(): String {
        val scale = fontScale.coerceIn(0.6f, 2.0f)
        val rootPx = String.format(java.util.Locale.US, "%.2f", 16f * scale)
        val bg = bgColor.replace("'", "").replace("\"", "")
        val fg = textColor.replace("'", "").replace("\"", "")
        val css = StringBuilder()
        css.append("html,body{margin:0 !important;padding:0 !important;")
        css.append("background:transparent !important;overflow:hidden !important;}")
        // Font-readability slider: the overlay sizes everything in rem off the root font-size,
        // so scaling html font-size scales the whole scoreboard proportionally (board width/
        // height sliders scale the composited sprite separately).
        css.append("html{font-size:${rootPx}px !important;}")
        // Re-pin the broadcast strip (normally bottom:0) to the top at native design width.
        // transform-origin:top-left is defensive: the viewport is forced to 1280 so the page's
        // applyOverlayScale() is a no-op, but if a scale ever did apply it would anchor at the
        // top (matching this top-pin) rather than leaving a gap above the measured strip.
        css.append("#overlay{position:fixed !important;top:0 !important;bottom:auto !important;")
        css.append("left:0 !important;right:0 !important;transform:none !important;")
        css.append("width:auto !important;margin:0 !important;transform-origin:top left !important;}")
        // Map the operator's box / text colour prefs onto the overlay's theme variables.
        if (bg.isNotEmpty() || fg.isNotEmpty()) {
            css.append(":root{")
            if (bg.isNotEmpty()) css.append("--bg:$bg !important;--bg2:$bg !important;")
            if (fg.isNotEmpty()) css.append("--text:$fg !important;")
            css.append("}")
        }
        return css.toString()
    }

    /**
     * Ensure the persistent style tag exists (head survives content rebuilds, but be
     * defensive) and report whether the scoreboard rendered plus its height in CSS px.
     * Runs on page load, on style changes, and every [MEASURE_INTERVAL_MS] — never per frame.
     */
    private fun measureScript(): String {
        val cssLiteral = buildInjectedCss().replace("\\", "\\\\").replace("'", "\\'")
        return """
(function(){
  try{
    // Force the CSS viewport to the overlay's 1280px design width on every device so the
    // strip lays out identically to Chrome (cricket_overlay.html ships width=device-width).
    var vp=document.querySelector('meta[name=viewport]');
    if(!vp){vp=document.createElement('meta');vp.setAttribute('name','viewport');
      (document.head||document.documentElement).appendChild(vp);}
    if(vp.getAttribute('content')!=='width=1280'){vp.setAttribute('content','width=1280');}
    var s=document.getElementById('cr-style');
    if(!s){s=document.createElement('style');s.id='cr-style';
      (document.head||document.documentElement).appendChild(s);}
    var css='$cssLiteral';
    if(s.textContent!==css){s.textContent=css;}
    var o=document.getElementById('overlay');
    if(!o){return JSON.stringify({ready:false,why:'no-overlay'});}
    var label=(o.textContent||'').replace(/\s+/g,' ').trim();
    if(label===''||/^Loading\.?\.?\.?$/i.test(label)){
      return JSON.stringify({ready:false,why:'loading'});}
    var r=o.getBoundingClientRect();
    if(r.height<8){return JSON.stringify({ready:false,why:'zero-rect'});}
    return JSON.stringify({ready:true,h:Math.ceil(r.bottom)+$BOTTOM_PAD_CSS});
  }catch(e){return JSON.stringify({ready:false,why:String(e)});}
})();
""".trimIndent()
    }

    private fun runOnMain(block: () -> Unit) {
        if (Looper.myLooper() == Looper.getMainLooper()) {
            block()
        } else {
            mainHandler.post(block)
        }
    }

    private fun obtainWebView(): WebView? {
        if (webView != null) return webView
        return try {
            WebView(activity).apply {
                settings.javaScriptEnabled = true
                settings.domStorageEnabled = true
                settings.textZoom = 100
                // Honour an explicit viewport width (we force width=1280 — the overlay's design
                // width — in the injected JS) and zoom-to-fit it into the narrower capture view.
                // This pins the page's CSS viewport to 1280 on every device/density so the strip
                // renders exactly as Chrome shows it and the height→pixel math stays deterministic.
                settings.useWideViewPort = true
                settings.loadWithOverviewMode = true
                setInitialScale(INITIAL_SCALE_PERCENT)
                setBackgroundColor(Color.TRANSPARENT)
                setLayerType(View.LAYER_TYPE_SOFTWARE, null)
                webViewClient = object : WebViewClient() {
                    override fun onPageFinished(view: WebView?, url: String?) {
                        pageLoaded = true
                        captureHeightPx = 0
                        CricrelayLog.d("overlay WebView page finished: $url")
                        view?.let { runMeasure(it) }
                        startMeasureLoop()
                        onPageReady?.invoke()
                    }
                }
            }.also { webView = it }
        } catch (e: Exception) {
            CricrelayLog.w("overlay WebView create failed: ${e.message}")
            null
        }
    }

    private fun startMeasureLoop() {
        stopMeasureLoop()
        val runnable = object : Runnable {
            override fun run() {
                webView?.let { runMeasure(it) }
                mainHandler.postDelayed(this, MEASURE_INTERVAL_MS)
            }
        }
        measureRunnable = runnable
        mainHandler.postDelayed(runnable, MEASURE_INTERVAL_MS)
    }

    private fun stopMeasureLoop() {
        measureRunnable?.let { mainHandler.removeCallbacks(it) }
        measureRunnable = null
    }

    private fun runMeasure(view: WebView) {
        view.evaluateJavascript(measureScript()) { result ->
            val obj = parseJson(result) ?: return@evaluateJavascript
            if (!obj.optBoolean("ready", false)) {
                if (captureHeightPx == 0) {
                    CricrelayLog.d("overlay measure: not ready (${obj.optString("why")})")
                }
                return@evaluateJavascript
            }
            val cssHeight = obj.optInt("h", 0)
            if (cssHeight <= 0) return@evaluateJavascript
            val physHeight = (cssHeight * CAPTURE_WIDTH_PX / CSS_VIEWPORT_WIDTH)
                .coerceIn(MIN_CAPTURE_HEIGHT_PX, MAX_CAPTURE_HEIGHT_PX)
            val previous = captureHeightPx
            if (previous == 0 || kotlin.math.abs(physHeight - previous) > 8) {
                captureHeightPx = physHeight
                CricrelayLog.d(
                    "overlay measure: capture height $previous -> $physHeight " +
                        "(css=$cssHeight viewport=${CAPTURE_WIDTH_PX}x$physHeight)",
                )
            }
        }
    }

    fun ensureAttached() {
        runOnMain {
            if (attached) return@runOnMain
            val view = obtainWebView() ?: return@runOnMain
            val token = activity.window?.decorView?.windowToken
            if (token != null) {
                try {
                    val lp = WindowManager.LayoutParams(
                        CAPTURE_WIDTH_PX,
                        MAX_CAPTURE_HEIGHT_PX,
                        WindowManager.LayoutParams.TYPE_APPLICATION_PANEL,
                        WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                            WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE,
                        PixelFormat.TRANSLUCENT,
                    ).apply {
                        this.token = token
                        gravity = android.view.Gravity.TOP or android.view.Gravity.START
                        x = -20000
                        y = -20000
                        alpha = 0f
                    }
                    windowManager.addView(view, lp)
                    attached = true
                    CricrelayLog.d("overlay WebView attached (panel)")
                    return@runOnMain
                } catch (e: Exception) {
                    CricrelayLog.w("overlay panel attach failed: ${e.message}")
                }
            }
            try {
                val root = activity.findViewById<ViewGroup>(android.R.id.content) ?: return@runOnMain
                detachFromParent(view)
                val lp = FrameLayout.LayoutParams(CAPTURE_WIDTH_PX, MAX_CAPTURE_HEIGHT_PX).apply {
                    leftMargin = -20000
                    topMargin = -20000
                }
                root.addView(view, lp)
                attached = true
                CricrelayLog.d("overlay WebView attached (content fallback)")
            } catch (e: Exception) {
                CricrelayLog.w("overlay content attach failed: ${e.message}")
            }
        }
    }

    fun loadUrl(url: String) {
        if (url.isBlank()) return
        runOnMain {
            ensureAttached()
            val view = webView ?: return@runOnMain
            try {
                if (view.url != url) {
                    pageLoaded = false
                    captureHeightPx = 0
                    CricrelayLog.d("overlay WebView load: $url")
                    view.loadUrl(url)
                }
            } catch (e: Exception) {
                CricrelayLog.w("overlay WebView load failed: ${e.message}")
            }
        }
    }

    /**
     * Rasterize the scoreboard. Per-frame work on the main thread is only
     * measure/layout/draw of the WebView; the callback runs on a background thread.
     * Returns null (without logging spam) until the page is ready.
     */
    fun captureAsync(callback: (Bitmap?) -> Unit) {
        runOnMain {
            if (captureInFlight) {
                callback(null)
                return@runOnMain
            }
            val view = webView
            val height = captureHeightPx
            if (view == null || !attached || !pageLoaded || height <= 0) {
                callback(null)
                return@runOnMain
            }
            captureInFlight = true
            val startedAt = SystemClock.elapsedRealtime()
            try {
                if (view.measuredWidth != CAPTURE_WIDTH_PX || view.measuredHeight != height) {
                    view.measure(
                        View.MeasureSpec.makeMeasureSpec(CAPTURE_WIDTH_PX, View.MeasureSpec.EXACTLY),
                        View.MeasureSpec.makeMeasureSpec(height, View.MeasureSpec.EXACTLY),
                    )
                }
                view.layout(0, 0, CAPTURE_WIDTH_PX, height)
                val bitmap = Bitmap.createBitmap(CAPTURE_WIDTH_PX, height, Bitmap.Config.ARGB_8888)
                view.draw(Canvas(bitmap))
                val elapsed = SystemClock.elapsedRealtime() - startedAt
                CricrelayLog.d(
                    "overlay capture ok: ${bitmap.width}x${bitmap.height} elapsed=${elapsed}ms",
                )
                bitmapExecutor.execute {
                    captureInFlight = false
                    callback(bitmap)
                }
            } catch (e: Exception) {
                captureInFlight = false
                CricrelayLog.w("overlay capture failed: ${e.message}")
                callback(null)
            }
        }
    }

    private fun parseJson(result: String?): JSONObject? {
        if (result.isNullOrBlank() || result == "null") return null
        val raw = try {
            JSONTokener(result.trim()).nextValue()?.toString().orEmpty()
        } catch (_: Exception) {
            result.trim().removeSurrounding("\"")
        }
        if (raw.isBlank()) return null
        return try {
            JSONObject(raw)
        } catch (e: Exception) {
            CricrelayLog.w("overlay measure parse failed: raw=$raw err=${e.message}")
            null
        }
    }

    private fun detachFromParent(view: View) {
        (view.parent as? ViewGroup)?.removeView(view)
    }

    fun destroy() {
        runOnMain {
            stopMeasureLoop()
            val view = webView ?: return@runOnMain
            if (attached) {
                try {
                    windowManager.removeViewImmediate(view)
                } catch (_: Exception) {
                    detachFromParent(view)
                }
                attached = false
            }
            try {
                view.destroy()
            } catch (_: Exception) {
            }
            webView = null
            pageLoaded = false
            captureHeightPx = 0
            captureInFlight = false
        }
    }
}

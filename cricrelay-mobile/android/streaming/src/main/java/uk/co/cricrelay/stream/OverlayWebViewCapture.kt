package uk.co.cricrelay.stream

import android.app.Activity
import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.PixelFormat
import android.os.Handler
import android.os.Looper
import android.view.View
import android.view.WindowManager
import android.webkit.WebView
import android.webkit.WebViewClient
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/** Off-screen WebView used to rasterize the scoreboard for GL overlay (not screen capture). */
class OverlayWebViewCapture(private val activity: Activity) {

    private val mainHandler = Handler(Looper.getMainLooper())
    private val windowManager =
        activity.getSystemService(Context.WINDOW_SERVICE) as WindowManager
    private var webView: WebView? = null
    private var attached = false

    // Configurable appearance (Board Edit). Injected as CSS into the scoreboard embed.
    @Volatile private var fontScale: Float = 1.0f
    @Volatile private var bgColor: String = ""
    @Volatile private var textColor: String = ""

    /** Update scoreboard appearance; re-applied on every capture and page load. */
    fun setStyle(fontScale: Float, bgColor: String, textColor: String) {
        this.fontScale = fontScale.coerceIn(0.6f, 2.0f)
        this.bgColor = bgColor.trim()
        this.textColor = textColor.trim()
        runOnMain { webView?.evaluateJavascript(prepareCaptureScript(), null) }
    }

    private fun buildStyleCss(): String {
        // The scoreboard embed sizes typography via overlay-size-N CSS classes and
        // `calc(Npx * var(--overlay-scale))`. Do not hardcode --widget-name/--widget-score
        // here — that overrides the web "Widget size" preset (e.g. extra-small = size 1).
        val scale = String.format(java.util.Locale.US, "%.3f", fontScale.coerceIn(0.6f, 2.0f))
        val bg = bgColor.replace("'", "").replace("\"", "")
        val fg = textColor.replace("'", "").replace("\"", "")
        val css = StringBuilder()
        css.append(":root{--overlay-scale:$scale !important;")
        if (bg.isNotEmpty()) css.append("--overlay-box-color:$bg !important;")
        css.append("}")
        if (bg.isNotEmpty()) {
            css.append("#overlay,#overlay .relay-widget,#overlay .score-strip,#overlay #content")
            css.append("{background:$bg !important;background-image:none !important;}")
            css.append("#overlay::before{background:$bg !important;background-image:none !important;}")
        }
        if (fg.isNotEmpty()) {
            css.append("#overlay,#overlay *{color:$fg !important;}")
        }
        return css.toString()
    }

    /** Apply fit + styles, then return measured content height (px). */
    private fun prepareCaptureScript(): String {
        val cssLiteral = buildStyleCss().replace("\\", "\\\\").replace("'", "\\'")
        return """
(function(){
  try{
    var s=document.getElementById('cr-style');
    if(!s){s=document.createElement('style');s.id='cr-style';
    (document.head||document.documentElement).appendChild(s);}
    s.textContent='$cssLiteral';
    var o=document.getElementById('overlay');
    if(o){
      o.style.setProperty('position','static','important');
      o.style.setProperty('top','0','important');
      o.style.setProperty('bottom','auto','important');
      o.style.setProperty('left','auto','important');
      o.style.setProperty('right','auto','important');
      o.style.setProperty('transform','none','important');
      o.style.setProperty('margin','0 auto','important');
      o.style.setProperty('width','100%','important');
    }
    var de=document.documentElement, b=document.body;
    if(de){de.style.background='transparent';de.style.overflow='visible';}
    if(b){b.style.margin='0';b.style.background='transparent';b.style.overflow='visible';}
    var el=o||b;
    var rect=el.getBoundingClientRect();
    var h=Math.ceil(Math.max(el.scrollHeight,el.offsetHeight,rect.height,64));
    return String(h);
  }catch(e){return '140';}
})();
""".trimIndent()
    }

    companion object {
        private const val MEASURE_VIEWPORT_HEIGHT = 900
        private const val MIN_CAPTURE_HEIGHT = 140
        private const val MAX_CAPTURE_HEIGHT = 640
        private const val JS_PREPARE_TIMEOUT_MS = 750L
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
                setBackgroundColor(Color.TRANSPARENT)
                // Software layer is REQUIRED: Canvas.draw() on a hardware-layer
                // WebView yields a blank bitmap, which made the scoreboard missing
                // from both the live stream overlay and the preview.
                setLayerType(View.LAYER_TYPE_SOFTWARE, null)
                webViewClient = object : WebViewClient() {
                    override fun onPageFinished(view: WebView?, url: String?) {
                        view?.evaluateJavascript(prepareCaptureScript(), null)
                    }
                }
            }.also { webView = it }
        } catch (_: Exception) {
            null
        }
    }

    fun ensureAttached() {
        runOnMain {
            if (attached) return@runOnMain
            val view = obtainWebView() ?: return@runOnMain
            val token = activity.window?.decorView?.windowToken ?: return@runOnMain
            try {
                val lp = WindowManager.LayoutParams(
                    1280,
                    MEASURE_VIEWPORT_HEIGHT,
                    WindowManager.LayoutParams.TYPE_APPLICATION_PANEL,
                    WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                        WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE or
                        WindowManager.LayoutParams.FLAG_HARDWARE_ACCELERATED,
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
            } catch (_: Exception) {
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
                    view.loadUrl(url)
                }
            } catch (_: Exception) {
            }
        }
    }

    fun capture(width: Int, height: Int): Bitmap? {
        if (Looper.myLooper() != Looper.getMainLooper()) {
            var result: Bitmap? = null
            val latch = CountDownLatch(1)
            mainHandler.post {
                result = captureOnMain(width, height)
                latch.countDown()
            }
            try {
                latch.await(2, TimeUnit.SECONDS)
            } catch (_: InterruptedException) {
            }
            return result
        }
        return captureOnMain(width, height)
    }

    private fun captureOnMain(width: Int, height: Int): Bitmap? {
        if (!attached || webView == null) return null
        return try {
            val view = webView!!
            val w = maxOf(width, 320)
            val hintH = height.coerceIn(MIN_CAPTURE_HEIGHT, MAX_CAPTURE_HEIGHT)

            // Measure pass: tall viewport so scrollHeight reflects full scoreboard DOM.
            view.measure(
                View.MeasureSpec.makeMeasureSpec(w, View.MeasureSpec.EXACTLY),
                View.MeasureSpec.makeMeasureSpec(MEASURE_VIEWPORT_HEIGHT, View.MeasureSpec.EXACTLY),
            )
            view.layout(0, 0, w, MEASURE_VIEWPORT_HEIGHT)

            val latch = CountDownLatch(1)
            var measuredH = hintH
            view.evaluateJavascript(prepareCaptureScript()) { result ->
                measuredH = parseMeasuredHeight(result, hintH)
                latch.countDown()
            }
            latch.await(JS_PREPARE_TIMEOUT_MS, TimeUnit.MILLISECONDS)

            val h = maxOf(hintH, measuredH).coerceIn(MIN_CAPTURE_HEIGHT, MAX_CAPTURE_HEIGHT)
            view.measure(
                View.MeasureSpec.makeMeasureSpec(w, View.MeasureSpec.EXACTLY),
                View.MeasureSpec.makeMeasureSpec(h, View.MeasureSpec.EXACTLY),
            )
            view.layout(0, 0, w, h)
            val bitmap = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
            val canvas = Canvas(bitmap)
            view.draw(canvas)
            bitmap
        } catch (_: Exception) {
            null
        }
    }

    private fun parseMeasuredHeight(result: String?, fallback: Int): Int {
        if (result.isNullOrBlank() || result == "null") return fallback
        val cleaned = result.trim().removeSurrounding("\"")
        return cleaned.toIntOrNull()?.coerceIn(MIN_CAPTURE_HEIGHT, MAX_CAPTURE_HEIGHT) ?: fallback
    }

    fun destroy() {
        runOnMain {
            val view = webView ?: return@runOnMain
            if (attached) {
                try {
                    windowManager.removeViewImmediate(view)
                } catch (_: Exception) {
                }
                attached = false
            }
            try {
                view.destroy()
            } catch (_: Exception) {
            }
            webView = null
        }
    }
}

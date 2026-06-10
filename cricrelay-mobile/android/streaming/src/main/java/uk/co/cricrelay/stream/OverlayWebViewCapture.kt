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
        runOnMain { webView?.evaluateJavascript(styleScript(), null) }
    }

    private fun styleScript(): String {
        // The scoreboard embed sizes ALL typography via `calc(Npx * var(--overlay-scale))`,
        // exposes `--overlay-box-color` on :root, and paints the strip with
        // `#overlay { background: linear-gradient(...) }`. Critically, the embed rebuilds
        // #overlay's inner DOM every ~2s and re-applies `--overlay-scale` inline (non-
        // important). Inline overrides therefore race the rebuild and get clobbered. We
        // instead inject ONE persistent <style id="cr-style"> into <head>: a stylesheet
        // `!important` rule beats the embed's inline-normal value, survives DOM rebuilds,
        // and re-applies to freshly-created children automatically.
        val scale = String.format(java.util.Locale.US, "%.3f", fontScale.coerceIn(0.6f, 2.0f))
        val bg = bgColor.replace("'", "").replace("\"", "")
        val fg = textColor.replace("'", "").replace("\"", "")
        val css = StringBuilder()
        css.append(":root{--overlay-scale:$scale !important;")
        // The live score widgets read `--overlay-scale`, but the team-name and score text
        // are sized by `--widget-name`/`--widget-score` (defined only via the embed's
        // responsive media queries — never set inline). Override them too so the font
        // control visibly scales ALL text uniformly, including the pre-match placeholder.
        css.append("--widget-name:calc(10px * $scale) !important;")
        css.append("--widget-score:calc(30px * $scale) !important;")
        if (bg.isNotEmpty()) css.append("--overlay-box-color:$bg !important;")
        css.append("}")
        if (bg.isNotEmpty()) {
            // The visible panel is the inner `.relay-widget` (a hardcoded gradient) sitting
            // inside #overlay, with `.score-strip`/#content nested further. Repaint every
            // structural container so the chosen colour fully takes over the strip; killing
            // background-image removes the baked-in gradients. The ::before is the thin top
            // accent line.
            css.append("#overlay,#overlay .relay-widget,#overlay .score-strip,#overlay #content")
            css.append("{background:$bg !important;background-image:none !important;}")
            css.append("#overlay::before{background:$bg !important;background-image:none !important;}")
        }
        if (fg.isNotEmpty()) {
            css.append("#overlay,#overlay *{color:$fg !important;}")
        }
        val cssLiteral = css.toString().replace("\\", "\\\\").replace("'", "\\'")
        val sb = StringBuilder()
        sb.append("(function(){try{")
        sb.append("var s=document.getElementById('cr-style');")
        sb.append("if(!s){s=document.createElement('style');s.id='cr-style';")
        sb.append("(document.head||document.documentElement).appendChild(s);}")
        sb.append("s.textContent='$cssLiteral';")
        sb.append("}catch(e){}})();")
        return sb.toString()
    }

    companion object {
        // Capture width: wide enough to use the horizontal score-strip layout
        // (the overlay stacks tall and gets clipped below ~720px).
        private const val CAPTURE_WIDTH = 1080

        // Neutralize the overlay's fixed bottom pinning so the full scoreboard
        // flows from the top-left and can be measured/captured without clipping.
        private const val FIT_SCRIPT = """
(function(){
  try{
    var o=document.getElementById('overlay');
    if(o){
      o.style.setProperty('position','static','important');
      o.style.setProperty('top','0','important');
      o.style.setProperty('bottom','auto','important');
      o.style.setProperty('left','auto','important');
      o.style.setProperty('right','auto','important');
      o.style.setProperty('transform','none','important');
      o.style.setProperty('margin','0 auto','important');
    }
    var de=document.documentElement, b=document.body;
    if(de){de.style.background='transparent';}
    if(b){b.style.margin='0';b.style.background='transparent';}
  }catch(e){}
})();
"""
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
                        view?.evaluateJavascript(FIT_SCRIPT, null)
                        view?.evaluateJavascript(styleScript(), null)
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
            // Host the offscreen WebView in its OWN WindowManager sub-window rather
            // than in the activity's content/decor view tree. Earlier versions added
            // it as a child of the DecorView (then the content FrameLayout); during
            // navigation transitions the parent was drawn while this child's slot was
            // momentarily null, throwing the FATAL "<ViewGroup> contains null child at
            // index 1 ... dispatchGetDisplayList" — which kicked the user back to a
            // black "Starting camera…" screen. A panel sub-window is fully decoupled
            // from the activity's view hierarchy, so transitions can never null it out.
            try {
                val lp = WindowManager.LayoutParams(
                    1280,
                    360,
                    WindowManager.LayoutParams.TYPE_APPLICATION_PANEL,
                    WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                        WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE or
                        WindowManager.LayoutParams.FLAG_HARDWARE_ACCELERATED,
                    PixelFormat.TRANSLUCENT,
                ).apply {
                    this.token = token
                    // Position fully off-screen and invisible; we only rasterize via draw().
                    gravity = android.view.Gravity.TOP or android.view.Gravity.START
                    x = -20000
                    y = -20000
                    alpha = 0f
                }
                windowManager.addView(view, lp)
                attached = true
            } catch (_: Exception) {
                // If the panel window can't be added, leave attached=false so capture()
                // returns null — preview simply shows no scoreboard, never crashes.
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
            val latch = java.util.concurrent.CountDownLatch(1)
            mainHandler.post {
                result = captureOnMain(width, height)
                latch.countDown()
            }
            try {
                latch.await(2, java.util.concurrent.TimeUnit.SECONDS)
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
            // Re-apply fit + appearance styles (the overlay re-renders its score every ~2s).
            view.evaluateJavascript(FIT_SCRIPT, null)
            view.evaluateJavascript(styleScript(), null)
            val w = maxOf(width, 320)
            val h = height.coerceIn(64, 320)
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

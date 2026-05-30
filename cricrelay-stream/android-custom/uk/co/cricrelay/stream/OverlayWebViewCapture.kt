package uk.co.cricrelay.stream

import android.app.Activity
import android.content.ContextWrapper
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.view.View
import android.view.ViewGroup
import android.webkit.WebView
import android.widget.FrameLayout

/** Off-screen WebView used to rasterize the scoreboard for GL overlay (not screen capture). */
class OverlayWebViewCapture(private val activity: Activity) {

    private val mainHandler = Handler(Looper.getMainLooper())
    private var webView: WebView? = null
    private var attached = false

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
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
                    setLayerType(View.LAYER_TYPE_HARDWARE, null)
                } else {
                    setLayerType(View.LAYER_TYPE_SOFTWARE, null)
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
            val root = activity.window?.decorView as? ViewGroup ?: return@runOnMain
            try {
                val lp = FrameLayout.LayoutParams(1280, 360)
                lp.leftMargin = -10000
                lp.topMargin = -10000
                root.addView(view, lp)
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
            val w = width.coerceIn(160, 1920)
            val h = height.coerceIn(48, 600)
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
                    (view.parent as? ViewGroup)?.removeView(view)
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

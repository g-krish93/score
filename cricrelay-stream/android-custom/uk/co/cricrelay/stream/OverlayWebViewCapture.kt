package uk.co.cricrelay.stream

import android.app.Activity
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.os.Handler
import android.os.Looper
import android.view.View
import android.view.ViewGroup
import android.webkit.WebView
import android.widget.FrameLayout

/** Off-screen WebView used to rasterize the scoreboard for GL overlay (not screen capture). */
class OverlayWebViewCapture(private val activity: Activity) {

    private val mainHandler = Handler(Looper.getMainLooper())
    private val webView = WebView(activity).apply {
        settings.javaScriptEnabled = true
        settings.domStorageEnabled = true
        setBackgroundColor(Color.TRANSPARENT)
    }

    private var attached = false

    private fun runOnMain(block: () -> Unit) {
        if (Looper.myLooper() == Looper.getMainLooper()) {
            block()
        } else {
            mainHandler.post(block)
        }
    }

    fun ensureAttached() {
        runOnMain {
            if (attached) return@runOnMain
            val root = activity.window?.decorView as? ViewGroup ?: return@runOnMain
            val lp = FrameLayout.LayoutParams(1280, 360)
            lp.leftMargin = -10000
            lp.topMargin = -10000
            root.addView(webView, lp)
            attached = true
        }
    }

    fun loadUrl(url: String) {
        runOnMain {
            ensureAttached()
            if (webView.url != url) {
                webView.loadUrl(url)
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
        if (!attached) return null
        return try {
            val w = width.coerceIn(160, 1920)
            val h = height.coerceIn(48, 600)
            webView.measure(
                View.MeasureSpec.makeMeasureSpec(w, View.MeasureSpec.EXACTLY),
                View.MeasureSpec.makeMeasureSpec(h, View.MeasureSpec.EXACTLY),
            )
            webView.layout(0, 0, w, h)
            val bitmap = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
            val canvas = Canvas(bitmap)
            webView.draw(canvas)
            bitmap
        } catch (_: Exception) {
            null
        }
    }

    fun destroy() {
        runOnMain {
            if (attached) {
                (webView.parent as? ViewGroup)?.removeView(webView)
                attached = false
            }
            webView.destroy()
        }
    }
}

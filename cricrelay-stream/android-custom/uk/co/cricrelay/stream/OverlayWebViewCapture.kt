package uk.co.cricrelay.stream

import android.app.Activity
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.view.View
import android.view.ViewGroup
import android.webkit.WebView
import android.widget.FrameLayout

/** Off-screen WebView used to rasterize the scoreboard for GL overlay (not screen capture). */
class OverlayWebViewCapture(private val activity: Activity) {

    private val webView = WebView(activity).apply {
        settings.javaScriptEnabled = true
        settings.domStorageEnabled = true
        setBackgroundColor(Color.TRANSPARENT)
    }

    private var attached = false

    fun ensureAttached() {
        if (attached) return
        val root = activity.window.decorView as ViewGroup
        val lp = FrameLayout.LayoutParams(1280, 360)
        lp.leftMargin = -10000
        lp.topMargin = -10000
        root.addView(webView, lp)
        attached = true
    }

    fun loadUrl(url: String) {
        ensureAttached()
        if (webView.url != url) {
            webView.loadUrl(url)
        }
    }

    fun capture(width: Int, height: Int): Bitmap? {
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
        if (attached) {
            (webView.parent as? ViewGroup)?.removeView(webView)
            attached = false
        }
        webView.destroy()
    }
}

package uk.co.cricrelay.stream

import android.content.Context
import android.util.Log
import org.json.JSONObject
import java.io.File

/** Session 0ad848 — NDJSON to logcat + app external files for broadcast-screen crash debug. */
object DebugTrace {
    private const val TAG = "CRICRELAY_DEBUG"
    private var appContext: Context? = null

    fun init(ctx: Context) {
        appContext = ctx.applicationContext
    }

    fun log(
        location: String,
        message: String,
        hypothesisId: String,
        data: Map<String, Any?> = emptyMap(),
    ) {
        val payload = JSONObject().apply {
            put("sessionId", "0ad848")
            put("timestamp", System.currentTimeMillis())
            put("location", location)
            put("message", message)
            put("hypothesisId", hypothesisId)
            put("data", JSONObject(data))
        }
        val line = payload.toString()
        Log.i(TAG, line)
        try {
            val ctx = appContext ?: return
            File(ctx.getExternalFilesDir(null), "debug-0ad848.log").appendText("$line\n")
        } catch (_: Exception) {
        }
    }
}

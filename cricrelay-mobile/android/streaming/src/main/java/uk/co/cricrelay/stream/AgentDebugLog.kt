package uk.co.cricrelay.stream

import android.util.Log
import org.json.JSONObject

/** Session-scoped debug ingest (removed after overlay preview investigation). */
// #region agent log
object AgentDebugLog {
    private const val TAG = "AgentDebugLog"
    private const val ENDPOINT =
        "http://127.0.0.1:7503/ingest/36692bbc-7afc-43eb-bc7d-1cc39e5034e1"
    private const val SESSION = "f8bbc4"

    fun log(
        location: String,
        message: String,
        data: Map<String, Any?> = emptyMap(),
        hypothesisId: String = "",
        runId: String = "pre-fix",
    ) {
        val dataJson = JSONObject(data.mapValues { it.value?.toString() ?: "null" }).toString()
        Log.i(TAG, "[$hypothesisId] $location | $message | $dataJson")
        Thread {
            runCatching {
                val payload = JSONObject().apply {
                    put("sessionId", SESSION)
                    put("location", location)
                    put("message", message)
                    put("timestamp", System.currentTimeMillis())
                    put("runId", runId)
                    if (hypothesisId.isNotEmpty()) put("hypothesisId", hypothesisId)
                    put("data", JSONObject(data.mapValues { it.value?.toString() ?: "null" }))
                }
                val conn = java.net.URL(ENDPOINT).openConnection() as java.net.HttpURLConnection
                conn.requestMethod = "POST"
                conn.connectTimeout = 1500
                conn.readTimeout = 1500
                conn.setRequestProperty("Content-Type", "application/json")
                conn.setRequestProperty("X-Debug-Session-Id", SESSION)
                conn.doOutput = true
                conn.outputStream.use { it.write(payload.toString().toByteArray()) }
                conn.inputStream.close()
            }
        }.start()
    }
}
// #endregion

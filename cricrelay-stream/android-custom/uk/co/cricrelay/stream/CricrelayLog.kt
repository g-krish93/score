package uk.co.cricrelay.stream

import android.util.Log

/** Verbose diagnostics for broadcast preview / z-order (filter logcat: Cricrelay). */
object CricrelayLog {
    private const val TAG = "Cricrelay"

    fun d(message: String) = Log.d(TAG, message)

    fun w(message: String) = Log.w(TAG, message)

    fun e(message: String, t: Throwable? = null) {
        if (t != null) Log.e(TAG, message, t) else Log.e(TAG, message)
    }
}

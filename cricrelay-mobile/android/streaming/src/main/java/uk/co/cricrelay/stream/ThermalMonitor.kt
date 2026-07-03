package uk.co.cricrelay.stream

import android.content.Context
import android.os.Build
import android.os.Handler
import android.os.PowerManager

/**
 * Thermal status source: a PowerManager listener on Q+, a 30 s poll below. Pure mechanics —
 * what a status change *means* (overlay refresh scaling, the "thermal" UI event) stays with
 * the engine's callback. Extracted from [StreamCameraEngine].
 */
internal class ThermalMonitor(
    private val mainHandler: Handler,
    private val onStatus: (Int) -> Unit,
) {
    private var listener: PowerManager.OnThermalStatusChangedListener? = null
    private var pollRunnable: Runnable? = null

    fun register(context: Context) {
        val pm = context.getSystemService(Context.POWER_SERVICE) as? PowerManager ?: return
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            if (listener != null) return
            val l = PowerManager.OnThermalStatusChangedListener { status -> onStatus(status) }
            listener = l
            try {
                pm.addThermalStatusListener(l)
                onStatus(pm.currentThermalStatus)
            } catch (_: Exception) {
            }
        } else {
            if (pollRunnable != null) return
            val runnable = object : Runnable {
                override fun run() {
                    val stressed = DeviceCapabilities.isThermalStressed(context)
                    onStatus(
                        if (stressed) PowerManager.THERMAL_STATUS_MODERATE else PowerManager.THERMAL_STATUS_NONE,
                    )
                    mainHandler.postDelayed(this, POLL_MS)
                }
            }
            pollRunnable = runnable
            mainHandler.post(runnable)
        }
    }

    fun unregister(context: Context) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            listener?.let { l ->
                try {
                    (context.getSystemService(Context.POWER_SERVICE) as? PowerManager)
                        ?.removeThermalStatusListener(l)
                } catch (_: Exception) {
                }
            }
            listener = null
        }
        pollRunnable?.let { mainHandler.removeCallbacks(it) }
        pollRunnable = null
    }

    private companion object {
        const val POLL_MS = 30_000L
    }
}

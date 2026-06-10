package uk.co.cricrelay.stream

import android.app.ActivityManager
import android.content.Context
import android.os.Build
import android.os.PowerManager

/** Device tier for encoder + overlay cost — keeps low-RAM phones smooth. */
object DeviceCapabilities {

    enum class Tier { LOW, MID, HIGH }

    fun tier(context: Context): Tier {
        val am = context.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager
        if (am.isLowRamDevice) return Tier.LOW
        val info = ActivityManager.MemoryInfo()
        am.getMemoryInfo(info)
        val totalGb = info.totalMem.toDouble() / (1024.0 * 1024.0 * 1024.0)
        return when {
            totalGb < 3.0 -> Tier.LOW
            totalGb < 6.0 -> Tier.MID
            else -> Tier.HIGH
        }
    }

    fun overlayRefreshMs(t: Tier): Long = when (t) {
        Tier.LOW -> 1200L
        Tier.MID -> 800L
        Tier.HIGH -> 500L
    }

    fun maxOverlayCaptureWidth(t: Tier): Int = when (t) {
        Tier.LOW -> 480
        Tier.MID -> 640
        Tier.HIGH -> 960
    }

    fun suggestedQualityId(t: Tier): String = when (t) {
        Tier.LOW -> "low"
        Tier.MID -> "medium"
        Tier.HIGH -> "high"
    }

    fun defaultEisEnabled(t: Tier): Boolean = t != Tier.LOW

    fun isPowerSaveMode(context: Context): Boolean {
        val pm = context.getSystemService(Context.POWER_SERVICE) as PowerManager
        return pm.isPowerSaveMode
    }

    fun isThermalStressed(context: Context): Boolean {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) return false
        return try {
            val pm = context.getSystemService(Context.POWER_SERVICE) as PowerManager
            pm.currentThermalStatus >= PowerManager.THERMAL_STATUS_MODERATE
        } catch (_: Exception) {
            false
        }
    }

    fun toMap(context: Context): Map<String, Any> {
        val t = tier(context)
        val am = context.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager
        return mapOf(
            "tier" to t.name.lowercase(),
            "lowRam" to am.isLowRamDevice,
            "overlayRefreshMs" to overlayRefreshMs(t),
            "maxOverlayCaptureWidth" to maxOverlayCaptureWidth(t),
            "suggestedQuality" to suggestedQualityId(t),
            "defaultEis" to defaultEisEnabled(t),
            "powerSave" to isPowerSaveMode(context),
        )
    }
}

package uk.co.cricrelay.mobile.feature.studio

import android.content.Context
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import uk.co.cricrelay.shared.model.OverlayLayoutPrefs
import uk.co.cricrelay.shared.model.StabilizationLevel
import javax.inject.Inject
import javax.inject.Singleton

/** Camera/device-scoped settings — they describe THIS phone, not the match. */
data class DeviceStreamSettings(
    val stabilizationLevel: Int = StabilizationLevel.STANDARD,
    val keepScreenOn: Boolean = true,
) {
    /** Overlay a copy of [prefs] with this device's camera settings. */
    fun appliedTo(prefs: OverlayLayoutPrefs): OverlayLayoutPrefs =
        prefs.withStabilizationLevel(stabilizationLevel).copy(keepScreenOn = keepScreenOn)
}

/**
 * Local-first persistence for the studio setup. The phone owns its configuration:
 *
 * - **Device settings** (stabilization level, keep-screen-on) are stored per device, never read
 *   back from the server — a camera setting must not depend on a network round-trip, and a club
 *   admin editing prefs in the web dashboard has no business changing this phone's EIS mode.
 * - **Overlay prefs** (board layout, sponsor setup, watermark) are cached per match slug. The
 *   cache is authoritative for the studio; the server copy is only a seed for a fresh install
 *   and the mirror that the web dashboard / remote companion read.
 */
@Singleton
class StudioLocalPrefsStore @Inject constructor(
    @ApplicationContext context: Context,
) {
    private val store = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    fun loadDeviceSettings(): DeviceStreamSettings = DeviceStreamSettings(
        stabilizationLevel = StabilizationLevel.sanitize(
            store.getInt(KEY_STABILIZATION_LEVEL, StabilizationLevel.STANDARD),
        ),
        keepScreenOn = store.getBoolean(KEY_KEEP_SCREEN_ON, true),
    )

    fun saveDeviceSettings(settings: DeviceStreamSettings) {
        store.edit()
            .putInt(KEY_STABILIZATION_LEVEL, StabilizationLevel.sanitize(settings.stabilizationLevel))
            .putBoolean(KEY_KEEP_SCREEN_ON, settings.keepScreenOn)
            .apply()
    }

    fun loadOverlayPrefs(slug: String): OverlayLayoutPrefs? {
        val raw = store.getString(overlayKey(slug), null) ?: return null
        return runCatching {
            OverlayLayoutPrefs.fromJson(Json.parseToJsonElement(raw).jsonObject)
        }.getOrNull()
    }

    fun saveOverlayPrefs(slug: String, prefs: OverlayLayoutPrefs) {
        store.edit().putString(overlayKey(slug), prefs.toJson().toString()).apply()
    }

    private fun overlayKey(slug: String) = "overlay_prefs_$slug"

    private companion object {
        const val PREFS = "cricrelay_studio_prefs"
        const val KEY_STABILIZATION_LEVEL = "device_stabilization_level"
        const val KEY_KEEP_SCREEN_ON = "device_keep_screen_on"
    }
}

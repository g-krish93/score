package uk.co.cricrelay.mobile.feature.studio

import android.content.Context
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton

data class RtmpCredentials(
    val rtmpUrl: String = "",
    val streamKey: String = "",
    val watchUrl: String = "",
)

@Singleton
class RtmpCredentialsStore @Inject constructor(
    @ApplicationContext context: Context,
) {
    private val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    fun load(slug: String): RtmpCredentials = RtmpCredentials(
        rtmpUrl = prefs.getString(key(slug, "rtmp_url"), "").orEmpty(),
        streamKey = prefs.getString(key(slug, "stream_key"), "").orEmpty(),
        watchUrl = prefs.getString(key(slug, "watch_url"), "").orEmpty(),
    )

    fun save(slug: String, credentials: RtmpCredentials) {
        prefs.edit()
            .putString(key(slug, "rtmp_url"), credentials.rtmpUrl)
            .putString(key(slug, "stream_key"), credentials.streamKey)
            .putString(key(slug, "watch_url"), credentials.watchUrl)
            .apply()
    }

    fun isConfigured(slug: String): Boolean {
        val creds = load(slug)
        return creds.rtmpUrl.isNotBlank() && creds.streamKey.isNotBlank()
    }

    private fun key(slug: String, field: String) = "rtmp_${slug}_$field"

    private companion object {
        const val PREFS = "cricrelay_rtmp_credentials"
    }
}

/** Vertical lift from the bottom edge; matches GL sprite [bottomMarginFraction] math. */
fun uk.co.cricrelay.shared.model.OverlayLayoutPrefs.bottomMarginPx(frameHeightPx: Int): Int {
    val fraction = (bottomMargin.toFloat() / 720f).coerceIn(0f, 0.2f)
    return (frameHeightPx * fraction).toInt()
}

fun uk.co.cricrelay.shared.model.OverlayLayoutPrefs.toEngineLayout(
    sponsorLogoUrls: List<String> = emptyList(),
): uk.co.cricrelay.stream.StreamCameraEngine.OverlayLayout {
    val urls = sponsorLogoUrls.filter { it.isNotBlank() }
    return uk.co.cricrelay.stream.StreamCameraEngine.OverlayLayout(
        heightFraction = heightFraction.toFloat(),
        widthFraction = widthFraction.toFloat(),
        anchorX = anchorX.toFloat(),
        anchorY = anchorY.toFloat(),
        bottomMarginFraction = (bottomMargin.toFloat() / 720f).coerceIn(0f, 0.2f),
        horizontalInsetFraction = (horizontalInset.toFloat() / 400f).coerceIn(0f, 0.2f),
        fontScale = effectiveFontScale(),
        bgColor = bgColor,
        textColor = textColor,
        opacity = opacity.toFloat().coerceIn(0.2f, 1.0f),
        watermarkEnabled = watermarkEnabled,
        watermarkText = watermarkText,
        sponsorEnabled = sponsorEnabled,
        sponsorLogoUrl = urls.firstOrNull().orEmpty(),
        sponsorLogoUrls = urls,
        sponsorLayoutMode = uk.co.cricrelay.shared.model.SponsorLayoutMode.sanitize(sponsorLayoutMode),
        sponsorCarouselIntervalSec = sponsorCarouselIntervalSec.toFloat().coerceIn(2f, 30f),
        sponsorDisplayMode = uk.co.cricrelay.shared.model.SponsorDisplayMode.sanitize(sponsorDisplayMode),
        sponsorPositionX = sponsorPositionX.toFloat().coerceIn(0f, 1f),
        sponsorPositionY = sponsorPositionY.toFloat().coerceIn(0f, 1f),
        sponsorSizeScale = sponsorSizeScale.toFloat().coerceIn(0.3f, 3f),
        sponsorOpacity = sponsorOpacity.toFloat().coerceIn(0.2f, 1f),
        sponsorScrollSpeed = sponsorScrollSpeed.toFloat().coerceIn(0.3f, 3f),
    )
}

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

fun uk.co.cricrelay.shared.model.OverlayLayoutPrefs.toEngineLayout(): uk.co.cricrelay.stream.StreamCameraEngine.OverlayLayout {
    return uk.co.cricrelay.stream.StreamCameraEngine.OverlayLayout(
        heightFraction = heightFraction.toFloat(),
        widthFraction = widthFraction.toFloat(),
        anchorX = anchorX.toFloat(),
        anchorY = anchorY.toFloat(),
        bottomMarginFraction = (bottomMargin.toFloat() / 720f).coerceIn(0f, 0.2f),
        horizontalInsetFraction = (horizontalInset.toFloat() / 400f).coerceIn(0f, 0.2f),
        fontScale = fontScale.toFloat().coerceIn(0.6f, 2.0f),
        bgColor = bgColor,
        textColor = textColor,
        opacity = opacity.toFloat().coerceIn(0.2f, 1.0f),
    )
}

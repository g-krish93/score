package uk.co.cricrelay.stream

/**
 * Decides when scoreboard GL refresh runs. Preview must not attach GL overlay filters
 * (Compose shows the camera; overlay burns in only while RTMP is live).
 */
object StreamOverlayPolicy {

    enum class RefreshMode {
        None,
        PreviewPush,
        StreamRefresh,
    }

    fun refreshMode(
        isStreaming: Boolean,
        hasPreviewListener: Boolean,
        overlayUrlBlank: Boolean,
    ): RefreshMode {
        if (overlayUrlBlank) return RefreshMode.None
        if (isStreaming) return RefreshMode.StreamRefresh
        if (hasPreviewListener) return RefreshMode.PreviewPush
        return RefreshMode.None
    }

    fun shouldAttachGlOverlayOnPreview(isStreaming: Boolean): Boolean = false

    fun shouldAttachGlOverlayOnStream(isStreaming: Boolean, overlayUrlBlank: Boolean): Boolean =
        isStreaming && !overlayUrlBlank
}

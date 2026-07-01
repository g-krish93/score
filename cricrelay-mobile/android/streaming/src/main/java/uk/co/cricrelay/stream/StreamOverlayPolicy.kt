package uk.co.cricrelay.stream

/**
 * Decides when scoreboard GL refresh runs. Pre-stream preview burns the overlay into the
 * camera GL surface (parity with iOS) so WYSIWYG matches the RTMP output.
 */
object StreamOverlayPolicy {

    enum class RefreshMode {
        None,
        PreviewGlRefresh,
        StreamRefresh,
    }

    fun refreshMode(
        isStreaming: Boolean,
        hasPreviewListener: Boolean,
        overlayUrlBlank: Boolean,
    ): RefreshMode {
        if (overlayUrlBlank) return RefreshMode.None
        if (isStreaming) return RefreshMode.StreamRefresh
        return RefreshMode.PreviewGlRefresh
    }

    fun shouldAttachGlOverlayOnPreview(isStreaming: Boolean): Boolean = !isStreaming

    fun shouldAttachGlOverlayOnStream(isStreaming: Boolean, overlayUrlBlank: Boolean): Boolean =
        isStreaming && !overlayUrlBlank
}

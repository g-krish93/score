package uk.co.cricrelay.mobile.feature.studio

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import uk.co.cricrelay.shared.model.OverlayLayoutPrefs
import uk.co.cricrelay.shared.model.Sponsor
import uk.co.cricrelay.shared.model.StreamMatch
import uk.co.cricrelay.shared.repository.StreamRepository
import uk.co.cricrelay.stream.StreamController

/**
 * Overlay/sponsor prefs plumbing for the studio: local-first persistence, engine sync, and the
 * best-effort server mirror. Extracted from [StudioViewModel] so the prefs flow and the
 * broadcast flow stop sharing one class; shares the ViewModel's UiState flow.
 */
class OverlaySyncController(
    private val uiState: MutableStateFlow<StudioUiState>,
    private val scope: CoroutineScope,
    private val streamController: StreamController,
    private val streamRepository: StreamRepository,
    private val localPrefs: StudioLocalPrefsStore,
) {

    fun syncOverlay(match: StreamMatch, prefs: OverlayLayoutPrefs) {
        if (match.overlayEmbedUrl.isBlank()) return
        val logoUrls = prefs.resolveSponsorLogoUrls(uiState.value.sponsors)
        streamController.updateOverlay(match.overlayEmbedUrl, prefs.toEngineLayout(logoUrls))
    }

    fun syncSponsorLayer(
        prefs: OverlayLayoutPrefs,
        sponsors: List<Sponsor> = uiState.value.sponsors,
    ) {
        val match = uiState.value.match ?: return
        val logoUrls = prefs.resolveSponsorLogoUrls(sponsors)
        if (match.overlayEmbedUrl.isBlank()) {
            streamController.setSponsorLayer(prefs.sponsorEnabled, logoUrls)
            return
        }
        streamController.updateOverlay(match.overlayEmbedUrl, prefs.toEngineLayout(logoUrls))
    }

    /** Push overlay/sponsor prefs to the camera preview without persisting to the server. */
    fun previewOverlayPrefs(prefs: OverlayLayoutPrefs) {
        val match = uiState.value.match ?: return
        syncOverlay(match, prefs)
        syncSponsorLayer(prefs)
    }

    /** Restore the last saved overlay on the preview after cancel/dismiss without save. */
    fun revertOverlayPreview() {
        previewOverlayPrefs(uiState.value.overlayPrefs)
    }

    fun updateOverlayPrefs(prefs: OverlayLayoutPrefs) {
        val match = uiState.value.match ?: return
        // Local persistence + camera apply first — the studio must work fully offline.
        localPrefs.saveOverlayPrefs(match.slug, prefs)
        localPrefs.saveDeviceSettings(
            DeviceStreamSettings(
                stabilizationLevel = prefs.stabilizationLevel,
                keepScreenOn = prefs.keepScreenOn,
            ),
        )
        streamController.setStabilizationLevel(prefs.stabilizationLevel)
        streamController.setKeepScreenOnDuringStream(prefs.keepScreenOn)
        syncOverlay(match, prefs)
        syncSponsorLayer(prefs)
        uiState.update { it.copy(overlayPrefs = prefs) }
        // Best-effort mirror to the server so the club dashboard and remote companion stay
        // informed — a failure never blocks or reverts the local studio.
        scope.launch {
            runCatching { streamRepository.setOverlayPrefs(match.slug, prefs) }
        }
    }
}

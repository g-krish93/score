package uk.co.cricrelay.mobile.feature.studio

import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.mockk
import io.mockk.verify
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import uk.co.cricrelay.shared.model.OverlayLayoutPrefs
import uk.co.cricrelay.shared.model.Sponsor
import uk.co.cricrelay.shared.model.StreamMatch
import uk.co.cricrelay.shared.repository.StreamRepository
import uk.co.cricrelay.stream.StreamController

@OptIn(ExperimentalCoroutinesApi::class)
class OverlaySyncControllerTest {

    private val match = StreamMatch(
        slug = "village-vs-town",
        label = "Village vs Town",
        overlayEmbedUrl = "https://club.example.com/overlay/village-vs-town",
    )

    private val streamController = mockk<StreamController>(relaxed = true)
    private val streamRepository = mockk<StreamRepository>(relaxed = true)
    private val localPrefs = mockk<StudioLocalPrefsStore>(relaxed = true)

    private fun controller(
        state: MutableStateFlow<StudioUiState>,
        scope: kotlinx.coroutines.CoroutineScope,
    ) = OverlaySyncController(state, scope, streamController, streamRepository, localPrefs)

    @Test
    fun `updateOverlayPrefs persists locally, applies to the engine, and mirrors to the server`() = runTest {
        val state = MutableStateFlow(StudioUiState(match = match, loading = false))
        val sync = controller(state, this)
        val prefs = OverlayLayoutPrefs(fontScale = 1.4, stabilizationLevel = 2, keepScreenOn = false)

        sync.updateOverlayPrefs(prefs)

        // Local-first: cache + engine apply happen synchronously, before any network.
        verify { localPrefs.saveOverlayPrefs(match.slug, prefs) }
        verify {
            localPrefs.saveDeviceSettings(
                DeviceStreamSettings(stabilizationLevel = 2, keepScreenOn = false),
            )
        }
        verify { streamController.setStabilizationLevel(2) }
        verify { streamController.setKeepScreenOnDuringStream(false) }
        assertEquals(prefs, state.value.overlayPrefs)

        advanceUntilIdle()
        coVerify { streamRepository.setOverlayPrefs(match.slug, prefs) }
    }

    @Test
    fun `server mirror failure never reverts the local studio`() = runTest {
        coEvery { streamRepository.setOverlayPrefs(any(), any()) } throws RuntimeException("offline")
        val state = MutableStateFlow(StudioUiState(match = match, loading = false))
        val sync = controller(state, this)
        val prefs = OverlayLayoutPrefs(sponsorEnabled = true)

        sync.updateOverlayPrefs(prefs)
        advanceUntilIdle()

        assertEquals(prefs, state.value.overlayPrefs)
        verify { localPrefs.saveOverlayPrefs(match.slug, prefs) }
    }

    @Test
    fun `updateOverlayPrefs without a match is a no-op`() = runTest {
        val state = MutableStateFlow(StudioUiState(match = null))
        val sync = controller(state, this)

        sync.updateOverlayPrefs(OverlayLayoutPrefs(fontScale = 1.4))
        advanceUntilIdle()

        verify(exactly = 0) { localPrefs.saveOverlayPrefs(any(), any()) }
        coVerify(exactly = 0) { streamRepository.setOverlayPrefs(any(), any()) }
        assertEquals(1.0, state.value.overlayPrefs.fontScale, 0.0)
    }

    @Test
    fun `previewOverlayPrefs pushes to the engine without persisting anywhere`() = runTest {
        val state = MutableStateFlow(StudioUiState(match = match, loading = false))
        val sync = controller(state, this)
        val draft = OverlayLayoutPrefs(anchorY = 0.5)

        sync.previewOverlayPrefs(draft)
        advanceUntilIdle()

        verify(atLeast = 1) { streamController.updateOverlay(match.overlayEmbedUrl, any()) }
        verify(exactly = 0) { localPrefs.saveOverlayPrefs(any(), any()) }
        coVerify(exactly = 0) { streamRepository.setOverlayPrefs(any(), any()) }
        // The committed prefs in state stay untouched by a preview.
        assertEquals(0.85, state.value.overlayPrefs.anchorY, 0.0)
    }

    @Test
    fun `syncOverlay is skipped when the match has no overlay embed url`() = runTest {
        val bare = match.copy(overlayEmbedUrl = "")
        val state = MutableStateFlow(StudioUiState(match = bare, loading = false))
        val sync = controller(state, this)

        sync.syncOverlay(bare, OverlayLayoutPrefs())

        verify(exactly = 0) { streamController.updateOverlay(any(), any()) }
    }

    @Test
    fun `syncSponsorLayer drives the native layer when there is no overlay embed`() = runTest {
        val bare = match.copy(overlayEmbedUrl = "")
        val sponsors = listOf(
            Sponsor(id = "sp-1", name = "Bakery", logoUrl = "https://cdn.example.com/bakery.png"),
        )
        val state = MutableStateFlow(StudioUiState(match = bare, loading = false, sponsors = sponsors))
        val sync = controller(state, this)
        val prefs = OverlayLayoutPrefs(sponsorEnabled = true, activeSponsorIds = listOf("sp-1"))

        sync.syncSponsorLayer(prefs)

        verify {
            streamController.setSponsorLayer(true, listOf("https://cdn.example.com/bakery.png"))
        }
        verify(exactly = 0) { streamController.updateOverlay(any(), any()) }
    }

    @Test
    fun `revertOverlayPreview replays the last committed prefs`() = runTest {
        val committed = OverlayLayoutPrefs(fontScale = 1.8)
        val state = MutableStateFlow(
            StudioUiState(match = match, loading = false, overlayPrefs = committed),
        )
        val sync = controller(state, this)

        sync.revertOverlayPreview()

        verify(atLeast = 1) { streamController.updateOverlay(match.overlayEmbedUrl, any()) }
        assertTrue(state.value.overlayPrefs === committed)
    }
}

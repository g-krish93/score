package uk.co.cricrelay.mobile.feature.studio

import androidx.lifecycle.viewModelScope
import app.cash.turbine.ReceiveTurbine
import app.cash.turbine.test
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import uk.co.cricrelay.mobile.database.StreamDao
import uk.co.cricrelay.mobile.database.StreamEntity
import uk.co.cricrelay.shared.model.GoLiveResult
import uk.co.cricrelay.shared.model.MatchDayStatus
import uk.co.cricrelay.shared.model.BroadcastStatus
import uk.co.cricrelay.shared.model.OverlayLayoutPrefs
import uk.co.cricrelay.shared.model.PlatformStatus
import uk.co.cricrelay.shared.model.RemoteCommand
import uk.co.cricrelay.shared.model.StreamMatch
import uk.co.cricrelay.shared.repository.ApiClientProvider
import uk.co.cricrelay.shared.repository.StreamRepository
import uk.co.cricrelay.stream.StreamCameraEngine
import uk.co.cricrelay.stream.StreamCaptureService
import uk.co.cricrelay.stream.StreamController
import uk.co.cricrelay.stream.StreamEvent
import uk.co.cricrelay.stream.StreamStatus

private const val SLUG = "village-vs-town"

class StudioViewModelTest {

    @get:Rule
    val mainDispatcherRule = MainDispatcherRule()

    private val statusFlow = MutableStateFlow(StreamStatus())
    private val statsFlow = MutableStateFlow<StreamCameraEngine.StreamStats?>(null)
    private val pipFlow = MutableStateFlow(false)

    private val match = StreamMatch(
        slug = SLUG,
        label = "Village vs Town",
        overlayEmbedUrl = "https://club.example.com/overlay/$SLUG",
    )

    private lateinit var streamController: StreamController
    private lateinit var streamRepository: StreamRepository
    private lateinit var apiClientProvider: ApiClientProvider
    private lateinit var rtmpStore: RtmpCredentialsStore
    private lateinit var localPrefs: StudioLocalPrefsStore
    private lateinit var streamDao: StreamDao
    private lateinit var viewModel: StudioViewModel

    @Before
    fun setUp() {
        // StreamController is a concrete Hilt singleton backed by the StreamCameraEngine object;
        // MockK builds the mock without running its init block, so the engine is never touched.
        streamController = mockk(relaxed = true) {
            every { status } returns statusFlow
            every { streamStats } returns statsFlow
            every { pipMode } returns pipFlow
            every { isFocusLocked() } returns false
            every { currentZoom() } returns 1f
            every { supports1080p() } returns false
            every { preparePreview(any(), any(), any(), any(), any()) } returns true
            every {
                startStream(any(), any(), any(), any(), any(), any(), any(), any())
            } returns "rtmp://endpoint/live"
        }
        streamRepository = mockk(relaxed = true) {
            coEvery { listStreams() } returns listOf(match)
            coEvery { getOverlayPrefs(any()) } returns OverlayLayoutPrefs()
            coEvery { listSponsors() } returns emptyList()
            coEvery { getScoring(any()) } throws RuntimeException("offline")
            coEvery { youtubePlatformStatus() } returns PlatformStatus()
            coEvery { twitchPlatformStatus() } returns PlatformStatus()
            coEvery { measureUploadMbps() } returns null
            coEvery { pollRemoteCommands(any()) } returns emptyList()
            coEvery { getMatchDayStatus(any()) } returns MatchDayStatus(
                slug = SLUG,
                label = "Village vs Town",
                scoringMode = "manual",
                scoringActive = false,
                scoringStale = false,
                relayPaused = false,
                broadcast = BroadcastStatus(),
            )
        }
        apiClientProvider = mockk(relaxed = true)
        rtmpStore = mockk(relaxed = true) {
            every { load(any()) } returns RtmpCredentials()
            every { isPrecheckDone() } returns true
        }
        localPrefs = mockk(relaxed = true) {
            every { loadOverlayPrefs(any()) } returns null
            every { loadDeviceSettings() } returns DeviceStreamSettings()
        }
        streamDao = mockk {
            coEvery { getBySlug(any()) } returns null
        }
    }

    @After
    fun tearDown() {
        // Backstop only — the real cleanup happens inside runStudioTest.
        if (::viewModel.isInitialized) viewModel.viewModelScope.cancel()
    }

    /**
     * runTest that cancels the ViewModel's scope before runTest's own cleanup runs. The
     * ViewModel keeps perpetual polling loops (match-day, remote commands) plus the live
     * timer on the shared virtual-time scheduler; without this, runTest's final
     * advance-until-idle never goes idle and the test hangs forever.
     */
    private fun runStudioTest(body: suspend TestScope.() -> Unit) = runTest {
        try {
            body()
        } finally {
            if (::viewModel.isInitialized) viewModel.viewModelScope.cancel()
        }
    }

    private fun loadedViewModel(): StudioViewModel {
        viewModel = StudioViewModel(
            streamRepository, streamController, apiClientProvider, rtmpStore, localPrefs, streamDao,
        )
        viewModel.load(SLUG)
        return viewModel
    }

    private suspend fun ReceiveTurbine<StudioUiState>.awaitUntil(
        predicate: (StudioUiState) -> Boolean,
    ): StudioUiState {
        while (true) {
            val item = awaitItem()
            if (predicate(item)) return item
        }
    }

    // ── status events ───────────────────────────────────────────────────────

    @Test
    fun `reconnecting event raises the amber banner and connected clears it`() = runStudioTest {
        val vm = loadedViewModel()
        vm.uiState.test {
            statusFlow.value = StreamStatus(
                previewReady = true,
                streaming = true,
                lastEvent = StreamEvent(StreamCaptureService.EVENT_RECONNECTING, "retrying"),
            )
            val reconnecting = awaitUntil { it.reconnecting }
            // Still live — the banner is a warning, not an error state.
            assertTrue(reconnecting.streaming)
            assertNull(reconnecting.error)

            statusFlow.value = StreamStatus(
                previewReady = true,
                streaming = true,
                lastEvent = StreamEvent(StreamCaptureService.EVENT_CONNECTED),
            )
            val recovered = awaitUntil { !it.reconnecting }
            assertTrue(recovered.streaming)
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun `user stop mid-reconnect drops the banner with the stream`() = runStudioTest {
        val vm = loadedViewModel()
        statusFlow.value = StreamStatus(
            previewReady = true,
            streaming = true,
            lastEvent = StreamEvent(StreamCaptureService.EVENT_RECONNECTING, "retrying"),
        )
        assertTrue(vm.uiState.value.reconnecting)

        // Engine reports the stream gone (user stop) without a reconnect resolution event.
        statusFlow.value = StreamStatus(previewReady = true, streaming = false)
        assertFalse(vm.uiState.value.reconnecting)
    }

    @Test
    fun `stream lost resets streaming and posts idle to the server`() = runStudioTest {
        val vm = loadedViewModel()
        statusFlow.value = StreamStatus(
            previewReady = true,
            streaming = true,
            lastEvent = StreamEvent(StreamCaptureService.EVENT_CONNECTED),
        )
        assertTrue(vm.uiState.value.streaming)

        statusFlow.value = StreamStatus(
            previewReady = true,
            streaming = false,
            lastEvent = StreamEvent(StreamCaptureService.EVENT_STREAM_LOST, "RTMP timeout."),
        )

        val state = vm.uiState.value
        assertFalse(state.streaming)
        assertFalse(state.reconnecting)
        assertFalse(state.busy)
        assertEquals(0L, state.liveElapsedSeconds)
        assertTrue(state.error!!.startsWith("Broadcast lost: RTMP timeout."))
        // The controller releases the foreground service; the server hears we're idle.
        verify { streamController.onStreamLost() }
        coVerify { streamRepository.updateBroadcastStatus(SLUG, "idle") }
    }

    @Test
    fun `overlay warning surfaces the banner but keeps broadcasting`() = runStudioTest {
        val vm = loadedViewModel()
        statusFlow.value = StreamStatus(
            previewReady = true,
            streaming = true,
            lastEvent = StreamEvent(
                StreamCaptureService.EVENT_OVERLAY_WARNING,
                "Sponsor logo failed to load",
            ),
        )
        val state = vm.uiState.value
        assertEquals("Sponsor logo failed to load", state.error)
        assertTrue(state.streaming)
    }

    // ── go live ─────────────────────────────────────────────────────────────

    @Test
    fun `goLive over custom rtmp starts the engine stream and goes live`() = runStudioTest {
        val vm = loadedViewModel()
        vm.updateCustomRtmp("rtmp://a.rtmp.example.com/live2", "key-1", "https://watch.example.com")

        vm.goLive()

        verify {
            streamController.startStream(
                "rtmp://a.rtmp.example.com/live2",
                "key-1",
                match.overlayEmbedUrl,
                any(), any(), any(), any(), any(),
            )
        }
        val state = vm.uiState.value
        assertTrue(state.streaming)
        assertFalse(state.busy)
        assertNull(state.error)
        assertEquals("Live", state.statusMessage)
        assertEquals("https://watch.example.com", state.watchUrl)
        // Custom RTMP is server-agnostic: no broadcast-status POST on the happy path.
        coVerify(exactly = 0) { streamRepository.updateBroadcastStatus(any(), any(), any(), any()) }
    }

    @Test
    fun `goLive failure surfaces the error and clears busy`() = runStudioTest {
        every {
            streamController.startStream(any(), any(), any(), any(), any(), any(), any(), any())
        } throws RuntimeException("Camera preview not ready yet")
        val vm = loadedViewModel()
        vm.updateCustomRtmp("rtmp://a.rtmp.example.com/live2", "key-1", "")

        vm.goLive()

        val state = vm.uiState.value
        assertFalse(state.streaming)
        assertFalse(state.busy)
        assertEquals("Camera preview not ready yet", state.error)
        assertEquals("", state.statusMessage)
    }

    @Test
    fun `goLive on a platform posts streaming status with the watch url`() = runStudioTest {
        coEvery { streamRepository.youtubePlatformStatus() } returns PlatformStatus(connected = true)
        coEvery { streamRepository.goLive(SLUG, "youtube") } returns GoLiveResult(
            rtmpUrl = "rtmp://yt.example.com/live",
            streamKey = "yt-key",
            watchUrl = "https://youtu.be/live-1",
        )
        val vm = loadedViewModel()
        vm.setDestination(StreamDestination.YouTube)
        assertTrue(vm.uiState.value.destinationReady)

        vm.goLive()

        verify {
            streamController.startStream(
                "rtmp://yt.example.com/live",
                "yt-key",
                match.overlayEmbedUrl,
                any(), any(), any(), any(), any(),
            )
        }
        coVerify {
            streamRepository.updateBroadcastStatus(SLUG, "streaming", "youtube", "https://youtu.be/live-1")
        }
        val state = vm.uiState.value
        assertTrue(state.streaming)
        assertEquals("https://youtu.be/live-1", state.watchUrl)
    }

    @Test
    fun `requestGoLive without a ready destination opens the destination sheet`() = runStudioTest {
        val vm = loadedViewModel()
        statusFlow.value = StreamStatus(previewReady = true)

        vm.requestGoLive()

        assertEquals(StudioSheet.Destination, vm.uiState.value.activeSheet)
    }

    // ── load / local-first extras ───────────────────────────────────────────

    @Test
    fun `cached overlay prefs skip the server seed entirely`() = runStudioTest {
        val cached = OverlayLayoutPrefs(fontScale = 1.5, sponsorEnabled = true)
        every { localPrefs.loadOverlayPrefs(SLUG) } returns cached

        val vm = loadedViewModel()

        assertEquals(1.5, vm.uiState.value.overlayPrefs.fontScale, 0.0)
        assertTrue(vm.uiState.value.overlayPrefs.sponsorEnabled)
        coVerify(exactly = 0) { streamRepository.getOverlayPrefs(any()) }
        // Cache hit must not be re-written either.
        verify(exactly = 0) { localPrefs.saveOverlayPrefs(any(), any()) }
    }

    @Test
    fun `cache miss seeds overlay prefs from the server and caches them`() = runStudioTest {
        val serverPrefs = OverlayLayoutPrefs(fontScale = 1.3)
        every { localPrefs.loadOverlayPrefs(SLUG) } returns null
        coEvery { streamRepository.getOverlayPrefs(SLUG) } returns serverPrefs

        val vm = loadedViewModel()

        assertEquals(1.3, vm.uiState.value.overlayPrefs.fontScale, 0.0)
        coVerify { streamRepository.getOverlayPrefs(SLUG) }
        verify { localPrefs.saveOverlayPrefs(SLUG, serverPrefs) }
    }

    @Test
    fun `device settings from this phone override the seeded prefs`() = runStudioTest {
        every { localPrefs.loadOverlayPrefs(SLUG) } returns OverlayLayoutPrefs(stabilizationLevel = 1)
        every { localPrefs.loadDeviceSettings() } returns DeviceStreamSettings(
            stabilizationLevel = 2,
            keepScreenOn = false,
        )

        val vm = loadedViewModel()

        val prefs = vm.uiState.value.overlayPrefs
        assertEquals(2, prefs.stabilizationLevel)
        assertFalse(prefs.keepScreenOn)
        verify { streamController.setStabilizationLevel(2) }
        verify { streamController.setKeepScreenOnDuringStream(false) }
    }

    @Test
    fun `load falls back to the cached match when the server list misses the slug`() = runStudioTest {
        coEvery { streamRepository.listStreams() } returns emptyList()
        coEvery { streamDao.getBySlug(SLUG) } returns StreamEntity(
            slug = SLUG,
            label = "Cached label",
            overlayEmbedUrl = "",
            relaySource = "scraper",
            relayPaused = false,
            scoringMode = "manual",
            scoringActive = false,
            scoringStale = false,
            isLive = false,
            broadcastStatus = "idle",
        )

        val vm = loadedViewModel()

        val state = vm.uiState.value
        assertFalse(state.loading)
        assertNull(state.error)
        assertEquals("Cached label", state.match?.label)
    }

    @Test
    fun `load with no server match and no cache reports the failure`() = runStudioTest {
        coEvery { streamRepository.listStreams() } returns emptyList()

        val vm = loadedViewModel()

        val state = vm.uiState.value
        assertFalse(state.loading)
        assertNull(state.match)
        assertEquals("Stream not found", state.error)
    }

    // ── remote companion commands ───────────────────────────────────────────

    @Test
    fun `remote mute command toggles the mic`() = runStudioTest {
        coEvery { streamRepository.pollRemoteCommands(SLUG) } returns listOf(
            RemoteCommand(type = "control", command = "mute_mic"),
        ) andThen emptyList()

        val vm = loadedViewModel()

        assertTrue(vm.uiState.value.micMuted)
        verify { streamController.setMicMuted(true) }
    }
}

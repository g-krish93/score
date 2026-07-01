# CricRelay Studio: Overheat Protection, Remote Control, Sponsor Overlay, Stabilization, Mic Mute

> **This plan is written for handoff to a less-capable coding tool (Cursor Composer).** Every section gives the exact current code (verbatim, with file path + line numbers) and the exact new/changed code to write. Where a full literal diff isn't given (some UI wiring is templated), the instruction names the exact existing function to pattern-match against, in the same file, so no invention is required.

## Context

While researching a competitor live-streaming app (PRISM Live) for feature ideas, we identified capabilities worth bringing into CricRelay's Studio broadcast screen: thermal/overheat protection for long matches (3-8 hours in direct sun is a real device-heat risk), a QR-paired remote control so a second crew member can operate start/stop/mute without touching the camera phone, a sponsor logo overlay (the `Sponsor` DB model already exists but has no rendering), fuller use of platform video stabilization, and a persistent microphone mute toggle.

Scope is the **primary native apps only**: `cricrelay-mobile/android/` (Kotlin/Compose) and `cricrelay-mobile/ios/` (SwiftUI), plus the `server/` Flask backend. The separate `cricrelay-stream/` Flutter companion app is **out of scope** — do not touch it.

Both platforms must stay in feature parity (see `docs/ARCHITECTURE.md`'s existing "Focus-lock" / "Broadcast resilience" rows, which document the few places Android/iOS deliberately differ — follow that same documentation discipline for any new asymmetry, and add rows there when this work lands).

## Decisions locked in with the user

- **Sponsor overlay ships ungated** — no Pro/paywall check for now (no entitlement system exists in the codebase yet).
- **Remote pairing = scan-to-trust** — no companion login required; scanning the QR code is sufficient authorization.
- **One active companion at a time** — pairing a new companion device invalidates the previous pairing.
- **Overheat mitigation is warn-only** — banner with a manual "Lower quality" button; never auto-change quality.
- **Remote control is phased**: build control commands now (start/stop, mute mic, toggle focus lock); keep the command envelope extensible for a future "companion as second camera" phase, without building that phase now.

## Build order

1. Overheat/thermal protection (touches the most sensitive engine lifecycle code — land first)
2. Stabilization (independent)
3. Microphone mute (independent; needed before #5's `mute_mic` command)
4. Sponsor overlay (touches `QuickToggles`/`OverlaySheet`, do after #3's UI slot lands)
5. Remote control phase 1 (depends on #3's mic-mute primitive)
6. UI/UX integration — folded into each step above, not a separate pass

---

## 1. Overheat / thermal protection

### 1.1 Android

**Current code** — `cricrelay-mobile/android/streaming/src/main/java/uk/co/cricrelay/stream/StreamCameraEngine.kt`, lines 268-311:

```kotlin
    private fun refreshDeviceTier(context: Context) {
        deviceTier = DeviceCapabilities.tier(context)
        overlayRefreshMs = DeviceCapabilities.overlayRefreshMs(deviceTier)
        if (DeviceCapabilities.isPowerSaveMode(context) || DeviceCapabilities.isThermalStressed(context)) {
            overlayRefreshMs = (overlayRefreshMs * 1.5).toLong().coerceAtMost(2500L)
        }
    }

    fun attachView(view: OpenGlView, act: Activity) {
        refreshDeviceTier(act.applicationContext)
        appContext = act.applicationContext
        audioManager = act.getSystemService(Context.AUDIO_SERVICE) as AudioManager
        activity = act
        if (openGlView === view && camera != null) {
            if (backgroundRendering) onExitBackground()
            return
        }
        ...
    }
```

`releaseCamera()` (lines 1031-1050) — full teardown path, where the thermal monitor should unregister:

```kotlin
    private fun releaseCamera() {
        stopPreviewOverlayPush()
        if (camera?.isStreaming == true) {
            stopStreamInternal()
        } else {
            stopPreviewOverlayRefresh()
            recycleOverlayBitmap()
            clearOverlayFilter()
            resetFocusState()
            try {
                camera?.stopPreview()
            } catch (_: Exception) {
            }
            encoderPrepared = false
            surfaceValid = false
        }
        camera = null
        openGlView = null
    }
```

**Add import** at the top of the file (in the existing import block, e.g. after `import android.os.Build`):
```kotlin
import android.os.PowerManager
```

**Add new fields** (in the `private var`/`private const` block near line 90-100, alongside `deviceTier`/`overlayRefreshMs`):
```kotlin
    private var thermalStatusListener: PowerManager.OnThermalStatusChangedListener? = null
    private var thermalPollRunnable: Runnable? = null
    private var lastThermalStatus: Int = PowerManager.THERMAL_STATUS_NONE
    private const val THERMAL_POLL_MS = 30_000L
```

**Add new functions** (anywhere in the class body, e.g. directly below `refreshDeviceTier`):
```kotlin
    private fun registerThermalMonitor(context: Context) {
        val pm = context.getSystemService(Context.POWER_SERVICE) as? PowerManager ?: return
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            if (thermalStatusListener != null) return
            val listener = PowerManager.OnThermalStatusChangedListener { status ->
                onThermalStatusChanged(status)
            }
            thermalStatusListener = listener
            try {
                pm.addThermalStatusListener(listener)
                onThermalStatusChanged(pm.currentThermalStatus)
            } catch (_: Exception) {
            }
        } else {
            if (thermalPollRunnable != null) return
            val runnable = object : Runnable {
                override fun run() {
                    val stressed = DeviceCapabilities.isThermalStressed(context)
                    onThermalStatusChanged(
                        if (stressed) PowerManager.THERMAL_STATUS_MODERATE else PowerManager.THERMAL_STATUS_NONE,
                    )
                    mainHandler.postDelayed(this, THERMAL_POLL_MS)
                }
            }
            thermalPollRunnable = runnable
            mainHandler.post(runnable)
        }
    }

    private fun unregisterThermalMonitor(context: Context) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            thermalStatusListener?.let { listener ->
                try {
                    (context.getSystemService(Context.POWER_SERVICE) as? PowerManager)
                        ?.removeThermalStatusListener(listener)
                } catch (_: Exception) {
                }
            }
            thermalStatusListener = null
        }
        thermalPollRunnable?.let { mainHandler.removeCallbacks(it) }
        thermalPollRunnable = null
    }

    private fun onThermalStatusChanged(status: Int) {
        lastThermalStatus = status
        overlayRefreshMs = when {
            status >= PowerManager.THERMAL_STATUS_SEVERE ->
                (DeviceCapabilities.overlayRefreshMs(deviceTier) * 2.5).toLong().coerceAtMost(3500L)
            status >= PowerManager.THERMAL_STATUS_MODERATE ->
                (DeviceCapabilities.overlayRefreshMs(deviceTier) * 1.5).toLong().coerceAtMost(2500L)
            else -> DeviceCapabilities.overlayRefreshMs(deviceTier)
        }
        emit("thermal", status.toString())
    }

    /**
     * Manual mitigation for the overheat banner's "Lower quality" button. Spike first: confirm
     * whether RootEncoder 2.4.8's RtmpCamera2 supports a live bitrate change (look for
     * `setVideoBitrateOnFly` or equivalent on `camera`). If it exists, call it here with a lower
     * value (e.g. current bitrate * 0.6). If it does NOT exist, this must stop+restart the stream
     * at a lower bitrate instead — flag that back before wiring the UI copy.
     */
    fun stepDownQuality() {
        val cam = camera ?: return
        // TODO(spike): replace with the real RootEncoder 2.4.8 live-bitrate API once confirmed.
    }
```

**Modify `attachView`** — add one line after `refreshDeviceTier(act.applicationContext)`:
```kotlin
    fun attachView(view: OpenGlView, act: Activity) {
        refreshDeviceTier(act.applicationContext)
        registerThermalMonitor(act.applicationContext)
        appContext = act.applicationContext
        ...
```

**Modify `releaseCamera`** — add one line at the top:
```kotlin
    private fun releaseCamera() {
        appContext?.let { unregisterThermalMonitor(it) }
        stopPreviewOverlayPush()
        ...
```

**Propagate into `StreamStatus`** — `cricrelay-mobile/android/streaming/src/main/java/uk/co/cricrelay/stream/StreamController.kt`:

Current (lines 12-48):
```kotlin
data class StreamEvent(val event: String, val message: String = "")

data class StreamStatus(
    val previewReady: Boolean = false,
    val streaming: Boolean = false,
    val paused: Boolean = false,
    val lastEvent: StreamEvent? = null,
)

@Singleton
class StreamController @Inject constructor() {
    private val _status = MutableStateFlow(StreamStatus())
    val status: StateFlow<StreamStatus> = _status.asStateFlow()
    ...
    init {
        StreamCameraEngine.setStatusListener { event, message ->
            _status.value = _status.value.copy(
                previewReady = StreamCameraEngine.isPreviewReady,
                streaming = StreamCameraEngine.isStreaming,
                paused = StreamCameraEngine.isStreamPaused,
                lastEvent = StreamEvent(event, message),
            )
        }
    }
```

New — add `thermalStatus: Int = android.os.PowerManager.THERMAL_STATUS_NONE` to `StreamStatus`, and update it whenever `event == "thermal"`:
```kotlin
data class StreamStatus(
    val previewReady: Boolean = false,
    val streaming: Boolean = false,
    val paused: Boolean = false,
    val lastEvent: StreamEvent? = null,
    val thermalStatus: Int = android.os.PowerManager.THERMAL_STATUS_NONE,
)

    init {
        StreamCameraEngine.setStatusListener { event, message ->
            _status.value = _status.value.copy(
                previewReady = StreamCameraEngine.isPreviewReady,
                streaming = StreamCameraEngine.isStreaming,
                paused = StreamCameraEngine.isStreamPaused,
                lastEvent = StreamEvent(event, message),
                thermalStatus = if (event == "thermal") {
                    message.toIntOrNull() ?: _status.value.thermalStatus
                } else {
                    _status.value.thermalStatus
                },
            )
        }
    }

    fun stepDownQuality() = StreamCameraEngine.stepDownQuality()
```

**`StudioViewModel.kt`** (`cricrelay-mobile/android/feature/studio/src/main/java/uk/co/cricrelay/mobile/feature/studio/StudioViewModel.kt`):

`StudioUiState` currently (lines 51-86) has no thermal field — add one:
```kotlin
    val focusLocked: Boolean = false,
    val thermalStatus: Int = android.os.PowerManager.THERMAL_STATUS_NONE,   // NEW
```

The existing status-collector (same block seen setting `streaming = status.streaming, paused = status.paused, focusLocked = streamController.isFocusLocked()`, around lines 138-150) gets one more copy line:
```kotlin
                        streaming = status.streaming,
                        paused = status.paused,
                        focusLocked = streamController.isFocusLocked(),
                        thermalStatus = status.thermalStatus,   // NEW
```

Add a manual mitigation entry point, mirroring `onToggleFocusLock` (lines 412-422)'s shape:
```kotlin
    fun onLowerQuality() {
        streamController.stepDownQuality()
    }
```

**UI** — `cricrelay-mobile/android/feature/studio/src/main/java/uk/co/cricrelay/mobile/feature/studio/BroadcastCameraUi.kt`, inside `StudioStatusMessages` (line 476-514, shown here in full as current code):

```kotlin
@Composable
private fun StudioStatusMessages(state: StudioUiState, modifier: Modifier = Modifier) {
    Column(modifier = modifier) {
        state.statusMessage.takeIf { it.isNotBlank() && !state.streaming }?.let { msg ->
            Text(
                msg,
                color = Color.White,
                modifier = Modifier
                    .padding(horizontal = 20.dp, vertical = 8.dp)
                    .fillMaxWidth()
                    .background(Color.Black.copy(alpha = 0.45f), CircleShape)
                    .padding(12.dp),
            )
        }
        state.error?.let {
            ErrorBanner(it, Modifier.padding(horizontal = 16.dp, vertical = 4.dp))
        }
        if (!state.streaming && !state.destinationReady) {
            Text(
                text = "Tap Dest to set YouTube, Twitch, or a stream key before Go Live",
                color = AppColors.Warning,
                modifier = Modifier
                    .padding(horizontal = 20.dp, vertical = 6.dp)
                    .fillMaxWidth()
                    .background(Color.Black.copy(alpha = 0.45f), CircleShape)
                    .padding(12.dp),
            )
        } else if (!state.streaming && !state.previewReady) {
            Text(
                text = "Preparing camera…",
                color = AppColors.Warning,
                modifier = Modifier
                    .padding(horizontal = 20.dp, vertical = 6.dp)
                    .fillMaxWidth()
                    .background(Color.Black.copy(alpha = 0.35f), CircleShape)
                    .padding(12.dp),
            )
        }
    }
}
```

Requires a new parameter `onLowerQuality: () -> Unit` threaded through (this function is called from `PortraitControls`/`LandscapeControls`, which already receive many callback params — add `onLowerQuality` alongside the existing `onToggleStabilization`/etc. params at every call site in that file).

New code — add this block right after the `state.error?.let { ... }` block, before the destination-not-ready check:
```kotlin
        if (state.thermalStatus >= /* PowerManager.THERMAL_STATUS_SEVERE = */ 3) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier
                    .padding(horizontal = 20.dp, vertical = 6.dp)
                    .fillMaxWidth()
                    .background(Color.Black.copy(alpha = 0.5f), CircleShape)
                    .padding(horizontal = 12.dp, vertical = 8.dp),
            ) {
                Icon(
                    Icons.Outlined.Whatshot,
                    contentDescription = null,
                    tint = AppColors.Warning,
                    modifier = Modifier.size(18.dp),
                )
                Spacer(Modifier.width(8.dp))
                Text(
                    text = "Phone is overheating — quality may drop automatically soon.",
                    color = AppColors.Warning,
                    modifier = Modifier.weight(1f),
                )
                if (state.thermalStatus >= /* PowerManager.THERMAL_STATUS_CRITICAL = */ 4) {
                    Spacer(Modifier.width(8.dp))
                    TextButton(onClick = onLowerQuality) {
                        Text("Lower quality", color = AppColors.Warning, fontWeight = FontWeight.Bold)
                    }
                }
            }
        }
```

(Use the literal `PowerManager.THERMAL_STATUS_SEVERE`/`THERMAL_STATUS_CRITICAL` constants via `import android.os.PowerManager` instead of the magic numbers `3`/`4` shown above — they are stable API constants but importing is cleaner.)

### 1.2 iOS

**Current code** — `cricrelay-mobile/ios/Streaming/StreamCameraEngine.swift`:

`registerLifecycleObservers()`, lines 748-763 (pattern to mirror):
```swift
private func registerLifecycleObservers() {
    guard !lifecycleObserversRegistered else { return }
    lifecycleObserversRegistered = true
    NotificationCenter.default.addObserver(
        self,
        selector: #selector(appDidEnterBackground),
        name: UIApplication.didEnterBackgroundNotification,
        object: nil
    )
    NotificationCenter.default.addObserver(
        self,
        selector: #selector(appWillEnterForeground),
        name: UIApplication.willEnterForegroundNotification,
        object: nil
    )
}
```

`startOverlayRefresh()`, lines 681-692 (fixed 0.5s interval to make dynamic):
```swift
private func startOverlayRefresh() {
    DispatchQueue.main.async { [weak self] in
        guard let self else { return }
        self.overlayTimer?.invalidate()
        self.overlayTimer = Timer.scheduledTimer(withTimeInterval: 0.5, repeats: true) { [weak self] _ in
            self?.refreshOverlayFrame()
        }
    }
    DispatchQueue.main.asyncAfter(deadline: .now() + 0.8) { [weak self] in
        self?.refreshOverlayFrame()
    }
}
```

Status handler mechanism, lines 51/68-70/734-738:
```swift
private var statusHandler: ((String, String) -> Void)?

func setStatusHandler(_ handler: ((String, String) -> Void)?) {
    statusHandler = handler
}

private func emit(_ event: String, _ message: String) {
    DispatchQueue.main.async { [weak self] in
        self?.statusHandler?(event, message)
    }
}
```

**New field** (add near other `private var` declarations, e.g. next to `overlayTimer`):
```swift
private var overlayRefreshInterval: TimeInterval = 0.5
private var lastThermalState: ProcessInfo.ThermalState = .nominal
```

**Modify `registerLifecycleObservers()`** — add a third observer:
```swift
private func registerLifecycleObservers() {
    guard !lifecycleObserversRegistered else { return }
    lifecycleObserversRegistered = true
    NotificationCenter.default.addObserver(
        self,
        selector: #selector(appDidEnterBackground),
        name: UIApplication.didEnterBackgroundNotification,
        object: nil
    )
    NotificationCenter.default.addObserver(
        self,
        selector: #selector(appWillEnterForeground),
        name: UIApplication.willEnterForegroundNotification,
        object: nil
    )
    NotificationCenter.default.addObserver(
        self,
        selector: #selector(thermalStateChanged),
        name: ProcessInfo.thermalStateDidChangeNotification,
        object: nil
    )
    thermalStateChanged()   // seed the initial state immediately
}

@objc private func thermalStateChanged() {
    let state = ProcessInfo.processInfo.thermalState
    lastThermalState = state
    switch state {
    case .critical:
        overlayRefreshInterval = 1.75
    case .serious:
        overlayRefreshInterval = 1.0
    default:
        overlayRefreshInterval = 0.5
    }
    let raw: Int
    switch state {
    case .nominal: raw = 0
    case .fair: raw = 1
    case .serious: raw = 2
    case .critical: raw = 3
    @unknown default: raw = 0
    }
    emit("thermal", String(raw))
}
```

(The `raw` mapping `nominal=0/fair=1/serious=2/critical=3` mirrors Android's `PowerManager.THERMAL_STATUS_NONE=0/LIGHT=1/MODERATE=2/SEVERE=3` ordering closely enough that the shared/KMP layer and both UIs can treat "status >= 2" as the same "show the banner" threshold on both platforms — treat iOS `.serious`(2) as the Android `SEVERE`(3)-equivalent "show banner" trigger, and `.critical`(3) as the "show Lower Quality button" trigger, since iOS only has 4 levels vs Android's 6.)

**Modify `startOverlayRefresh()`** to use the dynamic interval:
```swift
private func startOverlayRefresh() {
    DispatchQueue.main.async { [weak self] in
        guard let self else { return }
        self.overlayTimer?.invalidate()
        self.overlayTimer = Timer.scheduledTimer(withTimeInterval: self.overlayRefreshInterval, repeats: true) { [weak self] _ in
            self?.refreshOverlayFrame()
        }
    }
    DispatchQueue.main.asyncAfter(deadline: .now() + 0.8) { [weak self] in
        self?.refreshOverlayFrame()
    }
}
```

**Add a `stepDownQuality()` stub**, mirroring the Android spike note:
```swift
/// Manual mitigation for the "Lower quality" banner button.
/// Spike first: HaishinKit's RTMPStream/MediaMixer live-bitrate change API (check `videoSettings`
/// mutation while publishing) before wiring this up for real.
func stepDownQuality() {
    // TODO(spike): apply a lower bitrate via the real HaishinKit API once confirmed.
}
```

**`StudioViewModel.swift`** — add to the `@Published` block (shown in full above, insert near `focusLocked`):
```swift
@Published var thermalLevel: Int = 0
```

Wire it in the same place `setStatusHandler` results are consumed (search for where `focusLocked`/`error` are set from `StreamCameraEngine.shared.setStatusHandler { ... }` — mirror `toggleFocusLock()`'s direct-call shape for a new function):
```swift
func onLowerQuality() {
    StreamCameraEngine.shared.stepDownQuality()
}
```
And inside the existing `setStatusHandler` closure, add a case: when `event == "thermal"`, do `thermalLevel = Int(message) ?? thermalLevel`.

**UI** — `StudioView.swift`: mirror the `errorBanner(_:)` pattern (lines 463-481, shown in full above) with a new `thermalBanner` computed similarly:
```swift
private var thermalBanner: some View {
    HStack(spacing: 10) {
        Image(systemName: "flame.fill")
            .foregroundStyle(CricTheme.warning)   // use whatever warning color token errorBanner's sibling uses; if none exists, reuse CricTheme.danger at reduced opacity
            .font(.footnote)
        Text("Phone is overheating — quality may drop automatically soon.")
            .font(.footnote)
            .foregroundStyle(CricTheme.warning)
            .lineLimit(2)
        Spacer()
        if viewModel.thermalLevel >= 3 {
            Button("Lower quality") { Task { viewModel.onLowerQuality() } }
                .font(.footnote.bold())
                .foregroundStyle(CricTheme.warning)
        }
    }
    .padding(12)
    .background(CricTheme.warning.opacity(0.15), in: RoundedRectangle(cornerRadius: 12))
    .padding(.horizontal, 16)
    .padding(.bottom, 8)
}
```
Render it conditionally (`if viewModel.thermalLevel >= 2 { thermalBanner }`) in the same place `errorBanner(...)` is invoked in the view body.

---

## 2. Stabilization

### 2.1 Android — no code change

`StreamCameraEngine.kt` line 831-836 (current, keep as-is):
```kotlin
            if (videoStabilizationEnabled && deviceTier != DeviceCapabilities.Tier.LOW) {
                try {
                    cam.enableVideoStabilization()
                } catch (_: Exception) {
                }
            }
```
RootEncoder 2.4.8's `enableVideoStabilization()` is already the ceiling (wraps `CONTROL_VIDEO_STABILIZATION_MODE_ON`, standard EIS only — no separate OIS/preview-stabilization API exposed at this version). Confirm this against the RootEncoder 2.4.8 sources if available; otherwise treat it as a known library limit and make no change.

### 2.2 iOS

**Current code** — `StreamCameraEngine.swift`:

`ensureDevices()`, lines 514-533:
```swift
private func ensureDevices() async throws {
    configureAudioSession()
    if !devicesAttached {
        if let audio = AVCaptureDevice.default(for: .audio) {
            try await mixer.attachAudio(audio)
        }
        if let camera = AVCaptureDevice.default(.builtInWideAngleCamera, for: .video, position: .back) {
            let stabilizationEnabled = videoStabilizationEnabled
            try await mixer.attachVideo(camera, track: 0) { unit in
                unit.preferredVideoStabilizationMode = stabilizationEnabled ? .standard : .off
            }
        }
        var vmSettings = await mixer.videoMixerSettings
        vmSettings.mode = .offscreen
        vmSettings.mainTrack = 0
        await mixer.setVideoMixerSettings(vmSettings)
        devicesAttached = true
    }
    await mixer.startRunning()
}
```

`applyVideoStabilizationSetting()`, lines 541-548:
```swift
private func applyVideoStabilizationSetting() async {
    guard devicesAttached else { return }
    let enabled = videoStabilizationEnabled
    try? await mixer.configuration(video: 0) { unit in
        unit.preferredVideoStabilizationMode = enabled ? .standard : .off
    }
}
```

**New code** — change `.standard` to `.cinematicExtended` in both places:
```swift
private func ensureDevices() async throws {
    configureAudioSession()
    if !devicesAttached {
        if let audio = AVCaptureDevice.default(for: .audio) {
            try await mixer.attachAudio(audio)
        }
        if let camera = AVCaptureDevice.default(.builtInWideAngleCamera, for: .video, position: .back) {
            let stabilizationEnabled = videoStabilizationEnabled
            try await mixer.attachVideo(camera, track: 0) { unit in
                unit.preferredVideoStabilizationMode = stabilizationEnabled ? .cinematicExtended : .off
            }
        }
        var vmSettings = await mixer.videoMixerSettings
        vmSettings.mode = .offscreen
        vmSettings.mainTrack = 0
        await mixer.setVideoMixerSettings(vmSettings)
        devicesAttached = true
    }
    await mixer.startRunning()
}

private func applyVideoStabilizationSetting() async {
    guard devicesAttached else { return }
    let enabled = videoStabilizationEnabled
    try? await mixer.configuration(video: 0) { unit in
        unit.preferredVideoStabilizationMode = enabled ? .cinematicExtended : .off
    }
}
```

No device-tier gating on iOS for v1 (Apple's hardware set is narrow enough; skip building an iOS `DeviceCapabilities` equivalent for this alone). Add a one-line caption under the "Stabilize" toggle wherever it's exposed in `StudioSheets.swift`'s `OverlaySheet` (if a stabilization control lives there) or as a tooltip on the quick-toggle pill: *"Strong stabilization slightly narrows the camera's field of view."*

---

## 3. Microphone mute

Not persisted server-side — ephemeral, like `focusLocked`.

### 3.1 Android

**Current code** — `StreamCameraEngine.kt`, `pauseStreamInternal()`/`resumeStreamInternal()`, lines 985-1018:
```kotlin
    private fun pauseStreamInternal() {
        val cam = camera ?: return
        if (!cam.isStreaming || streamPaused) return
        streamPaused = true
        stopOverlayRefresh()
        try {
            cam.disableAudio()
        } catch (_: Exception) {
        }
        try {
            if (pauseBlackFilter == null) {
                val filter = BlackFilterRender()
                pauseBlackFilter = filter
                cam.glInterface.addFilter(filter)
            }
        } catch (_: Exception) {
        }
        emit(StreamCaptureService.EVENT_PAUSED, "")
    }

    private fun resumeStreamInternal() {
        val cam = camera ?: return
        if (!cam.isStreaming || !streamPaused) return
        streamPaused = false
        removePauseBlackFilter()
        try {
            cam.enableAudio()
        } catch (_: Exception) {
        }
        if (overlayUrl.isNotEmpty() && imageFilter != null) {
            startOverlayRefresh()
        }
        emit(StreamCaptureService.EVENT_RESUMED, "")
    }
```

**New field** (near `streamPaused`):
```kotlin
    private var micMuted = false
```

**New function**:
```kotlin
    fun setMicMuted(muted: Boolean) {
        micMuted = muted
        val cam = camera ?: return
        // pauseStreamInternal() already calls disableAudio() — don't fight it; only touch
        // audio directly here when the stream isn't already paused.
        if (streamPaused) return
        try {
            if (muted) cam.disableAudio() else cam.enableAudio()
        } catch (_: Exception) {
        }
    }

    fun isMicMuted(): Boolean = micMuted
```

**Modify `resumeStreamInternal()`** so it doesn't clobber an explicit mute — change the unconditional `cam.enableAudio()` to respect `micMuted`:
```kotlin
    private fun resumeStreamInternal() {
        val cam = camera ?: return
        if (!cam.isStreaming || !streamPaused) return
        streamPaused = false
        removePauseBlackFilter()
        try {
            if (!micMuted) cam.enableAudio()
        } catch (_: Exception) {
        }
        if (overlayUrl.isNotEmpty() && imageFilter != null) {
            startOverlayRefresh()
        }
        emit(StreamCaptureService.EVENT_RESUMED, "")
    }
```

**`StreamController.kt`** — add a wrapper next to `setVideoStabilization` (line 176):
```kotlin
    fun setMicMuted(muted: Boolean) = StreamCameraEngine.setMicMuted(muted)

    fun isMicMuted(): Boolean = StreamCameraEngine.isMicMuted()
```

**`StudioUiState`** — add field:
```kotlin
    val micMuted: Boolean = false,
```

**`StudioViewModel.kt`** — add toggle function, mirroring `onToggleFocusLock` (lines 412-422):
```kotlin
    fun onToggleMicMuted() {
        val next = !_uiState.value.micMuted
        streamController.setMicMuted(next)
        _uiState.update { it.copy(micMuted = next) }
    }
```

**UI** — `BroadcastCameraUi.kt`, `QuickToggles` (lines 323-374, shown in full above as current code). Add a 4th pill and a new parameter:
```kotlin
@Composable
private fun QuickToggles(
    state: StudioUiState,
    onToggleStabilization: () -> Unit,
    onToggleKeepScreenOn: () -> Unit,
    onToggleFocusLock: () -> Unit,
    onToggleMicMuted: () -> Unit,   // NEW param
    modifier: Modifier = Modifier,
    vertical: Boolean = false,
) {
    val focusLock: @Composable () -> Unit = { /* unchanged */ }
    val stabilize: @Composable () -> Unit = { /* unchanged */ }
    val screenOn: @Composable () -> Unit = { /* unchanged */ }
    val micMute: @Composable () -> Unit = {   // NEW
        CameraQuickToggle(
            label = if (state.micMuted) "Muted" else "Mic",
            active = state.micMuted,
            icon = if (state.micMuted) Icons.Outlined.MicOff else Icons.Outlined.Mic,
            onClick = onToggleMicMuted,
        )
    }
    if (vertical) {
        Column(
            modifier = modifier,
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            focusLock(); stabilize(); screenOn(); micMute()
        }
    } else {
        Row(
            modifier = modifier,
            horizontalArrangement = Arrangement.spacedBy(10.dp, Alignment.CenterHorizontally),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            focusLock(); stabilize(); screenOn(); micMute()
        }
    }
}
```
Thread `onToggleMicMuted` through every call site of `QuickToggles(...)` in `PortraitControls`/`LandscapeControls` (2 call sites shown in the current-code excerpt above, lines 577-583 and 678-684) the same way `onToggleFocusLock` is already threaded, and from `StudioScreen.kt` down to `StudioViewModel.onToggleMicMuted()` the same way `onToggleStabilization` is wired (line 181-184 area).

### 3.2 iOS

**Current code** — `StreamCameraEngine.swift`:
```swift
func pauseStream() async {
    guard publishing, !streamPaused else { return }
    streamPaused = true
    stopOverlayRefresh()
    await showPauseBlackOverlay()
    try? AVAudioSession.sharedInstance().setActive(false, options: .notifyOthersOnDeactivation)
    emit("paused", "")
}

func resumeStream() async {
    guard publishing, streamPaused else { return }
    streamPaused = false
    await hidePauseBlackOverlay()
    configureAudioSession()
    startOverlayRefresh()
    emit("resumed", "")
}

private func configureAudioSession() {
    let session = AVAudioSession.sharedInstance()
    try? session.setCategory(.playAndRecord, mode: .videoChat, options: [.defaultToSpeaker, .allowBluetooth])
    try? session.setActive(true)
}
```

**Spike required before writing this**: check whether `MediaMixer` (HaishinKit) exposes a per-track audio mute/gain setting analogous to the existing `videoMixerSettings`/`setVideoMixerSettings` shown in `ensureDevices()`. Search HaishinKit's `MediaMixer`/`AudioMixerSettings` API surface (not present anywhere in this file today — only `vmSettings`/video settings are used).

**If a track-level mute API exists** (preferred), add:
```swift
private var micMuted = false

func setMicMuted(_ muted: Bool) async {
    micMuted = muted
    // Replace with the real per-track audio API once confirmed, e.g.:
    // var amSettings = await mixer.audioMixerSettings
    // amSettings.tracks[0]?.isMuted = muted
    // await mixer.setAudioMixerSettings(amSettings)
}

func isMicMuted() -> Bool { micMuted }
```

**If no track-level API exists** (fallback), mute by detaching/reattaching the audio input:
```swift
private var micMuted = false

func setMicMuted(_ muted: Bool) async {
    guard micMuted != muted else { return }
    micMuted = muted
    if muted {
        await mixer.detachAudio()
    } else if let audio = AVCaptureDevice.default(for: .audio) {
        try? await mixer.attachAudio(audio)
    }
}

func isMicMuted() -> Bool { micMuted }
```

**Modify `resumeStream()`** to respect an explicit mute (mirrors the Android guard):
```swift
func resumeStream() async {
    guard publishing, streamPaused else { return }
    streamPaused = false
    await hidePauseBlackOverlay()
    configureAudioSession()
    if !micMuted {
        // re-attach / unmute path only if not explicitly muted by the operator
    }
    startOverlayRefresh()
    emit("resumed", "")
}
```

**`StudioViewModel.swift`** — add `@Published var micMuted: Bool = false` next to `focusLocked`, and:
```swift
func toggleMicMuted() async {
    let next = !micMuted
    await StreamCameraEngine.shared.setMicMuted(next)
    micMuted = next
}
```

**UI** — `StudioView.swift`, `quickToggleRow` (lines 241-261, shown in full above). Add a 4th pill:
```swift
private var quickToggleRow: some View {
    HStack(spacing: 10) {
        quickTogglePill(
            label: viewModel.focusLocked ? "Locked" : "Focus",
            systemImage: viewModel.focusLocked ? "lock.fill" : "lock.open",
            active: viewModel.focusLocked
        ) { Task { await viewModel.toggleFocusLock() } }

        quickTogglePill(
            label: "Stabilize",
            systemImage: "gyroscope",
            active: viewModel.overlayPrefs.videoStabilization
        ) { Task { await viewModel.toggleStabilization() } }

        quickTogglePill(
            label: "Screen on",
            systemImage: "sun.max.fill",
            active: viewModel.overlayPrefs.keepScreenOn
        ) { Task { await viewModel.toggleKeepScreenOn() } }

        quickTogglePill(
            label: viewModel.micMuted ? "Muted" : "Mic",
            systemImage: viewModel.micMuted ? "mic.slash.fill" : "mic.fill",
            active: viewModel.micMuted
        ) { Task { await viewModel.toggleMicMuted() } }
    }
}
```
(`quickTogglePill(...)` helper is reused unchanged — its full implementation is quoted in section 3 background above, lines 263-286.)

---

## 4. Sponsor overlay

### 4.1 Server

**Current `Sponsor` model** — `server/models_cricrelay.py`, lines 275-291 (unchanged, quoted for reference):
```python
class Sponsor(db.Model):
    """Club sponsor on record — feeds the public page and Pro overlay slot."""

    __tablename__ = "cricrelay_sponsor"

    id = db.Column(db.String(36), primary_key=True, default=lambda: str(uuid.uuid4()))
    organization_id = db.Column(
        db.String(36), db.ForeignKey("cricrelay_org.id"), nullable=False, index=True
    )
    name = db.Column(db.String(200), nullable=False)
    logo_url = db.Column(db.String(1000), nullable=True)
    link_url = db.Column(db.String(1000), nullable=True)
    is_active = db.Column(db.Boolean, nullable=False, default=True)
    active_from = db.Column(db.DateTime, nullable=True)
    active_to = db.Column(db.DateTime, nullable=True)
    created_at = db.Column(db.DateTime, default=lambda: datetime.now(timezone.utc))
```
No column changes needed — this table is already sufficient. Schema is bootstrapped via `db.create_all()` in `app.py` (no Alembic in this project — confirmed no `migrations/` folder; schema changes for new *tables* just need `db.create_all()` to pick them up on next app start, but this table already exists).

**New CRUD routes** — add to `server/app.py`, near the existing stream CRUD routes (`api_patch_stream`/`api_delete_stream`, lines 3850-3880, quoted below as the pattern to mirror):

```python
# Existing pattern to mirror (lines 3850-3880):
@app.patch("/api/streams/<match_slug>")
@stream_api_auth_required
def api_patch_stream(org: Organization, match_slug: str):
    slug = sanitize_match_id(match_slug)
    row = relay_match_for_org(org, slug)
    if not row:
        return jsonify({"error": "unknown stream"}), 404
    data = request.get_json(silent=True) or {}
    if "label" in data:
        label = str(data.get("label") or "").strip()
        row.label = label or row.play_cricket_match_id
        db.session.commit()
    return jsonify({"ok": True, "stream": {"slug": slug, "label": row.label or row.play_cricket_match_id}})
```

New routes (add near the sponsor import already present at line 44):
```python
def _sponsor_json(s: Sponsor) -> dict:
    return {
        "id": s.id,
        "name": s.name,
        "logo_url": s.logo_url,
        "link_url": s.link_url,
        "is_active": s.is_active,
        "active_from": s.active_from.isoformat() if s.active_from else None,
        "active_to": s.active_to.isoformat() if s.active_to else None,
    }


@app.get("/api/sponsors")
@stream_api_auth_required
def api_list_sponsors(org: Organization):
    rows = Sponsor.query.filter_by(organization_id=org.id).order_by(Sponsor.created_at.desc()).all()
    return jsonify({"ok": True, "sponsors": [_sponsor_json(s) for s in rows]})


@app.post("/api/sponsors")
@stream_api_auth_required
def api_create_sponsor(org: Organization):
    data = request.get_json(silent=True) or {}
    name = str(data.get("name") or "").strip()
    if not name:
        return jsonify({"error": "name required"}), 400
    s = Sponsor(
        organization_id=org.id,
        name=name,
        logo_url=str(data.get("logo_url") or "").strip() or None,
        link_url=str(data.get("link_url") or "").strip() or None,
        is_active=bool(data.get("is_active", True)),
    )
    db.session.add(s)
    db.session.commit()
    return jsonify({"ok": True, "sponsor": _sponsor_json(s)})


@app.patch("/api/sponsors/<sponsor_id>")
@stream_api_auth_required
def api_patch_sponsor(org: Organization, sponsor_id: str):
    s = Sponsor.query.filter_by(id=sponsor_id, organization_id=org.id).first()
    if not s:
        return jsonify({"error": "unknown sponsor"}), 404
    data = request.get_json(silent=True) or {}
    if "name" in data:
        s.name = str(data.get("name") or "").strip() or s.name
    if "logo_url" in data:
        s.logo_url = str(data.get("logo_url") or "").strip() or None
    if "link_url" in data:
        s.link_url = str(data.get("link_url") or "").strip() or None
    if "is_active" in data:
        s.is_active = bool(data.get("is_active"))
    db.session.commit()
    return jsonify({"ok": True, "sponsor": _sponsor_json(s)})


@app.delete("/api/sponsors/<sponsor_id>")
@stream_api_auth_required
def api_delete_sponsor(org: Organization, sponsor_id: str):
    s = Sponsor.query.filter_by(id=sponsor_id, organization_id=org.id).first()
    if not s:
        return jsonify({"error": "unknown sponsor"}), 404
    db.session.delete(s)
    db.session.commit()
    return jsonify({"ok": True, "deleted": sponsor_id})
```

**Persist the active-sponsor selection.** IMPORTANT correction from initial research: overlay prefs (`overlay_size`/`theme`/`overlay_density`) are **NOT** stored in a DB column — they live in a per-match JSON state file on disk (`STATE_DIR / cricket_state_{slug}.json`), managed by `blank_state()` / `match_context()` / `save_state()`. Add the new keys there, not to any SQLAlchemy model.

Current `blank_state()` (`server/app.py`, lines 261-303 — relevant tail shown):
```python
def blank_state():
    return {
        ...
        "theme": "classic",
        "overlay_density": "expanded",
        "overlay_scale": 1.0,
        "overlay_size": 3,
        "overlay_box_color": "#101f45",
        ...
    }
```
New — add two keys:
```python
        "sponsor_enabled": False,
        "active_sponsor_id": None,
```

Current `_overlay_prefs_json`/`api_set_overlay` (lines 3724-3772):
```python
def _overlay_prefs_json(slug: str) -> dict:
    with match_context(slug):
        merge_missing_state_keys(state)
        size = normalize_overlay_size(state.get("overlay_size"), state.get("overlay_scale"))
        theme = _sanitize_overlay_theme(state.get("theme"))
        density = str(state.get("overlay_density") or "expanded").strip().lower()
        if density not in {"compact", "expanded"}:
            density = "expanded"
        return {
            "ok": True,
            "overlay_size": size,
            "overlay_scale": float(state.get("overlay_scale") or 1.0),
            "theme": theme,
            "overlay_density": density,
        }


@app.get("/api/match/<match_slug>/overlay")
@stream_api_auth_required
def api_get_overlay(org: Organization, match_slug: str):
    slug = sanitize_match_id(match_slug)
    if not relay_match_for_org(org, slug):
        return jsonify({"error": "unknown stream"}), 404
    return jsonify(_overlay_prefs_json(slug))


@app.post("/api/match/<match_slug>/overlay")
@stream_api_auth_required
def api_set_overlay(org: Organization, match_slug: str):
    slug = sanitize_match_id(match_slug)
    if not relay_match_for_org(org, slug):
        return jsonify({"error": "unknown stream"}), 404
    data = request.get_json(silent=True) or {}
    with match_context(slug):
        merge_missing_state_keys(state)
        if "overlay_size" in data:
            state["overlay_size"] = normalize_overlay_size(data.get("overlay_size"), data.get("overlay_scale"))
            state["overlay_scale"] = round(0.8 + (state["overlay_size"] - 1) * 0.25, 2)
        if "theme" in data:
            state["theme"] = _sanitize_overlay_theme(data.get("theme"))
        elif "overlay_theme" in data:
            state["theme"] = _sanitize_overlay_theme(data.get("overlay_theme"))
        if "overlay_density" in data:
            density = str(data.get("overlay_density") or "").strip().lower()
            state["overlay_density"] = density if density in {"compact", "expanded"} else "expanded"
        save_state()
    return jsonify(_overlay_prefs_json(slug))
```

New — add sponsor fields to both:
```python
def _overlay_prefs_json(slug: str) -> dict:
    with match_context(slug):
        merge_missing_state_keys(state)
        size = normalize_overlay_size(state.get("overlay_size"), state.get("overlay_scale"))
        theme = _sanitize_overlay_theme(state.get("theme"))
        density = str(state.get("overlay_density") or "expanded").strip().lower()
        if density not in {"compact", "expanded"}:
            density = "expanded"
        return {
            "ok": True,
            "overlay_size": size,
            "overlay_scale": float(state.get("overlay_scale") or 1.0),
            "theme": theme,
            "overlay_density": density,
            "sponsor_enabled": bool(state.get("sponsor_enabled", False)),
            "active_sponsor_id": state.get("active_sponsor_id"),
        }


@app.post("/api/match/<match_slug>/overlay")
@stream_api_auth_required
def api_set_overlay(org: Organization, match_slug: str):
    slug = sanitize_match_id(match_slug)
    if not relay_match_for_org(org, slug):
        return jsonify({"error": "unknown stream"}), 404
    data = request.get_json(silent=True) or {}
    with match_context(slug):
        merge_missing_state_keys(state)
        if "overlay_size" in data:
            state["overlay_size"] = normalize_overlay_size(data.get("overlay_size"), data.get("overlay_scale"))
            state["overlay_scale"] = round(0.8 + (state["overlay_size"] - 1) * 0.25, 2)
        if "theme" in data:
            state["theme"] = _sanitize_overlay_theme(data.get("theme"))
        elif "overlay_theme" in data:
            state["theme"] = _sanitize_overlay_theme(data.get("overlay_theme"))
        if "overlay_density" in data:
            density = str(data.get("overlay_density") or "").strip().lower()
            state["overlay_density"] = density if density in {"compact", "expanded"} else "expanded"
        if "sponsor_enabled" in data:
            state["sponsor_enabled"] = bool(data.get("sponsor_enabled"))
        if "active_sponsor_id" in data:
            sid = data.get("active_sponsor_id")
            state["active_sponsor_id"] = str(sid).strip() if sid else None
        save_state()
    return jsonify(_overlay_prefs_json(slug))
```

**Extend `relay_overlay_data`** (`server/app.py`, lines 2382-2425, quoted in full above) — add a resolved `sponsor` key. Insert right before `resp = jsonify(payload)`:
```python
    sponsor_enabled = bool(data.get("sponsor_enabled", False))
    active_sponsor_id = data.get("active_sponsor_id")
    sponsor_payload = None
    if sponsor_enabled and active_sponsor_id:
        now = datetime.now(timezone.utc)
        s = Sponsor.query.filter_by(id=active_sponsor_id, is_active=True).first()
        if s and (s.active_from is None or s.active_from <= now) and (s.active_to is None or s.active_to >= now):
            sponsor_payload = {"logo_url": s.logo_url, "link_url": s.link_url, "name": s.name}
    payload["sponsor"] = sponsor_payload

    resp = jsonify(payload)
```
(`data` here is the `_live_snapshot(slug)` dict already in scope in this function — it needs `sponsor_enabled`/`active_sponsor_id` to be included in whatever persists the match JSON state, which they now are per the `blank_state()` change above.)

**`cricket_overlay.html`** (repo root) — add a small `<img id="sponsor-logo">` element positioned in a free corner (mirror wherever the existing watermark/branding element sits — grep this file for `watermark` or `#brand` to find the exact convention), and in the `render(data)` JS function add:
```javascript
const sponsorEl = document.getElementById('sponsor-logo');
if (data.sponsor && data.sponsor.logo_url) {
    sponsorEl.src = data.sponsor.logo_url;
    sponsorEl.style.display = 'block';
} else {
    sponsorEl.style.display = 'none';
}
```

### 4.2 Mobile compositing

**Decision**: 3rd copy-pasted filter pair (not a generalized layer list) — see reasoning in the earlier research; no near-term 4th overlay type planned.

**Android** — `StreamCameraEngine.kt`. Current watermark triplet (lines 1198-1273, quoted above in full: `buildWatermarkBitmap`, `ensureWatermarkFilter`, `clearWatermarkFilter`, `applyWatermarkSprite`) is the exact template. New fields (near `watermarkFilter`):
```kotlin
    private var sponsorFilter: ImageObjectFilterRender? = null
    private var appliedSponsorLogoUrl: String? = null
    private var overlaySponsorEnabled: Boolean = false
    private var overlaySponsorLogoUrl: String = ""
```

New functions (mirror the watermark block exactly, swapping text-bitmap-building for a network image fetch — **no Coil/Glide dependency exists in this module**, confirmed by grepping `libs.versions.toml`; use a plain `HttpURLConnection`/`BitmapFactory` fetch on a background thread):
```kotlin
    private fun fetchSponsorBitmap(url: String): Bitmap? = try {
        val conn = java.net.URL(url).openConnection() as java.net.HttpURLConnection
        conn.connectTimeout = 5000
        conn.readTimeout = 5000
        conn.inputStream.use { android.graphics.BitmapFactory.decodeStream(it) }
    } catch (e: Exception) {
        CricrelayLog.w("Sponsor logo fetch failed: ${e.message}")
        null
    }

    private fun ensureSponsorFilter() {
        val cam = camera ?: return
        if (!overlaySponsorEnabled || overlaySponsorLogoUrl.isBlank()) {
            clearSponsorFilter()
            return
        }
        if (!cam.isOnPreview && !cam.isStreaming) return
        val filter = sponsorFilter ?: try {
            ImageObjectFilterRender().also {
                cam.glInterface.addFilter(it)
                sponsorFilter = it
            }
        } catch (e: Exception) {
            CricrelayLog.w("Sponsor filter failed: ${e.message}")
            return
        }
        if (appliedSponsorLogoUrl != overlaySponsorLogoUrl) {
            // Network fetch — run off the GL/main thread in the real implementation
            // (e.g. via a background executor + post the result back before calling setImage).
            fetchSponsorBitmap(overlaySponsorLogoUrl)?.let { bmp ->
                try {
                    filter.setImage(bmp)
                    appliedSponsorLogoUrl = overlaySponsorLogoUrl
                } catch (e: Exception) {
                    CricrelayLog.w("Sponsor image failed: ${e.message}")
                }
            }
        }
        applySponsorSprite(filter)
    }

    private fun clearSponsorFilter() {
        val cam = camera ?: return
        sponsorFilter?.let { filter ->
            try {
                cam.glInterface.removeFilter(filter)
            } catch (_: Exception) {
            }
        }
        sponsorFilter = null
        appliedSponsorLogoUrl = null
    }

    private fun applySponsorSprite(filter: ImageObjectFilterRender) {
        val canvasW = encodedCanvasWidth()
        val canvasH = encodedCanvasHeight()
        filter.setDefaultScale(canvasW, canvasH)
        // Reuse WatermarkSpriteLayout — the corner-anchored positioning math is identical.
        // Anchor sponsor bottom-right (watermark is top-right) so they never overlap.
        val sprite = WatermarkSpriteLayout.compute(
            WatermarkSpriteLayout.Params(
                canvasW = canvasW,
                canvasH = canvasH,
                bitmapWidth = filter.let { 0 } /* TODO: use the fetched bitmap's actual width */,
                bitmapHeight = WATERMARK_BMP_HEIGHT,
                heightPct = WATERMARK_HEIGHT_PCT,
                rightEdgePct = WATERMARK_RIGHT_EDGE_PCT,
                topPct = 1.0f - WATERMARK_TOP_PCT,  // mirror to the bottom edge
                maxWidthPct = WATERMARK_MAX_WIDTH_PCT,
            ),
        )
        filter.setScale(sprite.scaleX, sprite.scaleY)
        filter.setPosition(sprite.positionX, sprite.positionY)
    }
```

New function, called from `updateOverlay(...)` (the same place `overlayLayout` is set — see call site context around lines 611-616 in section 1j above) to feed sponsor state in:
```kotlin
    fun setSponsorLayer(enabled: Boolean, logoUrl: String) {
        overlaySponsorEnabled = enabled
        overlaySponsorLogoUrl = logoUrl
        ensureSponsorFilter()
    }
```

Add `ensureSponsorFilter()` calls at the **same 6 call sites** as `ensureWatermarkFilter()` (lines 233, 585, 614, 781, 856, 1476-1478 — quoted in full in section 1j above), directly next to each `ensureWatermarkFilter()` call.

**Modify `dropStaleGlFilterRefs()`** (lines 1155-1162, quoted above):
```kotlin
    private fun dropStaleGlFilterRefs() {
        watermarkFilter = null
        imageFilter = null
        sponsorFilter = null            // NEW
        appliedWatermarkText = null
        appliedSponsorLogoUrl = null    // NEW
    }
```

**iOS** — `StreamCameraEngine.swift`. Current watermark triplet (lines 574-625, quoted in full above: `ensureWatermarkObject`, `buildWatermarkImage`) is the template. New:
```swift
private var sponsorObject: ImageScreenObject?
private var appliedSponsorLogoUrl: String?

private func ensureSponsorObject() async {
    let enabled = overlayLayout.sponsorEnabled
    let url = overlayLayout.sponsorLogoUrl
    if !enabled || url.isEmpty {
        await Task { @ScreenActor in
            if let obj = sponsorObject {
                try? await mixer.screen.removeChild(obj)
                sponsorObject = nil
                appliedSponsorLogoUrl = nil
            }
        }.value
        return
    }
    guard appliedSponsorLogoUrl != url || sponsorObject == nil else { return }
    guard let remoteURL = URL(string: url),
          let data = try? Data(contentsOf: remoteURL),
          let cg = UIImage(data: data)?.cgImage else { return }
    await Task { @ScreenActor in
        if sponsorObject == nil {
            let obj = ImageScreenObject()
            obj.horizontalAlignment = .right
            obj.verticalAlignment = .bottom   // mirror watermark's top-right by anchoring bottom-right
            obj.layoutMargin = UIEdgeInsets(top: 0, left: 0, bottom: 18, right: 18)
            sponsorObject = obj
            try? await mixer.screen.addChild(obj)
        }
        sponsorObject?.cgImage = cg
        appliedSponsorLogoUrl = url
    }.value
}
```
(`Data(contentsOf:)` is a synchronous network call — fine for a spike, but the real implementation should use `URLSession.shared.data(from:)` async instead to avoid blocking. Flag this in code review.)

**`OverlayLayout` struct** (lines 11-24, quoted in full above) — add two fields:
```swift
struct OverlayLayout {
    var heightFraction: Float = 0.16
    var widthFraction: Float = 1.0
    var anchorX: Float = 0.5
    var anchorY: Float = 0.85
    var bottomMarginFraction: Float = 0.02
    var horizontalInsetFraction: Float = 0.0
    var fontScale: Float = 1.0
    var bgColor: String = ""
    var textColor: String = ""
    var opacity: Float = 1.0
    var watermarkEnabled: Bool = true
    var watermarkText: String = "Visit cricrelay.co.uk"
    var sponsorEnabled: Bool = false      // NEW
    var sponsorLogoUrl: String = ""       // NEW
}
```
Call `await ensureSponsorObject()` from the same place(s) `ensureWatermarkObject()` is called (search this file for all its call sites, mirroring the Android call-site list).

### 4.3 Shared model

**`cricrelay-mobile/shared/src/commonMain/kotlin/uk/co/cricrelay/shared/model/Models.kt`** — current `OverlayLayoutPrefs` (lines 219-315, quoted in full above). Add two fields plus `fromJson`/`toJson` handling, following the exact existing `watermarkEnabled`/`watermarkText` pattern:

```kotlin
@Serializable
data class OverlayLayoutPrefs(
    // ...unchanged fields...
    @SerialName("watermark_enabled") val watermarkEnabled: Boolean = true,
    @SerialName("watermark_text") val watermarkText: String = WATERMARK_DEFAULT_TEXT,
    @SerialName("sponsor_enabled") val sponsorEnabled: Boolean = false,          // NEW
    @SerialName("active_sponsor_id") val activeSponsorId: String? = null,       // NEW
) {
    // ...unchanged...
    companion object {
        // ...unchanged constants...

        fun fromJson(json: JsonObject): OverlayLayoutPrefs = OverlayLayoutPrefs(
            // ...unchanged fields...
            watermarkEnabled = json.bool("watermark_enabled") != false,
            watermarkText = json.string("watermark_text")?.takeIf { it.isNotBlank() } ?: WATERMARK_DEFAULT_TEXT,
            sponsorEnabled = json.bool("sponsor_enabled") == true,                          // NEW
            activeSponsorId = json.string("active_sponsor_id")?.takeIf { it.isNotBlank() }, // NEW
        )
    }

    fun toJson(): JsonObject = buildJsonObject {
        // ...unchanged puts...
        put("watermark_enabled", watermarkEnabled)
        put("watermark_text", watermarkText)
        put("sponsor_enabled", sponsorEnabled)              // NEW
        activeSponsorId?.let { put("active_sponsor_id", it) }  // NEW
    }
}
```

### 4.4 UI

`OverlaySheet` in `StudioSheets.kt` — current watermark block (lines 329-361, quoted in full above). Add directly below it (before the `Spacer(Modifier.height(AppSpacing.md))` / `PrimaryButton` save block):
```kotlin
    Spacer(Modifier.height(AppSpacing.md))
    Column(modifier = Modifier.padding(horizontal = AppSpacing.lg)) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text("Sponsor logo", style = AppTypography.titleSmall)
                Text("Shown bottom-right on the broadcast", style = AppTypography.bodySmall)
            }
            Switch(
                checked = sponsorEnabled,
                onCheckedChange = { sponsorEnabled = it },
                colors = SwitchDefaults.colors(
                    checkedThumbColor = AppColors.OnPrimary,
                    checkedTrackColor = AppColors.Primary,
                    uncheckedThumbColor = AppColors.OnBackgroundDim,
                    uncheckedTrackColor = AppColors.SurfaceElevated,
                ),
            )
        }
        // If sponsors.size > 1, render a chip row here to pick activeSponsorId — same visual
        // pattern as the existing 6-theme carousel elsewhere in this file. sponsors comes from
        // a new StreamRepository.listSponsors(orgToken) call, following the exact pattern of
        // the existing streamRepository.getOverlayPrefs(slug) call in StudioViewModel.kt.
    }
```
Add `var sponsorEnabled by remember { mutableStateOf(prefs.sponsorEnabled) }` next to the existing `var watermarkEnabled by remember { ... }` declaration in this composable, and include `sponsorEnabled = sponsorEnabled` in the `PrimaryButton`'s `onClick` `prefs.copy(...)` call (same place `watermarkEnabled = watermarkEnabled` is already included, quoted in section 3a above).

iOS `OverlaySheet` in `StudioSheets.swift` — mirror the watermark `Toggle` block (lines 249-261, quoted in full above) with a `Toggle("Sponsor logo", isOn: $draft.sponsorEnabled)` immediately below it.

New API methods, following the exact pattern of the existing `getOverlayPrefs`/`updateBroadcastStatus` calls in `CricRelayApiClient` (Kotlin, shared module) and `CricRelayAPI.swift` (iOS) — add:
- `listSponsors(): List<Sponsor>` → `GET /api/sponsors`
- (Sponsor selection itself piggybacks on the existing `setOverlayPrefs`/`api_set_overlay` call now that `sponsor_enabled`/`active_sponsor_id` are part of that payload — no separate endpoint needed for selection.)

---

## 5. Remote control, phase 1

**Transport**: Redis is already deployed — self-hosted Postgres+Redis Docker containers on a dedicated instance (`infra/datastores.tf`, `infra/templates/datastore_user_data.sh.tftpl`), reachable from the app security group on port 6379, and `redis==5.2.1` is already in `requirements.txt` (confirmed zero current `import redis` usage anywhere in `server/` — this is the first real use).

### 5.1 Server — `server/stream_api.py`

**Current auth patterns to mirror** (quoted in full):
```python
def stream_api_auth_required(view: Callable):
    @wraps(view)
    def wrapped(*args, **kwargs):
        org = bearer_org_from_request()
        if not org:
            return jsonify({"error": "unauthorized"}), 401
        return view(org, *args, **kwargs)
    return wrapped


def bearer_org_from_request() -> Organization | None:
    auth = (request.headers.get("Authorization") or "").strip()
    if auth.lower().startswith("bearer "):
        token = auth[7:].strip()
        return org_from_stream_token(token)
    return None


def _youtube_oauth_serializer():
    from flask import current_app
    return URLSafeTimedSerializer(current_app.config["SECRET_KEY"], salt="cricrelay-youtube-oauth")


def issue_youtube_oauth_state(org_id: str) -> str:
    return _youtube_oauth_serializer().dumps({"oid": org_id})


def org_id_from_youtube_oauth_state(state: str) -> str | None:
    try:
        payload = _youtube_oauth_serializer().loads(state, max_age=900)
    except (SignatureExpired, BadSignature):
        return None
    oid = str((payload or {}).get("oid") or "").strip()
    return oid or None
```

**New Redis client** — add near the top of `stream_api.py` (after imports):
```python
import redis as _redis_lib

_redis_client = None


def redis_client():
    global _redis_client
    if _redis_client is None:
        _redis_client = _redis_lib.from_url(os.getenv("REDIS_URL", "redis://localhost:6379/0"))
    return _redis_client
```
(Set `REDIS_URL` in the deployment env to the private IP documented in `infra/datastores.tf`, e.g. `redis://<internal-ip>:6379/0` — confirm the exact IP/hostname convention already used for `DATABASE_URL` in this project's env config and mirror it.)

**New serializers + token functions** (mirror `_youtube_oauth_serializer` exactly):
```python
def _remote_pair_serializer():
    from flask import current_app
    return URLSafeTimedSerializer(current_app.config["SECRET_KEY"], salt="cricrelay-remote-pair")


def _companion_session_serializer():
    from flask import current_app
    return URLSafeTimedSerializer(current_app.config["SECRET_KEY"], salt="cricrelay-companion-session")


REMOTE_PAIR_TOKEN_MAX_AGE = 300          # 5 minutes
COMPANION_TOKEN_MAX_AGE = 6 * 60 * 60    # 6 hours — matches a typical match duration

REMOTE_CONTROL_COMMANDS = {
    "start_broadcast",
    "stop_broadcast",
    "mute_mic",
    "toggle_focus_lock",
}


def issue_remote_pair_token(org: Organization, match_slug: str) -> str:
    return _remote_pair_serializer().dumps({"oid": org.id, "slug": match_slug})


def redeem_remote_pair_token(pair_token: str) -> dict | None:
    """Validates a scanned pairing token and issues a scoped companion session token.
    Overwrites any prior companion pairing for this match (one-active-companion policy)."""
    try:
        payload = _remote_pair_serializer().loads(pair_token, max_age=REMOTE_PAIR_TOKEN_MAX_AGE)
    except (SignatureExpired, BadSignature):
        return None
    org_id = str(payload.get("oid") or "").strip()
    slug = str(payload.get("slug") or "").strip()
    if not org_id or not slug:
        return None
    import uuid as _uuid
    jti = _uuid.uuid4().hex
    companion_token = _companion_session_serializer().dumps({"oid": org_id, "slug": slug, "jti": jti})
    redis_client().setex(f"cricrelay:companion:{slug}", COMPANION_TOKEN_MAX_AGE, jti)
    return {"companion_token": companion_token, "slug": slug}


def companion_token_required(view: Callable):
    @wraps(view)
    def wrapped(*args, **kwargs):
        auth = (request.headers.get("Authorization") or "").strip()
        if not auth.lower().startswith("bearer "):
            return jsonify({"error": "unauthorized"}), 401
        token = auth[7:].strip()
        try:
            payload = _companion_session_serializer().loads(token, max_age=COMPANION_TOKEN_MAX_AGE)
        except (SignatureExpired, BadSignature):
            return jsonify({"error": "unauthorized"}), 401
        slug = str(payload.get("slug") or "").strip()
        jti = str(payload.get("jti") or "").strip()
        current = redis_client().get(f"cricrelay:companion:{slug}")
        if not current or current.decode() != jti:
            return jsonify({"error": "pairing_superseded"}), 410
        return view(slug, str(payload.get("oid") or ""), *args, **kwargs)
    return wrapped
```

### 5.2 Server — `server/app.py` routes

Add near the other `/api/match/<match_slug>/...` routes:
```python
@app.post("/api/match/<match_slug>/pair")
@stream_api_auth_required
def api_pair_remote(org: Organization, match_slug: str):
    from .stream_api import issue_remote_pair_token, REMOTE_PAIR_TOKEN_MAX_AGE
    slug = sanitize_match_id(match_slug)
    if not relay_match_for_org(org, slug):
        return jsonify({"error": "unknown stream"}), 404
    token = issue_remote_pair_token(org, slug)
    expires_at = (datetime.now(timezone.utc) + timedelta(seconds=REMOTE_PAIR_TOKEN_MAX_AGE)).isoformat()
    return jsonify({"ok": True, "pair_token": token, "expires_at": expires_at})


@app.post("/stream/<match_slug>/pair/redeem")
def public_pair_redeem(match_slug: str):
    from .stream_api import redeem_remote_pair_token
    data = request.get_json(silent=True) or {}
    pair_token = str(data.get("pair_token") or "").strip()
    if not pair_token:
        return jsonify({"error": "pair_token required"}), 400
    result = redeem_remote_pair_token(pair_token)
    if not result or result["slug"] != sanitize_match_id(match_slug):
        return jsonify({"error": "invalid or expired pairing code"}), 400
    return jsonify({"ok": True, "companion_token": result["companion_token"], "match_slug": result["slug"]})


@app.post("/api/match/<match_slug>/remote/command")
@companion_token_required
def api_remote_command(companion_slug: str, companion_org_id: str, match_slug: str):
    from .stream_api import REMOTE_CONTROL_COMMANDS, redis_client
    slug = sanitize_match_id(match_slug)
    if slug != companion_slug:
        return jsonify({"error": "slug mismatch"}), 400
    data = request.get_json(silent=True) or {}
    msg_type = str(data.get("type") or "").strip()
    command = str(data.get("command") or "").strip()
    if msg_type != "control" or command not in REMOTE_CONTROL_COMMANDS:
        return jsonify({"error": "invalid command"}), 400
    import time as _t
    envelope = json.dumps({"type": msg_type, "command": command, "ts": _t.time()})
    key = f"cricrelay:remote:cmds:{slug}"
    r = redis_client()
    r.rpush(key, envelope)
    r.expire(key, 600)
    return jsonify({"ok": True})


@app.get("/api/match/<match_slug>/remote/commands")
@stream_api_auth_required
def api_remote_commands_poll(org: Organization, match_slug: str):
    from .stream_api import redis_client
    slug = sanitize_match_id(match_slug)
    if not relay_match_for_org(org, slug):
        return jsonify({"error": "unknown stream"}), 404
    key = f"cricrelay:remote:cmds:{slug}"
    r = redis_client()
    pipe = r.pipeline()
    pipe.lrange(key, 0, -1)
    pipe.delete(key)
    raw_list, _ = pipe.execute()
    commands = [json.loads(item) for item in raw_list]
    return jsonify({"ok": True, "commands": commands})
```

Note: `@companion_token_required` as written above assigns `wrapped(*args, **kwargs)` → `view(slug, org_id, *args, **kwargs)`, so `api_remote_command`'s signature `(companion_slug, companion_org_id, match_slug)` receives Flask's URL-captured `match_slug` as a kwarg after the decorator's two positional args — verify the exact argument order matches how Flask passes route params through this decorator style (compare directly against how `stream_api_auth_required` passes `org` through to `api_patch_stream(org, match_slug)` — same shape, one extra field).

### 5.3 QR payload

`cricrelay://pair?slug=<match_slug>&token=<pair_token>&base=<api_base_url>`

Example: `cricrelay://pair?slug=oakwood-cc-vs-elm-park&token=eyJhbGciOi...&base=https://app.cricrelay.co.uk`

### 5.4 Mobile — QR generation & scanning

No existing QR dependency in either app (confirmed — zero `qrcode`/`zxing` matches repo-wide). Add:
- **Android**: `com.google.zxing:core:3.5.3` (generation only, no Android-specific wrapper needed — encode manually into a `Bitmap` via `QRCodeWriter().encode(payload, BarcodeFormat.QR_CODE, size, size)`). For scanning on the companion device, use ML Kit Barcode Scanning (`com.google.mlkit:barcode-scanning:17.2.0`) via CameraX, OR reuse `zxing` + `zxing-android-embedded:4.3.0` for a ready-made scanner Activity — pick whichever the team already has a CameraX/Camera2 pattern for; this repo uses Camera2 directly (RootEncoder), so ML Kit's `InputImage`-based API is likely the smaller lift.
- **iOS**: no dependency needed — generate via `CIFilter.qrCodeGenerator(message:correctionLevel:)`, scan via `AVCaptureMetadataOutput` with `metadataObjectTypes = [.qr]` (both built into `AVFoundation`, already a dependency).

### 5.5 Mobile UI

**Broadcasting phone**: new "Pair Remote" item in `StudioMenuSheet` (`StudioSheets.kt`, lines 551-566, quoted in full above):
```kotlin
@Composable
fun StudioMenuSheet(
    onRestartPreview: () -> Unit,
    onPairRemote: () -> Unit,   // NEW param
    onDismiss: () -> Unit,
) {
    SheetHeader(title = "Broadcast menu")
    Spacer(Modifier.height(AppSpacing.sm))
    SecondaryButton(
        text = "Restart camera preview",
        onClick = { onRestartPreview(); onDismiss() },
        modifier = Modifier.padding(horizontal = AppSpacing.lg),
    )
    Spacer(Modifier.height(AppSpacing.sm))
    SecondaryButton(
        text = "Pair Remote",
        onClick = { onPairRemote(); onDismiss() },
        modifier = Modifier.padding(horizontal = AppSpacing.lg),
    )
}
```
`onPairRemote` navigates to a new full-screen QR display (calls `POST /api/match/<slug>/pair`, renders the returned `pair_token` as a QR via zxing `QRCodeWriter`, and starts polling `GET /api/match/<slug>/remote/commands` every 1-2s while that screen or Studio is open — wire the poll result's `commands[]` array to `StudioViewModel` action dispatch: `start_broadcast`→existing go-live function, `stop_broadcast`→existing stop function, `mute_mic`→`onToggleMicMuted()`, `toggle_focus_lock`→`onToggleFocusLock()`).

iOS: mirror in `StudioMenuSheet` (`StudioSheets.swift`, lines 467-526, quoted in full above) — add a third `Button`/`HStack` row identical in shape to the existing "Restart camera preview" row, labeled "Pair Remote".

**Companion phone**: new screen outside the Studio module, reachable from Home. Android: new `RemoteControlRoute` in `CricRelayNavHost.kt` (new `feature/remotecontrol` module or folded into `feature/home`), screen = QR scanner (ML Kit) → on successful scan, POST to `/stream/<slug>/pair/redeem`, store the returned `companion_token`, show 4 buttons (Start/Stop/Mute Mic/Toggle Focus Lock) that each POST to `/api/match/<slug>/remote/command` with `{"type": "control", "command": "..."}` using the companion token as bearer auth. iOS: mirror under a new `Features/RemoteControl/` folder.

---

## 6. UI/UX integration summary

| New control | Placement | Why |
|---|---|---|
| Overheat/thermal banner | `StudioStatusMessages` (Android `BroadcastCameraUi.kt`), new `thermalBanner` (iOS `StudioView.swift`) | Same slot as existing warning/error text; non-modal |
| Mic mute | 4th `QuickToggles` pill | One-tap parity with Focus/Stabilize/Screen-on |
| Sponsor toggle (+ picker) | New block in `OverlaySheet`, below watermark toggle | Same "burned-in branding" grouping |
| Pair Remote | New item in `StudioMenuSheet` | `StudioTopBar` has no spare room; Menu sheet is the utility-actions home |

No changes to `ToolButtons` (Destination/Style/Score).

## Engineering spikes to resolve during implementation

- RootEncoder 2.4.8: live bitrate/resolution change API for `stepDownQuality()` — if absent, "Lower quality" must stop+restart.
- HaishinKit `MediaMixer`: per-track audio mute/gain API for iOS `setMicMuted` — if absent, use the detach/reattach fallback given above.
- `REDIS_URL` env var: confirm the exact hostname/IP convention already used for `DATABASE_URL` in this project's deploy config, and set it identically for Redis.
- Android sponsor logo fetch: the `fetchSponsorBitmap` sketch above is synchronous — wire it through a background executor (this file already uses `mainHandler`/`Handler` patterns elsewhere; add a small `Executors.newSingleThreadExecutor()` for network fetches, post results back via `mainHandler.post { ... }`).

## Verification

- **Android**: run Studio on a physical device; mock `isThermalStressed()`/force a listener callback to confirm the banner appears/disappears without blocking interaction; toggle mic mute during a live RTMP test stream and confirm the encoded audio actually mutes; enable sponsor toggle and confirm the logo composites bottom-right without overlapping the top-right watermark or the scoreboard; pair a companion device via QR and issue all 4 commands, confirming ~1-2s latency.
- **iOS**: same checklist on a physical iPhone; additionally confirm `.cinematicExtended` doesn't visually clip the WYSIWYG overlay position at the narrower field of view.
- **Server**: exercise `/api/sponsors` CRUD and the pair/redeem/command routes with `curl` using a real org bearer token; confirm the Redis `cricrelay:remote:cmds:{slug}` list drains correctly on poll and `cricrelay:companion:{slug}` correctly invalidates a prior companion on re-pair.
- Cross-platform parity check: same 4 quick-toggle pills, same Overlay sheet sponsor block, same Menu sheet Pair Remote entry on both Android and iOS.
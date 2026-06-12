import 'dart:async';
import 'dart:io';

import 'package:flutter/foundation.dart';

import '../models/broadcast_phase.dart';
import '../models/overlay_layout_prefs.dart';
import '../models/stream_destination.dart';
import '../models/stream_quality.dart';
import '../services/api.dart';
import '../services/overlay_preview_platform.dart';
import '../services/overlay_layout_store.dart';
import '../services/rtmp_credentials_store.dart';
import '../services/rtmp_platform.dart';
import '../utils/device_profile.dart';
import '../utils/native_encoder_profile.dart';

/// Central broadcast state — narrow [ValueNotifier]s avoid full-screen [setState].
class BroadcastController extends ChangeNotifier {
  BroadcastController({
    required this.api,
    required this.match,
  })  : _rtmpStore = RtmpCredentialsStore(match.slug),
        _overlayStore = OverlayLayoutStore(api, match.slug);

  final CricRelayApi api;
  final StreamMatch match;
  final RtmpCredentialsStore _rtmpStore;
  final OverlayLayoutStore _overlayStore;

  // Targeted listeners (HUD, scoreboard strip, dock status line).
  final matchDay = ValueNotifier<MatchDayStatus?>(null);
  final overlayPreview = ValueNotifier<Uint8List?>(null);
  final zoomUi = ValueNotifier<double>(1.0);
  final statusLine = ValueNotifier<String?>(null);

  bool live = false;
  bool streamPaused = false;
  bool busy = false;
  bool nativeCamera = false;
  bool nativeCameraReady = false;
  bool avPermissionsGranted = false;
  String? initError;
  StreamDestination destination = StreamDestination.custom;
  bool liveManagedByApi = false;
  String? livePlatform;
  String? customRtmpUrl;
  String? customStreamKey;
  String? customWatchUrl;
  double zoom = 1.0;
  double minZoom = 1.0;
  double maxZoom = 1.0;
  StreamQualityProfile quality = StreamQualityProfile.high;
  StreamQualityProfile nativeProfile = StreamQualityProfile.high;
  OverlayLayoutPrefs overlayPrefs = const OverlayLayoutPrefs();
  bool overlayLocked = false;
  bool dockVisible = true;
  String? watchUrl;
  ScoringConfig? scoring;
  DateTime? liveStartedAt;
  String? status;
  String liveStatusMessage = '';
  int encoderWidth = 1280;
  int encoderHeight = 720;
  int displayRotation = 0;
  int preparedEncoderRotation = -1;
  /// GL preview rotation applied via [rotatePreviewOnly] — suppresses false orientation warnings.
  int glPreviewRotation = -1;
  bool focusLocked = false;
  DeviceProfile? deviceProfile;

  StreamSubscription<RtmpStreamEvent>? _rtmpStatusSub;
  StreamSubscription<OverlayPreviewFrame>? _overlayPreviewSub;
  Timer? _matchDayPoll;

  OverlayLayoutStore get overlayStore => _overlayStore;
  RtmpCredentialsStore get rtmpStore => _rtmpStore;

  /// Android renders the scoreboard preview from the same native bitmap used for
  /// the stream — this composites as a plain Flutter image over the transparent
  /// camera (a WebView platform view does not draw reliably over it). iOS keeps
  /// the WebView preview.
  bool get useNativeOverlayPreview => Platform.isAndroid;

  BroadcastPhase get phase {
    if (initError != null) return BroadcastPhase.cameraError;
    if (!avPermissionsGranted) return BroadcastPhase.permissionDenied;
    if (nativeCamera && !nativeCameraReady && initError == null) {
      return busy ? BroadcastPhase.connecting : BroadcastPhase.cameraLoading;
    }
    if (busy && !live) return BroadcastPhase.connecting;
    if (live && streamPaused) return BroadcastPhase.paused;
    if (live) return BroadcastPhase.live;
    if (nativeCameraReady || !nativeCamera) return BroadcastPhase.previewReady;
    return BroadcastPhase.initializing;
  }

  bool get orientationChangedSincePrepare {
    if (!nativeCamera || live) return false;
    final params = NativeEncoderProfile.paramsFromDisplayRotation(nativeProfile, displayRotation);
    if (glPreviewRotation == params.rotation) return false;
    return preparedEncoderRotation != params.rotation;
  }

  /// Full encoder re-prepare required before RTMP (GL-only rotation is not enough).
  bool get needsEncoderReprepareForGoLive {
    if (!nativeCamera || live) return false;
    final params = encoderParamsForContext();
    return preparedEncoderRotation != params.rotation ||
        encoderWidth != params.width ||
        encoderHeight != params.height;
  }

  ({int width, int height, int rotation}) encoderParamsForContext() {
    return NativeEncoderProfile.paramsFromDisplayRotation(nativeProfile, displayRotation);
  }

  void setStatus(String? value) {
    statusLine.value = value;
    status = value;
    notifyListeners();
  }

  /// Updates status without notifying full widget tree (HUD uses [statusLine]).
  void setStatusQuiet(String? value) {
    statusLine.value = value;
    status = value;
  }

  Future<void> refreshDisplayRotation() async {
    if (!Platform.isAndroid && !Platform.isIOS) return;
    displayRotation = await RtmpPlatform.getDisplayRotation();
  }

  Future<void> syncNativeOverlay() async {
    if (!nativeCamera) return;
    final url = _overlayStore.embedUrl(match.overlayEmbedUrl, overlayPrefs);
    await RtmpPlatform.updateOverlay(
      overlayUrl: url,
      overlayHeightFraction: overlayPrefs.heightFraction,
      overlayWidthFraction: overlayPrefs.widthFraction,
      overlayAnchorX: overlayPrefs.anchorX,
      overlayAnchorY: overlayPrefs.anchorY,
      overlayBottomMargin: overlayPrefs.bottomMargin,
      overlayHorizontalInset: overlayPrefs.horizontalInset,
    );
  }

  void startOverlayPreviewListener() {
    if (!useNativeOverlayPreview) return;
    _overlayPreviewSub?.cancel();
    _overlayPreviewSub = OverlayPreviewPlatform.frames.listen((frame) {
      if (frame.pngBytes.isEmpty) return;
      overlayPreview.value = frame.pngBytes;
    });
  }

  void stopOverlayPreviewListener() {
    _overlayPreviewSub?.cancel();
    _overlayPreviewSub = null;
    overlayPreview.value = null;
  }

  void listenRtmpStatus(void Function(RtmpStreamEvent) handler) {
    _rtmpStatusSub?.cancel();
    _rtmpStatusSub = RtmpPlatform.statusEvents.listen(handler);
  }

  void startMatchDayPoll(Future<void> Function() refresh) {
    _matchDayPoll?.cancel();
    _matchDayPoll = Timer.periodic(const Duration(seconds: 8), (_) => refresh());
  }

  Future<void> refreshMatchDayStatus() async {
    try {
      final day = await api.getMatchDayStatus(match.slug);
      matchDay.value = day;
    } catch (_) {}
  }

  Future<void> initZoomLevels({bool? hasFlutterCamera}) async {
    if (nativeCamera) {
      try {
        final range = await RtmpPlatform.getZoomRange();
        minZoom = range.min <= 0 ? 1.0 : range.min;
        maxZoom = range.max < minZoom ? minZoom : range.max;
        final current = range.current.clamp(minZoom, maxZoom);
        zoom = current;
        zoomUi.value = current;
      } catch (_) {
        minZoom = 1.0;
        maxZoom = 1.0;
        zoom = 1.0;
        zoomUi.value = 1.0;
      }
    }
    notifyListeners();
  }

  void scheduleZoomUiUpdate(double clamped) {
    zoomUi.value = clamped;
  }

  double get zoomDisplayFactor {
    if (minZoom <= 0) return 1.0;
    return zoomUi.value / minZoom;
  }

  /// Fast GL rotation before Go Live; full encoder re-prepare only when needed.
  Future<bool> applyCameraOrientationFast({
    required bool isPortraitLayout,
    required void Function() onLayoutChanged,
  }) async {
    if (!nativeCamera || live) return false;
    await refreshDisplayRotation();
    final params = encoderParamsForContext();
    if (preparedEncoderRotation == params.rotation && nativeCameraReady) {
      onLayoutChanged();
      return true;
    }

    if (nativeCameraReady) {
      final fast = await RtmpPlatform.rotatePreviewOnly(params.rotation);
      if (fast) {
        encoderWidth = params.width;
        encoderHeight = params.height;
        glPreviewRotation = params.rotation;
        // NOTE: GL-only rotation updates the on-screen preview but NOT the encoded
        // output. Do not mark preparedEncoderRotation here, otherwise Go Live would
        // skip the real prepareVideo and stream in the old orientation.
        onLayoutChanged();
        unawaited(syncNativeOverlay());
        notifyListeners();
        return true;
      }
    }

    nativeCameraReady = false;
    notifyListeners();
    encoderWidth = params.width;
    encoderHeight = params.height;
    onLayoutChanged();
    var ok = await RtmpPlatform.resetCameraOrientation(
      width: params.width,
      height: params.height,
      fps: nativeProfile.fps,
      bitrateBps: nativeProfile.bitrateBps,
      rotation: params.rotation,
    );
    if (!ok) {
      await RtmpPlatform.prepareCamera(
        width: params.width,
        height: params.height,
        fps: nativeProfile.fps,
        bitrateBps: nativeProfile.bitrateBps,
        rotation: params.rotation,
      );
    }
    preparedEncoderRotation = params.rotation;
    glPreviewRotation = params.rotation;
    for (var i = 0; i < 20; i++) {
      if (await RtmpPlatform.isCameraReady) {
        nativeCameraReady = true;
        await initZoomLevels();
        await syncNativeOverlay();
        if (useNativeOverlayPreview) startOverlayPreviewListener();
        notifyListeners();
        return true;
      }
      await Future<void>.delayed(const Duration(milliseconds: 100));
    }
    setStatus('Camera preview loading — wait a moment and try Go Live');
    notifyListeners();
    return false;
  }

  /// Full encoder re-prepare so the RTMP output matches the current phone
  /// orientation (landscape phone -> landscape stream). GL-only preview rotation
  /// does not change the encoded output, so a real prepareVideo is required.
  /// Run immediately before Go Live (camera not yet streaming, so this is safe).
  Future<bool> prepareEncoderForGoLive() async {
    if (!nativeCamera || live) return nativeCameraReady;
    await refreshDisplayRotation();
    final params = encoderParamsForContext();
    final alreadyCorrect = nativeCameraReady &&
        preparedEncoderRotation == params.rotation &&
        glPreviewRotation == params.rotation &&
        encoderWidth == params.width &&
        encoderHeight == params.height;
    if (alreadyCorrect) return true;

    encoderWidth = params.width;
    encoderHeight = params.height;
    nativeCameraReady = false;
    notifyListeners();

    var ok = await RtmpPlatform.resetCameraOrientation(
      width: params.width,
      height: params.height,
      fps: nativeProfile.fps,
      bitrateBps: nativeProfile.bitrateBps,
      rotation: params.rotation,
    );
    if (!ok) {
      ok = await RtmpPlatform.prepareCamera(
        width: params.width,
        height: params.height,
        fps: nativeProfile.fps,
        bitrateBps: nativeProfile.bitrateBps,
        rotation: params.rotation,
      );
    }
    preparedEncoderRotation = params.rotation;
    glPreviewRotation = params.rotation;

    for (var i = 0; i < 25; i++) {
      if (await RtmpPlatform.isCameraReady) {
        nativeCameraReady = true;
        await syncNativeOverlay();
        notifyListeners();
        return true;
      }
      await Future<void>.delayed(const Duration(milliseconds: 100));
    }
    notifyListeners();
    return false;
  }

  Future<void> ensureNativeCameraReady() async {
    if (!nativeCamera) return;
    if (Platform.isAndroid) {
      await RtmpPlatform.showNativePreview();
    }
    if (nativeCameraReady) {
      try {
        if (await RtmpPlatform.isCameraReady) return;
      } catch (_) {}
    }
    await refreshDisplayRotation();
    final params = encoderParamsForContext();
    encoderWidth = params.width;
    encoderHeight = params.height;
    await RtmpPlatform.setKeepScreenOnDuringStream(overlayPrefs.keepScreenOn);
    await RtmpPlatform.setVideoStabilization(overlayPrefs.videoStabilization);
    if (!await RtmpPlatform.isCameraReady) {
      await RtmpPlatform.prepareCamera(
        width: params.width,
        height: params.height,
        fps: nativeProfile.fps,
        bitrateBps: nativeProfile.bitrateBps,
        rotation: params.rotation,
      );
    }
    preparedEncoderRotation = params.rotation;
    glPreviewRotation = params.rotation;
    for (var attempt = 0; attempt < 20; attempt++) {
      if (await RtmpPlatform.isCameraReady) {
        nativeCameraReady = true;
        await initZoomLevels();
        await syncNativeOverlay();
        if (useNativeOverlayPreview) startOverlayPreviewListener();
        notifyListeners();
        return;
      }
      await Future<void>.delayed(const Duration(milliseconds: 100));
    }
    setStatus('Camera preview loading — wait a moment and try Go Live');
    notifyListeners();
  }

  void onGoLivePhase() {
    stopOverlayPreviewListener();
    notifyListeners();
  }

  void onStopPhase() {
    if (useNativeOverlayPreview) startOverlayPreviewListener();
    notifyListeners();
  }

  @override
  void dispose() {
    _rtmpStatusSub?.cancel();
    _overlayPreviewSub?.cancel();
    _matchDayPoll?.cancel();
    matchDay.dispose();
    overlayPreview.dispose();
    zoomUi.dispose();
    statusLine.dispose();
    super.dispose();
  }
}

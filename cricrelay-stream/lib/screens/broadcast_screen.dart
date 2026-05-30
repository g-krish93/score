import 'dart:async';
import 'dart:io';
import 'dart:ui';

import 'package:flutter/gestures.dart';
import 'package:flutter/material.dart';
import 'package:permission_handler/permission_handler.dart';
import 'package:shared_preferences/shared_preferences.dart';
import 'package:camera/camera.dart';
import 'package:share_plus/share_plus.dart';
import 'package:url_launcher/url_launcher.dart' show launchUrl, LaunchMode;
import 'package:wakelock_plus/wakelock_plus.dart';
import 'package:webview_flutter/webview_flutter.dart';
import 'package:webview_flutter_android/webview_flutter_android.dart';

import '../services/api.dart';
import '../services/app_analytics.dart';
import '../services/overlay_layout_store.dart';
import '../services/rtmp_credentials_store.dart';
import '../services/rtmp_platform.dart';
import '../utils/stream_error_messages.dart';
import '../utils/stream_orientation.dart';
import '../models/overlay_layout_prefs.dart';
import '../models/stream_destination.dart';
import '../models/stream_quality.dart';
import '../utils/device_profile.dart';
import '../utils/native_encoder_profile.dart';
import '../utils/rtmp_endpoint.dart';
import '../theme/app_theme.dart';
import '../widgets/broadcast_control_dock.dart';
import '../widgets/draggable_overlay_frame.dart';
import '../widgets/go_live_preflight_sheet.dart';
import '../widgets/overlay_layout_sheet.dart';
import '../widgets/match_day_wizard.dart';
import '../widgets/scoring_mode_sheet.dart';
import '../widgets/stream_management_sheet.dart';
import '../widgets/stream_settings_sheet.dart';
import '../widgets/camera_focus_reticle.dart';
import '../widgets/studio/broadcast_hud.dart';
import '../widgets/studio/studio_shell.dart';
import '../widgets/ui_kit.dart';

/// Live broadcast: in-app RTMP to YouTube + overlay + scoring menu.
class BroadcastScreen extends StatefulWidget {
  const BroadcastScreen({super.key, required this.api, required this.match});

  final CricRelayApi api;
  final StreamMatch match;

  @override
  State<BroadcastScreen> createState() => _BroadcastScreenState();
}

class _BroadcastScreenState extends State<BroadcastScreen> with WidgetsBindingObserver {
  CameraController? _camera;
  WebViewController? _web;
  bool _live = false;
  bool _streamPaused = false;
  bool _busy = false;
  String? _status;
  String? _watchUrl;
  ScoringConfig? _scoring;
  /// Native camera + GL overlay (not screen capture).
  bool _nativeCamera = false;
  bool _nativeCameraReady = false;
  bool _avPermissionsGranted = false;
  String? _initError;
  /// Default: volunteer stream key (no OAuth on phone).
  StreamDestination _destination = StreamDestination.custom;
  bool _liveManagedByApi = false;
  String? _livePlatform;
  String? _customRtmpUrl;
  String? _customStreamKey;
  String? _customWatchUrl;
  double _zoom = 1.0;
  double _minZoom = 1.0;
  double _maxZoom = 1.0;
  double _pinchBaseZoom = 1.0;
  StreamQualityProfile _quality = StreamQualityProfile.high;
  StreamQualityProfile _nativeProfile = StreamQualityProfile.high;
  late final OverlayLayoutStore _overlayStore;
  OverlayLayoutPrefs _overlayPrefs = const OverlayLayoutPrefs();
  bool _overlayLocked = false;
  bool _dockVisible = true;
  StreamSubscription<RtmpStreamEvent>? _rtmpStatusSub;
  late final RtmpCredentialsStore _rtmpStore;
  DateTime? _liveStartedAt;
  String _liveStatusMessage = '';
  int _encoderWidth = 1280;
  int _encoderHeight = 720;
  Timer? _orientationDebounce;
  Timer? _zoomUiDebounce;
  double _zoomUi = 1.0;
  bool _focusLocked = false;
  Offset? _focusReticle;
  Timer? _focusReticleHide;
  Offset? _focusTapDown;
  bool _focusPinchActive = false;
  int _lastNotifMinute = -1;
  DeviceProfile? _deviceProfile;
  MatchDayStatus? _matchDay;
  Timer? _matchDayPoll;
  int _displayRotation = 0;
  int _preparedEncoderRotation = -1;

  Future<void> _refreshDisplayRotation() async {
    if (!Platform.isAndroid && !Platform.isIOS) return;
    _displayRotation = await RtmpPlatform.getDisplayRotation();
  }

  ({int width, int height, int rotation}) _encoderParamsForContext() {
    return NativeEncoderProfile.paramsFromDisplayRotation(_nativeProfile, _displayRotation);
  }

  Future<void> _applyCameraOrientation() async {
    if (!_nativeCamera || _live || !mounted) return;
    await _refreshDisplayRotation();
    final params = _encoderParamsForContext();
    if (_preparedEncoderRotation == params.rotation && _nativeCameraReady) {
      _applyOverlayLayoutForOrientation();
      return;
    }
    _encoderWidth = params.width;
    _encoderHeight = params.height;
    _applyOverlayLayoutForOrientation();
    _clearFocusUi();
    var ok = await RtmpPlatform.updatePreviewRotation(params.rotation);
    if (!ok) {
      ok = await RtmpPlatform.resetCameraOrientation(
        width: params.width,
        height: params.height,
        fps: _nativeProfile.fps,
        bitrateBps: _nativeProfile.bitrateBps,
        rotation: params.rotation,
      );
      if (!ok) {
        await RtmpPlatform.prepareCamera(
          width: params.width,
          height: params.height,
          fps: _nativeProfile.fps,
          bitrateBps: _nativeProfile.bitrateBps,
          rotation: params.rotation,
        );
      }
    }
    if (!mounted) return;
    _preparedEncoderRotation = params.rotation;
    for (var i = 0; i < 40; i++) {
      if (await RtmpPlatform.isCameraReady) {
        if (mounted) setState(() => _nativeCameraReady = true);
        await _loadOverlayWebView();
        await _syncNativeOverlay();
        return;
      }
      await Future<void>.delayed(const Duration(milliseconds: 250));
    }
    if (mounted) {
      setState(() {
        _nativeCameraReady = false;
        _status = 'Camera preview loading — wait a moment and try Go Live';
      });
    }
  }

  bool get _orientationChangedSincePrepare {
    if (!_nativeCamera || _live) return false;
    final params = _encoderParamsForContext();
    return _preparedEncoderRotation != params.rotation;
  }

  Future<void> _applyNativeStreamPrefs() async {
    if (!_nativeCamera) return;
    await RtmpPlatform.setKeepScreenOnDuringStream(_overlayPrefs.keepScreenOn);
    await RtmpPlatform.setVideoStabilization(_overlayPrefs.videoStabilization);
  }

  Future<void> _reprepareCameraForCurrentOrientation() async {
    await _applyCameraOrientation();
  }

  void _hideDock() {
    if (!_dockVisible || !mounted) return;
    setState(() => _dockVisible = false);
  }

  void _toggleDock() {
    setState(() => _dockVisible = !_dockVisible);
  }

  Future<void> _reportBroadcastStatus(String status) async {
    try {
      await widget.api.updateBroadcastStatus(
        widget.match.slug,
        status: status,
        platform: _destination.name,
        watchUrl: _watchUrl,
      );
    } catch (_) {}
  }

  Future<void> _refreshMatchDayStatus() async {
    try {
      final day = await widget.api.getMatchDayStatus(widget.match.slug);
      if (mounted) setState(() => _matchDay = day);
    } catch (_) {}
  }

  StreamMatch _matchForManagement() {
    final base = widget.match;
    final day = _matchDay;
    var broadcast = day?.broadcast ?? base.broadcast;
    if (_live) {
      broadcast = BroadcastStatus(
        status: _streamPaused ? 'paused' : 'streaming',
        platform: broadcast.platform ?? _livePlatform,
        watchUrl: _watchUrl ?? broadcast.watchUrl,
      );
    }
    if (day == null) {
      return StreamMatch(
        slug: base.slug,
        label: base.label,
        overlayEmbedUrl: base.overlayEmbedUrl,
        relaySource: base.relaySource,
        relayPaused: base.relayPaused,
        scoringMode: base.scoringMode,
        scoringActive: base.scoringActive,
        scoringStale: base.scoringStale,
        isLive: base.isLive,
        broadcast: broadcast,
      );
    }
    return StreamMatch(
      slug: base.slug,
      label: day.label.isNotEmpty ? day.label : base.label,
      overlayEmbedUrl: base.overlayEmbedUrl,
      relaySource: base.relaySource,
      relayPaused: day.relayPaused,
      scoringMode: day.scoringMode,
      scoringActive: day.scoringActive,
      scoringStale: day.scoringStale,
      isLive: base.isLive,
      broadcast: broadcast,
    );
  }

  void _openStreamManagement() {
    unawaited(showStreamManagementSheet(
      context: context,
      api: widget.api,
      match: _matchForManagement(),
      onChanged: _refreshMatchDayStatus,
      onDeleted: () {
        if (mounted) Navigator.of(context).pop();
      },
    ));
  }

  Future<void> _shareWatchLink() async {
    final url = _watchUrl;
    if (url == null || url.isEmpty) return;
    await Share.share('Watch live: $url');
  }

  String? get _scorerHudLabel {
    final mode = _matchDay?.scoringMode ?? _scoring?.mode;
    if (mode != 'manual') return null;
    if (_matchDay?.scoringActive == true) return 'ACTIVE';
    return 'WAITING';
  }

  void _applyOverlayLayoutForOrientation() {
    if (!mounted) return;
    if (StreamOrientationHelper.isPortrait(context)) {
      _overlayPrefs = OverlayLayoutPrefs(
        size: _overlayPrefs.size,
        theme: _overlayPrefs.theme,
        density: _overlayPrefs.density,
        heightFraction: 0.18,
        widthFraction: 0.92,
        anchorX: 0.5,
        anchorY: 0.92,
        bottomMargin: 12,
        horizontalInset: 8,
        keepScreenOn: _overlayPrefs.keepScreenOn,
        videoStabilization: _overlayPrefs.videoStabilization,
      );
    } else {
      _overlayPrefs = OverlayLayoutPrefs.cricketLandscape.copyWith(
        theme: _overlayPrefs.theme,
        size: _overlayPrefs.size,
        density: _overlayPrefs.density,
        keepScreenOn: _overlayPrefs.keepScreenOn,
        videoStabilization: _overlayPrefs.videoStabilization,
      );
    }
    setState(() {});
    unawaited(_syncNativeOverlay());
  }

  @override
  void initState() {
    super.initState();
    WidgetsBinding.instance.addObserver(this);
    _rtmpStore = RtmpCredentialsStore(widget.match.slug);
    _init();
  }

  Future<void> _applyClubStreamDestination() async {
    if ((_customRtmpUrl ?? '').isNotEmpty && (_customStreamKey ?? '').isNotEmpty) {
      return;
    }
    try {
      final tw = await widget.api.twitchStatus();
      if (tw['connected'] == true) {
        final name = (tw['display_name'] ?? tw['login'] ?? 'Twitch').toString();
        final keyOk = tw['stream_key_ok'] == true;
        _destination = StreamDestination.twitch;
        if (!mounted) return;
        setState(() {
          _status = keyOk
              ? 'Destination: Club Twitch ($name) — tap Go Live'
              : 'Twitch connected ($name) but stream key check failed — reconnect on home screen';
        });
        return;
      }
      final yt = await widget.api.youtubeStatus();
      if (yt['connected'] == true) {
        final title = (yt['channel_title'] ?? 'YouTube').toString();
        final liveOk = yt['live_streaming_ok'] == true;
        _destination = StreamDestination.youtube;
        if (!mounted) return;
        setState(() {
          _status = liveOk
              ? 'Destination: Club YouTube ($title) — tap Go Live'
              : 'YouTube connected ($title) but live streaming check failed — reconnect on home screen';
        });
      }
    } catch (_) {}
  }

  Future<void> _loadSavedRtmp() async {
    final creds = await _rtmpStore.load();
    if ((creds.server ?? '').isNotEmpty && (creds.key ?? '').isNotEmpty) {
      _customRtmpUrl = creds.server;
      _customStreamKey = creds.key;
      _customWatchUrl = creds.watch;
      _destination = StreamDestination.custom;
      if (mounted) {
        setState(() => _status = 'Volunteer RTMP ready (saved for this stream)');
      }
    } else if (mounted) {
      setState(() => _status = 'Volunteer mode: paste stream key from YouTube Studio (antenna icon)');
    }
  }

  Future<void> _saveRtmpPrefs() async {
    final server = _customRtmpUrl ?? '';
    final key = _customStreamKey ?? '';
    if (server.isEmpty || key.isEmpty) return;
    await _rtmpStore.save(
      server: server,
      key: key,
      watch: _customWatchUrl,
    );
  }

  Future<void> _showPermissionSettingsDialog({
    required String title,
    required String message,
  }) async {
    if (!mounted) return;
    await showDialog<void>(
      context: context,
      builder: (ctx) => AlertDialog(
        title: Text(title),
        content: Text(message),
        actions: [
          TextButton(onPressed: () => Navigator.pop(ctx), child: const Text('Cancel')),
          FilledButton(
            onPressed: () {
              Navigator.pop(ctx);
              openAppSettings();
            },
            child: const Text('Open Settings'),
          ),
        ],
      ),
    );
  }

  Future<bool> _requestAvPermissions() async {
    final statuses = await [Permission.camera, Permission.microphone].request();
    final cam = statuses[Permission.camera] ?? PermissionStatus.denied;
    final mic = statuses[Permission.microphone] ?? PermissionStatus.denied;
    if (cam.isGranted && mic.isGranted) return true;
    if (!mounted) return false;
    await _showPermissionSettingsDialog(
      title: 'Camera & microphone required',
      message:
          'CricRelay Live needs camera and microphone access to broadcast. '
          'Enable them in Settings, then return to this screen.',
    );
    return false;
  }

  Future<void> _requestNotificationPermission() async {
    if (!Platform.isAndroid) return;
    final status = await Permission.notification.request();
    if (status.isGranted) return;
    if (!mounted) return;
    await _showPermissionSettingsDialog(
      title: 'Notifications recommended',
      message:
          'Allow notifications so CricRelay can show a persistent alert while you are live. '
          'You can enable this in Settings.',
    );
  }

  Future<void> _init() async {
    try {
      _overlayStore = OverlayLayoutStore(widget.api, widget.match.slug);
      final avOk = await _requestAvPermissions();
      _avPermissionsGranted = avOk;
      if (!avOk && mounted) {
        setState(() => _status = 'Camera or microphone permission denied');
      }
      _quality = await DeviceProfile.resolveInitialQuality();
      _nativeProfile = NativeEncoderProfile.forNative(_quality);
      _deviceProfile = await DeviceProfile.loadAndroid();
      final prefs = await SharedPreferences.getInstance();
      if (!prefs.containsKey(kStreamQualityPref)) {
        await saveStreamQualityProfile(_quality);
      }
      await _loadSavedRtmp();
      await _applyClubStreamDestination();
      if ((Platform.isAndroid || Platform.isIOS) && avOk) {
        _nativeCamera = await RtmpPlatform.isCaptureSupported;
      }
      if (_nativeCamera) {
        _rtmpStatusSub = RtmpPlatform.statusEvents.listen(_onRtmpStatus);
        if (mounted) setState(() {});
        WidgetsBinding.instance.addPostFrameCallback((_) {
          WidgetsBinding.instance.addPostFrameCallback((_) {
            unawaited(_ensureNativeCameraReady());
          });
        });
      } else if (avOk) {
        try {
          final cams = await availableCameras();
          if (cams.isNotEmpty) {
            final back = cams.firstWhere(
              (c) => c.lensDirection == CameraLensDirection.back,
              orElse: () => cams.first,
            );
            _camera = CameraController(back, ResolutionPreset.high, enableAudio: true);
            await _camera!.initialize();
            await _initZoomLevels();
          } else if (mounted) {
            setState(() => _status = 'No camera found on this device');
          }
        } catch (e) {
          if (mounted) {
            setState(() => _status = StreamErrorMessages.fromObject(e));
          }
        }
      } else if (avOk && mounted) {
        setState(() {
          _initError = Platform.isAndroid
              ? 'Streaming engine missing in this build. Reinstall the latest APK from cricrelay.co.uk'
              : 'Streaming is not available in this iOS build yet.';
        });
      }
      try {
        final cfg = await widget.api.getScoring(widget.match.slug);
        if (mounted) _applyScoringLabel(cfg);
      } catch (_) {
        if (mounted) {
          _scoring = ScoringConfig.localFallback(
            widget.api.baseUrl,
            widget.match.slug,
            'manual',
          );
        }
      }
      try {
        _overlayPrefs = await _overlayStore.load();
        if (_deviceProfile != null && _deviceProfile!.isLowTier && _overlayPrefs.videoStabilization) {
          _overlayPrefs = _overlayPrefs.copyWith(videoStabilization: false);
        }
      } catch (_) {}
      if (avOk) {
        await _loadOverlayWebView();
      }
      if (mounted) setState(() {});
      _applyOverlayLayoutForOrientation();
      await _applyNativeStreamPrefs();
      _matchDayPoll = Timer.periodic(const Duration(seconds: 8), (_) => _refreshMatchDayStatus());
      unawaited(_refreshMatchDayStatus());
      if (mounted) {
        WidgetsBinding.instance.addPostFrameCallback((_) {
          unawaited(maybeShowMatchDayWizard(
            context: context,
            api: widget.api,
            matchSlug: widget.match.slug,
            matchLabel: widget.match.label,
            destination: _destination,
            onOpenDestination: _chooseDestination,
            onOpenScoring: _openScoringMenu,
            onOpenOverlay: _openOverlayLayout,
          ));
        });
      }
    } catch (e) {
      if (mounted) {
        setState(() {
          _initError = StreamErrorMessages.fromObject(e);
          _status = _initError;
        });
      }
    }
  }

  Future<void> _ensureNativeCameraReady() async {
    if (!_nativeCamera || !mounted) return;
    await _refreshDisplayRotation();
    final params = _encoderParamsForContext();
    _encoderWidth = params.width;
    _encoderHeight = params.height;
    await _applyNativeStreamPrefs();
    await RtmpPlatform.prepareCamera(
      width: params.width,
      height: params.height,
      fps: _nativeProfile.fps,
      bitrateBps: _nativeProfile.bitrateBps,
      rotation: params.rotation,
    );
    if (!mounted) return;
    _preparedEncoderRotation = params.rotation;
    for (var attempt = 0; attempt < 40; attempt++) {
      if (await RtmpPlatform.isCameraReady) {
        if (!mounted) return;
        setState(() => _nativeCameraReady = true);
        await _initZoomLevels();
        await _loadOverlayWebView();
        await _syncNativeOverlay();
        if (mounted && StreamOrientationHelper.isPortrait(context)) {
          setState(() => _status = 'Rotate to landscape for cricket — then tap Go Live');
        }
        return;
      }
      await Future<void>.delayed(const Duration(milliseconds: 250));
    }
    if (mounted) {
      setState(() => _status = 'Camera preview loading — wait a moment and try Go Live');
    }
  }

  @override
  void didChangeMetrics() {
    super.didChangeMetrics();
    if (!_nativeCamera || _live || !mounted) return;
    _orientationDebounce?.cancel();
    _orientationDebounce = Timer(const Duration(milliseconds: 180), () {
      if (!mounted || _live || !_nativeCamera) return;
      unawaited(_applyCameraOrientation().then((_) {
        if (!mounted) return;
        if (StreamOrientationHelper.isPortrait(context)) {
          setState(() => _status = 'Rotate to landscape for cricket — then tap Go Live');
        } else {
          setState(() => _status = 'Landscape ready — tap Go Live when your destination is set');
        }
      }));
    });
  }

  @override
  void didChangeAppLifecycleState(AppLifecycleState state) {
    if (_live && state == AppLifecycleState.paused && Platform.isAndroid) {
      unawaited(RtmpPlatform.setPipWhenLive(true));
    }
  }

  Future<void> _syncNativeOverlay() async {
    if (!_nativeCamera) return;
    final url = _overlayStore.embedUrl(widget.match.overlayEmbedUrl, _overlayPrefs);
    await RtmpPlatform.updateOverlay(
      overlayUrl: url,
      overlayHeightFraction: _overlayPrefs.heightFraction,
      overlayWidthFraction: _overlayPrefs.widthFraction,
      overlayAnchorX: _overlayPrefs.anchorX,
      overlayAnchorY: _overlayPrefs.anchorY,
      overlayBottomMargin: _overlayPrefs.bottomMargin,
      overlayHorizontalInset: _overlayPrefs.horizontalInset,
    );
  }

  void _onOverlayLayoutChanged(OverlayLayoutPrefs prefs) {
    _overlayPrefs = prefs;
  }

  Future<void> _persistOverlayLayout() async {
    try {
      await _overlayStore.saveLocal(_overlayPrefs);
      if (_nativeCamera) {
        await _syncNativeOverlay();
      }
    } catch (_) {}
  }

  Future<void> _loadOverlayWebView() async {
    final url = _overlayStore.embedUrl(widget.match.overlayEmbedUrl, _overlayPrefs);
    final web = _web ??
        (WebViewController()
          ..setJavaScriptMode(JavaScriptMode.unrestricted)
          ..setBackgroundColor(const Color(0x00000000)));
    if (web.platform is AndroidWebViewController) {
      (web.platform as AndroidWebViewController).setMediaPlaybackRequiresUserGesture(false);
    }
    await web.loadRequest(Uri.parse(url));
    if (!mounted) return;
    setState(() => _web = web);
  }

  Future<void> _applyWakelock() async {
    if (_live && _overlayPrefs.keepScreenOn) {
      await WakelockPlus.enable();
    } else {
      await WakelockPlus.disable();
    }
  }

  void _liveBlockedSnack(String message) {
    if (!mounted) return;
    ScaffoldMessenger.of(context).showSnackBar(SnackBar(content: Text(message)));
  }

  Future<void> _openOverlayLayout() async {
    if (_overlayLocked) {
      if (!mounted) return;
      ScaffoldMessenger.of(context).showSnackBar(
        const SnackBar(content: Text('Unlock scoreboard to change size or design')),
      );
      return;
    }
    _hideDock();
    final next = await showOverlayLayoutSheet(context: context, initial: _overlayPrefs);
    if (next == null || !mounted) return;
    final blockUi = !_live;
    if (blockUi) setState(() => _busy = true);
    try {
      final synced = await _overlayStore.saveAndSync(next);
      if (!mounted) return;
      _overlayPrefs = synced;
      await _applyNativeStreamPrefs();
      if (_nativeCamera) {
        await _syncNativeOverlay();
      } else if (!_live) {
        await _reloadOverlayWebView();
      }
      await _applyWakelock();
      if (mounted && !_live) {
        setState(() => _status = 'Scoreboard updated — lock position if you want (optional)');
      }
    } catch (e) {
      if (mounted) {
        ScaffoldMessenger.of(context).showSnackBar(
          SnackBar(content: Text(StreamErrorMessages.fromObject(e))),
        );
      }
    } finally {
      if (mounted && blockUi) setState(() => _busy = false);
    }
  }

  Future<void> _reloadOverlayWebView() async {
    final url = _overlayStore.embedUrl(widget.match.overlayEmbedUrl, _overlayPrefs);
    final web = _web;
    if (web == null) {
      await _loadOverlayWebView();
      return;
    }
    await web.loadRequest(Uri.parse(url));
    if (mounted) setState(() {});
  }

  void _toggleOverlayLock() {
    setState(() {
      _overlayLocked = !_overlayLocked;
      _status = _overlayLocked
          ? 'Scoreboard position locked — Go Live does not require lock'
          : 'Drag scoreboard into place (locking is optional)';
    });
    unawaited(_persistOverlayLayout());
    if (_nativeCamera) unawaited(_syncNativeOverlay());
  }

  Future<void> _initZoomLevels() async {
    if (_nativeCamera) {
      try {
        final range = await RtmpPlatform.getZoomRange();
        _minZoom = range.min;
        _maxZoom = range.max;
        _zoom = range.current;
        _zoomUi = range.current;
      } catch (_) {
        _minZoom = 1.0;
        _maxZoom = 1.0;
        _zoom = 1.0;
      }
    } else {
      final cam = _camera;
      if (cam == null || !cam.value.isInitialized) return;
      try {
        _minZoom = await cam.getMinZoomLevel();
        _maxZoom = await cam.getMaxZoomLevel();
        _zoom = _minZoom;
        _zoomUi = _minZoom;
      } catch (_) {
        _minZoom = 1.0;
        _maxZoom = 1.0;
        _zoom = 1.0;
      }
    }
    if (mounted) setState(() {});
  }

  Future<void> _setZoom(double level) async {
    final clamped = level.clamp(_minZoom, _maxZoom);
    if (_nativeCamera) {
      try {
        await RtmpPlatform.setZoom(clamped);
        _zoom = clamped;
        _scheduleZoomUiUpdate(clamped);
      } catch (_) {}
      return;
    }
    final cam = _camera;
    if (cam == null || !cam.value.isInitialized) return;
    try {
      await cam.setZoomLevel(clamped);
      _zoom = clamped;
      _scheduleZoomUiUpdate(clamped);
    } catch (_) {}
  }

  void _scheduleZoomUiUpdate(double clamped) {
    _zoomUiDebounce?.cancel();
    _zoomUiDebounce = Timer(const Duration(milliseconds: 100), () {
      if (mounted) setState(() => _zoomUi = clamped);
    });
  }

  double get _zoomDisplayFactor {
    if (_minZoom <= 0) return 1.0;
    return _zoomUi / _minZoom;
  }

  void _clearFocusUi({bool locked = false}) {
    _focusReticleHide?.cancel();
    _focusLocked = locked;
    _focusReticle = null;
  }

  Future<void> _handlePreviewTap(Offset local, Size previewSize) async {
    if (!_nativeCamera || !_nativeCameraReady || !Platform.isAndroid) return;
    if (previewSize.width < 1 || previewSize.height < 1) return;

    final result = await RtmpPlatform.tapToFocus(
      x: local.dx,
      y: local.dy,
      viewWidth: previewSize.width.round(),
      viewHeight: previewSize.height.round(),
    );
    if (!mounted) return;

    setState(() {
      _focusLocked = result.locked;
      _focusReticle = local;
    });

    _focusReticleHide?.cancel();
    if (!result.locked) {
      _focusReticleHide = Timer(const Duration(seconds: 2), () {
        if (mounted) setState(() => _focusReticle = null);
      });
    }
  }

  Future<void> _openStreamSettings() async {
    _hideDock();
    await showStreamSettingsSheet(
      context: context,
      initial: _quality,
      live: _live,
      onChanged: (p) async {
        if (_live) return;
        if (mounted) {
          setState(() {
            _quality = p;
            _nativeProfile = NativeEncoderProfile.forNative(p);
            _status = 'Stream quality: ${p.label} (${_nativeProfile.width}×${_nativeProfile.height})';
          });
          if (_nativeCamera) {
            setState(() {
              _nativeCameraReady = false;
              _clearFocusUi();
            });
            final params = _encoderParamsForContext();
            await RtmpPlatform.prepareCamera(
              width: params.width,
              height: params.height,
              fps: _nativeProfile.fps,
              bitrateBps: _nativeProfile.bitrateBps,
              rotation: params.rotation,
            );
            if (mounted && await RtmpPlatform.isCameraReady) {
              setState(() => _nativeCameraReady = true);
            }
          }
        }
      },
    );
  }

  void _applyScoringLabel(ScoringConfig cfg) {
    _scoring = cfg;
  }

  void _onRtmpStatus(RtmpStreamEvent e) {
    if (!mounted) return;
    if (e.event == 'error') {
      AppAnalytics.logRtmpEvent('error', e.message);
      final msg = StreamErrorMessages.fromRaw(
        e.message.isNotEmpty ? e.message : StreamErrorMessages.genericFailure,
      );
      if (_live || _busy) {
        unawaited(_handleStreamFailure(msg));
      } else if (_status != null && _status!.contains('Connecting')) {
        setState(() => _status = msg);
      }
    } else if (e.event == 'preview_ready') {
      if (mounted) {
        setState(() {
          _nativeCameraReady = true;
          if (e.message.isNotEmpty) {
            _status = 'Camera ready (${e.message})';
          }
        });
        unawaited(_initZoomLevels());
      }
    } else if (e.event == 'paused') {
      if (mounted) setState(() => _streamPaused = true);
    } else if (e.event == 'resumed') {
      if (mounted) setState(() => _streamPaused = false);
    } else if (e.event.isNotEmpty) {
      AppAnalytics.logRtmpEvent(e.event, e.message);
    }
  }

  Future<void> _handleStreamFailure(String msg) async {
    await _stopEncoder();
    if (_liveManagedByApi) {
      try {
        await widget.api.stopLive(platform: _livePlatform);
      } catch (_) {}
    }
    await WakelockPlus.disable();
    await StreamOrientationHelper.restoreDefaultOrientations();
    await RtmpPlatform.setPipWhenLive(false);
    if (!mounted) return;
    setState(() {
      _live = false;
      _streamPaused = false;
      _liveStartedAt = null;
      _overlayLocked = false;
      _liveManagedByApi = false;
      _livePlatform = null;
      _busy = false;
      _status = msg;
    });
  }

  Future<void> _togglePause() async {
    if (!_live || !_nativeCamera) return;
    setState(() => _busy = true);
    try {
      if (_streamPaused) {
        await RtmpPlatform.resumeStream();
        if (!mounted) return;
        setState(() {
          _streamPaused = false;
          _status = _liveStatusMessage.isNotEmpty
              ? _liveStatusMessage
              : 'Live — broadcast resumed';
        });
        unawaited(_reportBroadcastStatus('streaming'));
      } else {
        await RtmpPlatform.pauseStream();
        if (!mounted) return;
        if (Platform.isAndroid) {
          unawaited(RtmpPlatform.updateStreamNotification('Paused'));
        }
        setState(() {
          _streamPaused = true;
          _status =
              'Broadcast paused — viewers see a black screen. Tap Resume when play continues.';
        });
        unawaited(_reportBroadcastStatus('paused'));
      }
    } catch (e) {
      if (mounted) {
        _liveBlockedSnack(StreamErrorMessages.fromObject(e));
      }
    } finally {
      if (mounted) setState(() => _busy = false);
    }
  }

  @override
  void dispose() {
    WidgetsBinding.instance.removeObserver(this);
    _rtmpStatusSub?.cancel();
    _orientationDebounce?.cancel();
    _zoomUiDebounce?.cancel();
    _focusReticleHide?.cancel();
    _matchDayPoll?.cancel();
    if (_live) {
      unawaited(_reportBroadcastStatus('idle'));
    }
    if (_live && _nativeCamera) {
      unawaited(RtmpPlatform.stopStream());
      if (_liveManagedByApi) {
        unawaited(widget.api.stopLive(platform: _livePlatform));
      }
      unawaited(WakelockPlus.disable());
    }
    unawaited(StreamOrientationHelper.restoreDefaultOrientations());
    unawaited(RtmpPlatform.setPipWhenLive(false));
    _camera?.dispose();
    super.dispose();
  }

  Future<void> _openScoringMenu() async {
    _hideDock();
    ScoringConfig cfg;
    try {
      cfg = _scoring ?? await widget.api.getScoring(widget.match.slug);
    } catch (e) {
      cfg = ScoringConfig.localFallback(widget.api.baseUrl, widget.match.slug, _scoring?.mode ?? 'manual');
      if (mounted) {
        ScaffoldMessenger.of(context).showSnackBar(
          SnackBar(
            content: Text(
              'Using offline scorer link — ${StreamErrorMessages.fromObject(e)}',
            ),
          ),
        );
      }
    }
    if (!mounted) return;
    await showScoringModeSheet(
      context: context,
      api: widget.api,
      matchSlug: widget.match.slug,
      initial: cfg,
      onUpdated: (next) {
        if (mounted) {
          setState(() {
            _applyScoringLabel(next);
            if (next.mode == 'manual' && next.manualInputUrl.isNotEmpty) {
              _status =
                  'Manual scoring — copy the scorer link and share it with a teammate';
            } else if (next.mode == 'auto') {
              _status = 'Auto scoring from Play-Cricket';
            } else if (next.mode == 'ble') {
              _status = 'BLE scoring — use PCS Relay on another phone';
            }
          });
        }
      },
    );
  }

  Future<void> _startEncoder(GoLiveResult cred) async {
    if (!Platform.isAndroid && !Platform.isIOS) {
      throw Exception('In-app streaming requires the CricRelay Stream mobile app.');
    }
    if (!_nativeCamera) {
      throw Exception(
        Platform.isIOS
            ? 'This build cannot stream yet. Install the latest CricRelay Stream IPA from cricrelay.co.uk'
            : 'This APK cannot stream yet. Install the latest CricRelay Stream APK from cricrelay.co.uk',
      );
    }
    if (!_nativeCameraReady) {
      await _ensureNativeCameraReady();
    }
    if (!await RtmpPlatform.isCameraReady) {
      throw Exception(StreamErrorMessages.previewNotReady);
    }
    if (_orientationChangedSincePrepare) {
      await _reprepareCameraForCurrentOrientation();
    }
    final overlayUrl = _overlayStore.embedUrl(widget.match.overlayEmbedUrl, _overlayPrefs);
    AppAnalytics.logBreadcrumb('go_live_start_stream');
    final connected = RtmpPlatform.waitForConnected(
      timeoutMessage: _destination == StreamDestination.twitch
          ? 'Timed out connecting to Twitch. Check home screen Twitch status, then try again.'
          : _destination == StreamDestination.youtube
              ? 'Timed out connecting to YouTube. Start the live in Studio first, then tap Go Live.'
              : 'Timed out connecting to RTMP. Check your stream URL and key, then try again.',
    );
    if (mounted) {
      setState(() => _status = 'Connecting to stream…');
    }
    await RtmpPlatform.startStream(
      rtmpUrl: cred.rtmpUrl,
      streamKey: cred.streamKey,
      overlayUrl: overlayUrl,
      overlayHeightFraction: _overlayPrefs.heightFraction,
      overlayWidthFraction: _overlayPrefs.widthFraction,
      overlayAnchorX: _overlayPrefs.anchorX,
      overlayAnchorY: _overlayPrefs.anchorY,
      overlayBottomMargin: _overlayPrefs.bottomMargin,
      overlayHorizontalInset: _overlayPrefs.horizontalInset,
      width: _encoderWidth,
      height: _encoderHeight,
      bitrateBps: _nativeProfile.bitrateBps,
      fps: _nativeProfile.fps,
    );
    await connected;
    AppAnalytics.logBreadcrumb('go_live_connected');
  }

  Future<void> _stopEncoder() async {
    if ((Platform.isAndroid || Platform.isIOS) && _nativeCamera) {
      await RtmpPlatform.stopStream();
    }
  }

  bool get _hasStreamKey {
    if (_destination != StreamDestination.custom) return true;
    return (_customRtmpUrl ?? '').isNotEmpty && (_customStreamKey ?? '').isNotEmpty;
  }

  Future<void> _goLive() async {
    if (_destination == StreamDestination.custom &&
        ((_customRtmpUrl ?? '').isEmpty || (_customStreamKey ?? '').isEmpty)) {
      setState(() => _status = 'Paste stream URL + key first (antenna icon)');
      await _editCustomDestination();
      return;
    }
    final camReady =
        _nativeCamera ? _nativeCameraReady : (_camera?.value.isInitialized ?? false);
    _hideDock();
    final proceed = await showGoLivePreflightSheet(
      context: context,
      cameraReady: camReady,
      streamKeySet: _hasStreamKey,
      overlayLocked: _overlayLocked,
      orientationLabel: StreamOrientationHelper.labelFor(context),
      orientationChanged: _orientationChangedSincePrepare,
      resolveCameraReady: () async {
        if (_nativeCamera) {
          return _nativeCameraReady || await RtmpPlatform.isCameraReady;
        }
        return _camera?.value.isInitialized ?? false;
      },
    );
    if (!proceed || !mounted) return;
    if (_orientationChangedSincePrepare) {
      await _reprepareCameraForCurrentOrientation();
    }
    await _performGoLive();
  }

  Future<void> _performGoLive() async {
    if (_nativeCamera) {
      if (!_nativeCameraReady) {
        await _ensureNativeCameraReady();
      }
      if (!await RtmpPlatform.isCameraReady) {
        if (mounted) {
          setState(() => _status = StreamErrorMessages.previewNotReady);
        }
        return;
      }
    }
    await _requestNotificationPermission();
    if (!mounted) return;
    if (Platform.isAndroid) {
      final portrait = StreamOrientationHelper.isPortrait(context);
      await RtmpPlatform.setPipAspectRatio(
        width: portrait ? 9 : 16,
        height: portrait ? 16 : 9,
      );
    }
    await AppAnalytics.logEvent('go_live_started', {
      'destination': _destination.name,
      'quality': _quality.label,
    });
    if (!mounted) return;
    setState(() {
      _busy = true;
      _status = switch (_destination) {
        StreamDestination.custom => 'Connecting to RTMP ingest…',
        StreamDestination.twitch => 'Starting Twitch stream…',
        StreamDestination.youtube => 'Starting YouTube stream…',
      };
    });
    try {
      final GoLiveResult cred;
      if (_destination == StreamDestination.custom) {
        cred = GoLiveResult(
          rtmpUrl: _customRtmpUrl ?? '',
          streamKey: _customStreamKey ?? '',
          watchUrl: _customWatchUrl ?? '',
          overlayEmbedUrl: widget.match.overlayEmbedUrl,
        );
        _livePlatform = null;
      } else {
        final platform = _destination == StreamDestination.twitch ? 'twitch' : 'youtube';
        cred = await widget.api.goLive(widget.match.slug, platform: platform);
        _livePlatform = platform;
      }
      await _startEncoder(cred);
      if (!mounted) return;
      await StreamOrientationHelper.lockCurrentOrientation(context);
      await RtmpPlatform.setPipWhenLive(true);
      await AppAnalytics.logEvent('go_live_connected', {
        'destination': _destination.name,
        'quality': _quality.label,
      });
      setState(() {
        _liveManagedByApi = _destination != StreamDestination.custom;
        _watchUrl = cred.watchUrl;
        _live = true;
        _streamPaused = false;
        _liveStartedAt = DateTime.now();
        _status = switch (_destination) {
          StreamDestination.custom => 'Live on custom RTMP',
          StreamDestination.twitch => 'Live on Twitch',
          StreamDestination.youtube => _nativeCamera
              ? 'Live on YouTube — camera + scoreboard only'
              : 'Live on YouTube',
        };
        if (!_overlayPrefs.keepScreenOn) {
          _status = '$_status — you can turn the screen off to save battery';
        }
        _liveStatusMessage = _status!;
      });
      unawaited(_reportBroadcastStatus('streaming'));
      if (cred.watchUrl.isNotEmpty && mounted) {
        ScaffoldMessenger.of(context).showSnackBar(
          SnackBar(
            content: const Text('You are live'),
            action: SnackBarAction(
              label: 'Share watch link',
              onPressed: _shareWatchLink,
            ),
          ),
        );
      }
      await _applyWakelock();
    } catch (e) {
      await AppAnalytics.logEvent('go_live_failed', {
        'destination': _destination.name,
      });
      await _stopEncoder();
      await StreamOrientationHelper.restoreDefaultOrientations();
      await RtmpPlatform.setPipWhenLive(false);
      if (mounted) {
        setState(() {
          _overlayLocked = false;
          _live = false;
          _streamPaused = false;
          _liveStartedAt = null;
          _status = StreamErrorMessages.fromObject(e);
        });
      }
    } finally {
      if (mounted) setState(() => _busy = false);
    }
  }

  Future<void> _stopInternal() async {
    try {
      if (_live) {
        await AppAnalytics.logEvent('stream_stopped', {
          'destination': _destination.name,
        });
        await _stopEncoder();
        if (_liveManagedByApi) {
          await widget.api.stopLive(platform: _livePlatform);
        }
      }
    } catch (_) {}
    await WakelockPlus.disable();
    await StreamOrientationHelper.restoreDefaultOrientations();
    await RtmpPlatform.setPipWhenLive(false);
    if (_live) {
      unawaited(_reportBroadcastStatus('idle'));
    }
    if (mounted) {
      setState(() {
        _live = false;
        _streamPaused = false;
        _liveStartedAt = null;
        _overlayLocked = false;
        _liveManagedByApi = false;
        _livePlatform = null;
      });
    }
  }

  Future<void> _stop() async {
    setState(() => _busy = true);
    await _stopInternal();
    if (mounted) {
      setState(() {
        _busy = false;
        _status = 'Stopped';
      });
    }
  }

  Future<void> _openWatchUrl() async {
    final url = _watchUrl;
    if (url == null || url.isEmpty) return;
    await launchUrl(Uri.parse(url), mode: LaunchMode.externalApplication);
  }

  Future<void> _chooseDestination() async {
    if (_live) {
      _liveBlockedSnack('Destination is locked while you are live. Pause or stop the stream first.');
      return;
    }
    _hideDock();
    final choice = await showModalBottomSheet<String>(
      context: context,
      builder: (ctx) => SafeArea(
        child: Column(
          mainAxisSize: MainAxisSize.min,
          children: [
            ListTile(
              leading: const Icon(Icons.phonelink_ring),
              title: const Text('Volunteer: paste stream key'),
              subtitle: const Text(
                'YouTube Studio or Twitch dashboard key — no login on this phone (recommended for volunteers).',
              ),
              onTap: () => Navigator.of(ctx).pop('custom'),
            ),
            ListTile(
              leading: const Icon(Icons.account_circle),
              title: const Text('Club YouTube (OAuth)'),
              subtitle: const Text('Connect club channel once on the server'),
              onTap: () => Navigator.of(ctx).pop('youtube'),
            ),
            ListTile(
              leading: const Icon(Icons.videogame_asset),
              title: const Text('Club Twitch (OAuth)'),
              subtitle: const Text('Connect club Twitch once on the server'),
              onTap: () => Navigator.of(ctx).pop('twitch'),
            ),
          ],
        ),
      ),
    );
    if (!mounted || choice == null) return;
    if (choice == 'youtube') {
      setState(() {
        _destination = StreamDestination.youtube;
        _status = 'Destination: club YouTube (OAuth)';
      });
      return;
    }
    if (choice == 'twitch') {
      setState(() {
        _destination = StreamDestination.twitch;
        _status = 'Destination: club Twitch (OAuth) — tap Go Live';
      });
      return;
    }
    await _editCustomDestination();
  }

  Future<void> _editCustomDestination() async {
    final serverCtrl = TextEditingController(text: _customRtmpUrl ?? 'rtmp://a.rtmp.youtube.com/live2');
    final keyCtrl = TextEditingController(text: _customStreamKey ?? '');
    final watchCtrl = TextEditingController(text: _customWatchUrl ?? '');
    final ok = await showDialog<bool>(
      context: context,
      builder: (ctx) => AlertDialog(
        title: const Text('Custom RTMP (stream key)'),
        content: SingleChildScrollView(
          child: Column(
            mainAxisSize: MainAxisSize.min,
            children: [
              const Text(
                'YouTube: Studio → Go live → copy URL + key (rtmp://a.rtmp.youtube.com/live2).\n'
                'Twitch: Dashboard → Settings → Stream → copy key (rtmp://live.twitch.tv/app).\n'
                'Volunteer pastes below — no login on this phone.',
                style: TextStyle(fontSize: 12, color: Colors.white70),
              ),
              const SizedBox(height: 10),
              TextField(
                controller: serverCtrl,
                decoration: const InputDecoration(labelText: 'RTMP server or full RTMP URL'),
              ),
              const SizedBox(height: 8),
              TextField(
                controller: keyCtrl,
                decoration: const InputDecoration(labelText: 'Stream key (optional if full URL)'),
              ),
              const SizedBox(height: 8),
              TextField(
                controller: watchCtrl,
                decoration: const InputDecoration(labelText: 'Watch URL (optional)'),
              ),
            ],
          ),
        ),
        actions: [
          TextButton(onPressed: () => Navigator.of(ctx).pop(false), child: const Text('Cancel')),
          FilledButton(onPressed: () => Navigator.of(ctx).pop(true), child: const Text('Save')),
        ],
      ),
    );
    if (ok != true || !mounted) return;
    final watch = watchCtrl.text.trim();
    final parsed = RtmpEndpoint.parse(
      serverInput: serverCtrl.text,
      keyInput: keyCtrl.text,
    );
    final server = parsed.server;
    final key = parsed.key;
    if (server.isEmpty) {
      setState(() => _status = 'Enter RTMP URL/server first');
      return;
    }
    if (key.isEmpty) {
      setState(() => _status = 'Stream key missing. Paste full RTMP URL or provide key.');
      return;
    }
    if (!server.startsWith('rtmp://')) {
      setState(() => _status = 'Server must start with rtmp:// (not a watch link)');
      return;
    }
    setState(() {
      _destination = StreamDestination.custom;
      _customRtmpUrl = server;
      _customStreamKey = key;
      _customWatchUrl = watch;
      _status = 'Stream key saved — tap Go Live when the platform is ready';
    });
    await _saveRtmpPrefs();
  }

  Widget _buildPreviewStack() {
    final camReady =
        _nativeCamera ? _nativeCameraReady : (_camera?.value.isInitialized ?? false);

    return LayoutBuilder(
      builder: (context, constraints) {
        final previewSize = Size(constraints.maxWidth, constraints.maxHeight);

        final stack = Stack(
      fit: StackFit.expand,
      children: [
        if (_nativeCamera && _avPermissionsGranted) ...[
          const ColoredBox(color: AppColors.canvas),
          Positioned.fill(
            child: Platform.isAndroid
                ? const AndroidView(
                    viewType: 'cricrelay-camera-preview',
                    layoutDirection: TextDirection.ltr,
                  )
                : const UiKitView(
                    viewType: 'cricrelay-camera-preview',
                    layoutDirection: TextDirection.ltr,
                  ),
          ),
        ] else if (!_avPermissionsGranted)
          Center(
            child: Padding(
              padding: const EdgeInsets.all(AppSpacing.lg),
              child: Text(
                _status ?? 'Allow camera and microphone to preview your stream.',
                style: appTextTheme.bodyLarge,
                textAlign: TextAlign.center,
              ),
            ),
          )
        else if (camReady && _camera != null) ...[
          CameraPreview(_camera!),
          if (_web != null) _buildFlutterOverlayPreview(),
        ],
        if (_initError != null)
          Center(
            child: Padding(
              padding: const EdgeInsets.all(AppSpacing.lg),
              child: CrErrorBanner(message: _initError!),
            ),
          ),
        if (_nativeCamera && _avPermissionsGranted && !_nativeCameraReady && _initError == null)
          const Center(
            child: CircularProgressIndicator(color: AppColors.accentGreen),
          ),
        if (_nativeCamera && _nativeCameraReady && _web != null)
          _buildScoreboardPreviewOverlay(),
        if (_nativeCamera && _nativeCameraReady)
          DraggableOverlayFrame(
            prefs: _overlayPrefs,
            locked: _overlayLocked,
            onChanged: _onOverlayLayoutChanged,
            onDragEnd: _persistOverlayLayout,
          ),
        if (StreamOrientationHelper.isPortrait(context) && !_live && camReady)
          Positioned(
            top: 72,
            left: 12,
            right: 12,
            child: CrInfoBanner(
              title: 'Rotate to landscape',
              body: 'Cricket match streams require landscape. Turn your phone sideways, then tap Go Live.',
              accentColor: AppColors.warning,
            ),
          ),
        if (_orientationChangedSincePrepare && !_live && _nativeCameraReady && !StreamOrientationHelper.isPortrait(context))
          Positioned(
            top: 72,
            left: 12,
            right: 12,
            child: CrInfoBanner(
              title: 'Set orientation before going live',
              body: 'Hold the phone how viewers should see the stream. Orientation locks when you tap Go Live.',
              accentColor: AppColors.warning,
            ),
          ),
        if (_matchDay?.relayPaused == true && _matchDay?.scoringMode == 'auto')
          Positioned(
            top: 72,
            left: 12,
            right: 12,
            child: CrInfoBanner(
              title: 'Auto scoring paused',
              body: 'Play-Cricket sync is off. Scores won\'t update automatically. Manual scorer still works.',
              accentColor: AppColors.warning,
            ),
          ),
        if (_matchDay?.scoringStale == true && _matchDay?.scoringMode == 'auto')
          Positioned(
            top: 72,
            left: 12,
            right: 12,
            child: CrInfoBanner(
              title: 'Auto scoring stale',
              body: 'No recent Play-Cricket updates. Open Scoring and switch to Manual, or check the fixture.',
              accentColor: AppColors.warning,
            ),
          ),
        if (_overlayLocked)
          Positioned(
            top: 72,
            left: 0,
            right: 0,
            child: Center(child: _OverlayLockedChip()),
          ),
        if (_focusReticle != null)
          CameraFocusReticle(center: _focusReticle!, locked: _focusLocked),
        Positioned(
          top: MediaQuery.of(context).padding.top + 52,
          left: 0,
          right: 0,
          child: BroadcastPreviewHud(
            live: _live,
            paused: _streamPaused,
            qualityLabel: _quality.label.toUpperCase(),
            orientationLabel: StreamOrientationHelper.isPortrait(context) ? 'Portrait' : 'Landscape',
            stabilizationOn: _overlayPrefs.videoStabilization,
            focusLocked: _focusLocked,
            scorerLabel: _scorerHudLabel,
            zoomLabel: camReady && _maxZoom > _minZoom ? '${_zoomDisplayFactor.toStringAsFixed(1)}×' : null,
            liveTimer: _live
                ? CrLiveTimerBadge(
                    startedAt: _liveStartedAt,
                    paused: _streamPaused,
                    onTick: (d) {
                      if (Platform.isAndroid && !_streamPaused) {
                        final minute = d.inMinutes;
                        if (minute == _lastNotifMinute) return;
                        _lastNotifMinute = minute;
                        final m = d.inMinutes.remainder(60).toString().padLeft(2, '0');
                        final s = d.inSeconds.remainder(60).toString().padLeft(2, '0');
                        final h = d.inHours;
                        final label = h > 0 ? '$h:$m:$s' : '$m:$s';
                        unawaited(RtmpPlatform.updateStreamNotification(label));
                      }
                    },
                  )
                : null,
          ),
        ),
        Positioned(
          right: 12,
          bottom: 12,
          child: FloatingActionButton.small(
            heroTag: 'dock_toggle',
            onPressed: _toggleDock,
            tooltip: _dockVisible ? 'Hide controls' : 'Show controls',
            backgroundColor: AppColors.surfaceElevated.withValues(alpha: 0.92),
            child: Icon(_dockVisible ? Icons.expand_more_rounded : Icons.tune_rounded),
          ),
        ),
      ],
    );

    return GestureDetector(
      behavior: HitTestBehavior.translucent,
      onScaleStart: (_) {
        _focusPinchActive = true;
        _pinchBaseZoom = _zoom;
      },
      onScaleEnd: (_) => _focusPinchActive = false,
      onScaleUpdate: (d) => _setZoom(_pinchBaseZoom * d.scale),
      child: Listener(
        onPointerDown: camReady && _nativeCamera && Platform.isAndroid
            ? (e) => _focusTapDown = e.localPosition
            : null,
        onPointerUp: camReady && _nativeCamera && Platform.isAndroid
            ? (e) {
                final down = _focusTapDown;
                _focusTapDown = null;
                if (down == null || _focusPinchActive) return;
                if ((e.localPosition - down).distance > 18) return;
                unawaited(_handlePreviewTap(e.localPosition, previewSize));
              }
            : null,
        onPointerSignal: (event) {
          if (event is PointerScrollEvent) {
            _setZoom(_zoom + event.scrollDelta.dy * -0.002);
          }
        },
        child: stack,
      ),
    );
      },
    );
  }

  Widget _buildScoreboardPreviewOverlay() {
    final web = _web;
    if (web == null) return const SizedBox.shrink();
    return LayoutBuilder(
      builder: (context, constraints) {
        final maxW = constraints.maxWidth;
        final maxH = constraints.maxHeight;
        if (maxW < 64 || maxH < 64) return const SizedBox.shrink();
        final frame = _overlayPrefs.frameRect(maxW, maxH);
        return Positioned(
          left: frame.left,
          top: frame.top,
          width: frame.width,
          height: frame.height,
          child: IgnorePointer(
            ignoring: _overlayLocked,
            child: ClipRRect(
              borderRadius: BorderRadius.circular(6),
              child: ColoredBox(
                color: Colors.black54,
                child: WebViewWidget(controller: web),
              ),
            ),
          ),
        );
      },
    );
  }

  Widget _buildFlutterOverlayPreview() {
    return LayoutBuilder(
      builder: (context, constraints) {
        final frame = _overlayPrefs.frameRect(constraints.maxWidth, constraints.maxHeight);
        return Positioned(
          left: frame.left,
          top: frame.top,
          width: frame.width,
          height: frame.height,
          child: IgnorePointer(
            child: DecoratedBox(
              decoration: BoxDecoration(
                border: Border.all(
                  color: _overlayLocked ? AppColors.overlayFrameLocked : AppColors.overlayFrame,
                  width: 2,
                ),
                borderRadius: BorderRadius.circular(8),
              ),
              child: ClipRRect(
                borderRadius: BorderRadius.circular(6),
                child: WebViewWidget(controller: _web!),
              ),
            ),
          ),
        );
      },
    );
  }

  Widget _buildControlDock({required bool camReady}) {
    return BroadcastControlDock(
      status: _status,
      live: _live,
      paused: _streamPaused,
      busy: _busy,
      camReady: camReady,
      qualityLabel: _quality.label,
      zoom: _zoom,
      minZoom: _minZoom,
      maxZoom: _maxZoom,
      zoomDisplay: _zoomDisplayFactor,
      onZoomChanged: _setZoom,
      onOpenQuality: _openStreamSettings,
      onOpenScoring: _openScoringMenu,
      onOpenOverlay: _openOverlayLayout,
      onToggleOverlayLock: _toggleOverlayLock,
      onOpenDestination: _chooseDestination,
      overlayLocked: _overlayLocked,
      onGoLive: _goLive,
      onStop: _stop,
      onTogglePause: _togglePause,
      onShare: _live && (_watchUrl?.isNotEmpty ?? false) ? () => unawaited(_shareWatchLink()) : null,
    );
  }

  @override
  Widget build(BuildContext context) {
    final camReady =
        _nativeCamera ? _nativeCameraReady : (_camera?.value.isInitialized ?? false);
    final orient = MediaQuery.of(context).orientation;
    final isLandscape = orient == Orientation.landscape;
    return PopScope(
      canPop: !_live,
      onPopInvokedWithResult: (didPop, result) async {
        if (didPop || !_live) return;
        final leave = await showCrConfirmDialog(
          context: context,
          title: 'Leave while live?',
          message:
              'You are still broadcasting. Leave this screen anyway? The stream may continue until you stop it from the notification.',
          confirmLabel: 'Leave',
          destructive: true,
        );
        if (leave == true && context.mounted) {
          Navigator.of(context).pop();
        }
      },
      child: Scaffold(
      backgroundColor: AppColors.canvas,
      extendBodyBehindAppBar: true,
      appBar: PreferredSize(
        preferredSize: const Size.fromHeight(kToolbarHeight),
        child: ClipRect(
          child: BackdropFilter(
            filter: ImageFilter.blur(sigmaX: 16, sigmaY: 16),
            child: AppBar(
              title: Text(
                _matchDay?.label.isNotEmpty == true ? _matchDay!.label : widget.match.label,
                style: const TextStyle(fontSize: 16, fontWeight: FontWeight.w700, letterSpacing: -0.2),
              ),
              backgroundColor: AppColors.hudBackground,
              elevation: 0,
              actions: [
                if (_watchUrl != null)
                  IconButton(
                    icon: const Icon(Icons.open_in_new_rounded),
                    onPressed: _openWatchUrl,
                    tooltip: 'Watch stream',
                  ),
                IconButton(
                  icon: const Icon(Icons.more_vert_rounded),
                  onPressed: _openStreamManagement,
                  tooltip: 'Stream settings',
                ),
              ],
            ),
          ),
        ),
      ),
      body: isLandscape
          ? Row(
              children: [
                Expanded(
                  child: RepaintBoundary(child: _buildPreviewStack()),
                ),
                if (_dockVisible)
                  SizedBox(
                    width: 300,
                    child: _buildControlDock(camReady: camReady),
                  ),
              ],
            )
          : Column(
              children: [
                Expanded(
                  child: RepaintBoundary(child: _buildPreviewStack()),
                ),
                if (_dockVisible)
                  _buildControlDock(camReady: camReady),
              ],
            ),
      ),
    );
  }
}

class _OverlayLockedChip extends StatelessWidget {
  const _OverlayLockedChip();

  @override
  Widget build(BuildContext context) {
    return CrGlassPanel(
      padding: const EdgeInsets.symmetric(horizontal: 12, vertical: 6),
      borderRadius: AppSpacing.radiusPill,
      blur: 12,
      child: Row(
        mainAxisSize: MainAxisSize.min,
        children: [
          const Icon(Icons.lock_rounded, size: 14, color: AppColors.warning),
          const SizedBox(width: 6),
          Text('Scoreboard locked', style: metricStyle(size: 11, color: AppColors.onBackgroundMuted)),
        ],
      ),
    );
  }
}

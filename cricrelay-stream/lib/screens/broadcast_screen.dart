import 'dart:async';
import 'dart:io';

import 'package:flutter/gestures.dart';
import 'package:flutter/material.dart';
import 'package:permission_handler/permission_handler.dart';
import 'package:camera/camera.dart';
import 'package:url_launcher/url_launcher.dart' show launchUrl, LaunchMode;
import 'package:shared_preferences/shared_preferences.dart';
import 'package:wakelock_plus/wakelock_plus.dart';
import 'package:webview_flutter/webview_flutter.dart';
import 'package:webview_flutter_android/webview_flutter_android.dart';

import '../services/api.dart';
import '../services/overlay_layout_store.dart';
import '../services/rtmp_platform.dart';
import '../models/overlay_layout_prefs.dart';
import '../models/stream_destination.dart';
import '../models/stream_quality.dart';
import '../utils/rtmp_endpoint.dart';
import '../theme/app_theme.dart';
import '../widgets/broadcast_control_dock.dart';
import '../widgets/overlay_layout_sheet.dart';
import '../widgets/scoring_mode_sheet.dart';
import '../widgets/stream_settings_sheet.dart';
import '../widgets/ui_kit.dart';

/// Live broadcast: in-app RTMP to YouTube + overlay + scoring menu.
class BroadcastScreen extends StatefulWidget {
  const BroadcastScreen({super.key, required this.api, required this.match});

  final CricRelayApi api;
  final StreamMatch match;

  @override
  State<BroadcastScreen> createState() => _BroadcastScreenState();
}

class _BroadcastScreenState extends State<BroadcastScreen> {
  CameraController? _camera;
  WebViewController? _web;
  bool _live = false;
  bool _busy = false;
  String? _status;
  String? _watchUrl;
  ScoringConfig? _scoring;
  /// Native camera + GL overlay (not screen capture).
  bool _nativeCamera = false;
  bool _nativeCameraReady = false;
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
  late final OverlayLayoutStore _overlayStore;
  OverlayLayoutPrefs _overlayPrefs = const OverlayLayoutPrefs();
  bool _overlayLocked = false;
  StreamSubscription<RtmpStreamEvent>? _rtmpStatusSub;

  @override
  void initState() {
    super.initState();
    _init();
  }

  String _rtmpPrefsKey(String field) => 'rtmp_${field}_${widget.match.slug}';

  Future<void> _loadSavedRtmp() async {
    final prefs = await SharedPreferences.getInstance();
    final server = prefs.getString(_rtmpPrefsKey('server'));
    final key = prefs.getString(_rtmpPrefsKey('key'));
    if (server != null && server.isNotEmpty && key != null && key.isNotEmpty) {
      _customRtmpUrl = server;
      _customStreamKey = key;
      _customWatchUrl = prefs.getString(_rtmpPrefsKey('watch'));
      _destination = StreamDestination.custom;
      if (mounted) {
        setState(() => _status = 'Volunteer RTMP ready (saved for this stream)');
      }
    } else if (mounted) {
      setState(() => _status = 'Volunteer mode: paste stream key from YouTube Studio (antenna icon)');
    }
  }

  Future<void> _saveRtmpPrefs() async {
    final prefs = await SharedPreferences.getInstance();
    await prefs.setString(_rtmpPrefsKey('server'), _customRtmpUrl ?? '');
    await prefs.setString(_rtmpPrefsKey('key'), _customStreamKey ?? '');
    if (_customWatchUrl != null && _customWatchUrl!.isNotEmpty) {
      await prefs.setString(_rtmpPrefsKey('watch'), _customWatchUrl!);
    }
  }

  Future<void> _init() async {
    _overlayStore = OverlayLayoutStore(widget.api, widget.match.slug);
    await [Permission.camera, Permission.microphone].request();
    _quality = await loadStreamQualityProfile();
    await _loadSavedRtmp();
    if (Platform.isAndroid || Platform.isIOS) {
      _nativeCamera = await RtmpPlatform.isCaptureSupported;
    }
    if (_nativeCamera) {
      _nativeCameraReady = true;
      _rtmpStatusSub = RtmpPlatform.statusEvents.listen(_onRtmpStatus);
      WidgetsBinding.instance.addPostFrameCallback((_) async {
        await RtmpPlatform.prepareCamera(
          width: _quality.width,
          height: _quality.height,
          fps: _quality.fps,
          bitrateBps: _quality.bitrateBps,
        );
        await _syncNativeOverlay();
        await _initZoomLevels();
      });
    } else {
      final cams = await availableCameras();
      if (cams.isNotEmpty) {
        final back = cams.firstWhere(
          (c) => c.lensDirection == CameraLensDirection.back,
          orElse: () => cams.first,
        );
        _camera = CameraController(back, ResolutionPreset.high, enableAudio: true);
        await _camera!.initialize();
        await _initZoomLevels();
      }
    }
    try {
      final cfg = await widget.api.getScoring(widget.match.slug);
      if (mounted) _applyScoringLabel(cfg);
    } catch (_) {}
    try {
      _overlayPrefs = await _overlayStore.load();
    } catch (_) {}
    if (!_nativeCamera) {
      await _loadOverlayWebView();
    }
  }

  Future<void> _syncNativeOverlay() async {
    if (!_nativeCamera) return;
    final url = _overlayStore.embedUrl(widget.match.overlayEmbedUrl, _overlayPrefs);
    await RtmpPlatform.updateOverlay(
      overlayUrl: url,
      overlayHeightFraction: _overlayPrefs.heightFraction,
      overlayBottomMargin: _overlayPrefs.bottomMargin,
      overlayHorizontalInset: _overlayPrefs.horizontalInset,
    );
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

  Future<void> _openOverlayLayout() async {
    if (_overlayLocked) {
      if (!mounted) return;
      ScaffoldMessenger.of(context).showSnackBar(
        const SnackBar(content: Text('Unlock overlay to change size or design')),
      );
      return;
    }
    final next = await showOverlayLayoutSheet(context: context, initial: _overlayPrefs);
    if (next == null || !mounted) return;
    setState(() => _busy = true);
    try {
      final synced = await _overlayStore.saveAndSync(next);
      _overlayPrefs = synced;
      if (_nativeCamera) {
        await _syncNativeOverlay();
      } else {
        await _reloadOverlayWebView();
      }
      await _applyWakelock();
      if (mounted) {
        setState(() => _status = 'Overlay updated — lock before going live');
      }
    } catch (e) {
      if (mounted) {
        ScaffoldMessenger.of(context).showSnackBar(SnackBar(content: Text(e.toString())));
      }
    } finally {
      if (mounted) setState(() => _busy = false);
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
          ? 'Overlay locked — preview touches ignored'
          : 'Overlay unlocked — adjust layout, then lock';
    });
  }

  Future<void> _initZoomLevels() async {
    if (_nativeCamera) {
      try {
        final range = await RtmpPlatform.getZoomRange();
        _minZoom = range.min;
        _maxZoom = range.max;
        _zoom = range.current;
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
        if (mounted) setState(() => _zoom = clamped);
      } catch (_) {}
      return;
    }
    final cam = _camera;
    if (cam == null || !cam.value.isInitialized) return;
    try {
      await cam.setZoomLevel(clamped);
      if (mounted) setState(() => _zoom = clamped);
    } catch (_) {}
  }

  double get _zoomDisplayFactor {
    if (_minZoom <= 0) return 1.0;
    return _zoom / _minZoom;
  }

  Future<void> _openStreamSettings() async {
    await showStreamSettingsSheet(
      context: context,
      initial: _quality,
      onChanged: (p) async {
        if (mounted) {
          setState(() {
            _quality = p;
            _status = 'Stream quality: ${p.label} (${p.width}×${p.height})';
          });
          if (_nativeCamera && !_live) {
            await RtmpPlatform.prepareCamera(
              width: p.width,
              height: p.height,
              fps: p.fps,
              bitrateBps: p.bitrateBps,
            );
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
      final msg = e.message.isNotEmpty ? e.message : 'Stream failed';
      if (_live || _busy) {
        unawaited(_handleStreamFailure(msg));
      } else if (_status != null && _status!.contains('Connecting')) {
        setState(() => _status = msg);
      }
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
    if (!mounted) return;
    setState(() {
      _live = false;
      _overlayLocked = false;
      _liveManagedByApi = false;
      _livePlatform = null;
      _busy = false;
      _status = msg;
    });
  }

  @override
  void dispose() {
    _rtmpStatusSub?.cancel();
    _stopInternal();
    _camera?.dispose();
    super.dispose();
  }

  Future<void> _openScoringMenu() async {
    ScoringConfig cfg;
    try {
      cfg = _scoring ?? await widget.api.getScoring(widget.match.slug);
    } catch (e) {
      if (!mounted) return;
      ScaffoldMessenger.of(context).showSnackBar(SnackBar(content: Text(e.toString())));
      return;
    }
    if (!mounted) return;
    await showScoringModeSheet(
      context: context,
      api: widget.api,
      matchSlug: widget.match.slug,
      initial: cfg,
      onUpdated: (next) {
        if (mounted) setState(() => _applyScoringLabel(next));
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
    final overlayUrl = _overlayStore.embedUrl(widget.match.overlayEmbedUrl, _overlayPrefs);
    await RtmpPlatform.prepareCamera(
      width: _quality.width,
      height: _quality.height,
      fps: _quality.fps,
      bitrateBps: _quality.bitrateBps,
    );
    final connected = RtmpPlatform.waitForConnected();
    if (mounted) {
      setState(() => _status = 'Connecting to stream…');
    }
    await RtmpPlatform.startStream(
      rtmpUrl: cred.rtmpUrl,
      streamKey: cred.streamKey,
      overlayUrl: overlayUrl,
      overlayHeightFraction: _overlayPrefs.heightFraction,
      overlayBottomMargin: _overlayPrefs.bottomMargin,
      overlayHorizontalInset: _overlayPrefs.horizontalInset,
      width: _quality.width,
      height: _quality.height,
      bitrateBps: _quality.bitrateBps,
      fps: _quality.fps,
    );
    await connected;
  }

  Future<void> _stopEncoder() async {
    if ((Platform.isAndroid || Platform.isIOS) && _nativeCamera) {
      await RtmpPlatform.stopStream();
    }
  }

  Future<void> _goLive() async {
    if (_destination == StreamDestination.custom &&
        ((_customRtmpUrl ?? '').isEmpty || (_customStreamKey ?? '').isEmpty)) {
      setState(() => _status = 'Paste stream URL + key first (antenna icon)');
      await _editCustomDestination();
      return;
    }
    setState(() {
      _busy = true;
      _status = switch (_destination) {
        StreamDestination.custom => 'Connecting to RTMP ingest…',
        StreamDestination.twitch => 'Starting Twitch stream…',
        StreamDestination.youtube => 'Starting YouTube stream…',
      };
    });
    try {
      if (_scoring?.mode != 'auto') {
        try {
          final auto = await widget.api.setScoring(widget.match.slug, 'auto');
          if (mounted) setState(() => _applyScoringLabel(auto));
        } catch (_) {}
      }
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
      setState(() {
        _overlayLocked = true;
        _liveManagedByApi = _destination != StreamDestination.custom;
        _watchUrl = cred.watchUrl;
        _live = true;
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
      });
      await _applyWakelock();
    } catch (e) {
      await _stopEncoder();
      if (mounted) {
        setState(() {
          _overlayLocked = false;
          _live = false;
          _status = e.toString();
        });
      }
    } finally {
      if (mounted) setState(() => _busy = false);
    }
  }

  Future<void> _stopInternal() async {
    try {
      if (_live) {
        await _stopEncoder();
        if (_liveManagedByApi) {
          await widget.api.stopLive(platform: _livePlatform);
        }
      }
    } catch (_) {}
    await WakelockPlus.disable();
    if (mounted) {
      setState(() {
        _live = false;
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
        _status = 'Destination: club Twitch (OAuth)';
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

    Widget stack = Stack(
      fit: StackFit.expand,
      children: [
        if (camReady && _nativeCamera)
          Platform.isAndroid
              ? const AndroidView(
                  viewType: 'cricrelay-camera-preview',
                  layoutDirection: TextDirection.ltr,
                )
              : const UiKitView(
                  viewType: 'cricrelay-camera-preview',
                  layoutDirection: TextDirection.ltr,
                )
        else if (camReady && _camera != null) ...[
          CameraPreview(_camera!),
          if (_web != null) _buildFlutterOverlayPreview(),
        ],
        if (_nativeCamera && camReady)
          Positioned(
            left: _overlayPrefs.horizontalInset,
            right: _overlayPrefs.horizontalInset,
            bottom: _overlayPrefs.bottomMargin,
            height: MediaQuery.of(context).size.height * _overlayPrefs.heightFraction,
            child: IgnorePointer(
              child: DecoratedBox(
                decoration: BoxDecoration(
                  border: Border.all(
                    color: _overlayLocked ? Colors.white24 : AppColors.accentGreen,
                    width: 2,
                  ),
                  borderRadius: BorderRadius.circular(8),
                ),
              ),
            ),
          ),
        if (_overlayLocked)
          const Positioned(
            top: 48,
            left: 0,
            right: 0,
            child: Center(
              child: _OverlayLockedChip(),
            ),
          ),
        if (_live) const Positioned(top: 12, left: 12, child: CrLiveBadge()),
        if (camReady && _maxZoom > _minZoom && !_overlayLocked)
          Positioned(
            top: 12,
            right: 12,
            child: Container(
              padding: const EdgeInsets.symmetric(horizontal: 8, vertical: 4),
              decoration: BoxDecoration(
                color: Colors.black54,
                borderRadius: BorderRadius.circular(6),
              ),
              child: Text(
                '${_zoomDisplayFactor.toStringAsFixed(1)}×',
                style: const TextStyle(fontWeight: FontWeight.w600),
              ),
            ),
          ),
      ],
    );

    if (_overlayLocked) {
      return AbsorbPointer(child: stack);
    }
    return GestureDetector(
      onScaleStart: (_) => _pinchBaseZoom = _zoom,
      onScaleUpdate: (d) => _setZoom(_pinchBaseZoom * d.scale),
      child: Listener(
        onPointerSignal: (event) {
          if (event is PointerScrollEvent) {
            _setZoom(_zoom + event.scrollDelta.dy * -0.002);
          }
        },
        child: stack,
      ),
    );
  }

  Widget _buildFlutterOverlayPreview() {
    final mq = MediaQuery.of(context);
    final overlayH = mq.size.height * _overlayPrefs.heightFraction;
    final inset = _overlayPrefs.horizontalInset;
    final bottom = _overlayPrefs.bottomMargin;
    return Positioned(
      left: inset,
      right: inset,
      bottom: bottom,
      height: overlayH,
      child: IgnorePointer(
        child: DecoratedBox(
          decoration: BoxDecoration(
            border: Border.all(
              color: _overlayLocked ? Colors.white24 : const Color(0xFF22D3A8),
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
  }

  @override
  Widget build(BuildContext context) {
    final camReady =
        _nativeCamera ? _nativeCameraReady : (_camera?.value.isInitialized ?? false);
    final orient = MediaQuery.of(context).orientation;
    final isLandscape = orient == Orientation.landscape;
    return Scaffold(
      backgroundColor: Colors.black,
      extendBodyBehindAppBar: true,
      appBar: AppBar(
        title: Text(
          widget.match.label,
          style: const TextStyle(fontSize: 16, fontWeight: FontWeight.w600),
        ),
        backgroundColor: Colors.black54,
        elevation: 0,
        actions: [
          if (_watchUrl != null)
            IconButton(
              icon: const Icon(Icons.open_in_new),
              onPressed: _openWatchUrl,
              tooltip: 'Watch stream',
            ),
        ],
      ),
      body: isLandscape
          ? Row(
              children: [
                Expanded(child: _buildPreviewStack()),
                SizedBox(
                  width: 300,
                  child: BroadcastControlDock(
                    status: _status,
                    live: _live,
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
                  ),
                ),
              ],
            )
          : Column(
              children: [
                Expanded(child: _buildPreviewStack()),
                BroadcastControlDock(
                  status: _status,
                  live: _live,
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
                ),
              ],
            ),
    );
  }
}

class _OverlayLockedChip extends StatelessWidget {
  const _OverlayLockedChip();

  @override
  Widget build(BuildContext context) {
    return Container(
      padding: const EdgeInsets.symmetric(horizontal: 10, vertical: 4),
      decoration: BoxDecoration(
        color: Colors.black87,
        borderRadius: BorderRadius.circular(20),
        border: Border.all(color: Colors.white24),
      ),
      child: const Row(
        mainAxisSize: MainAxisSize.min,
        children: [
          Icon(Icons.lock, size: 14),
          SizedBox(width: 6),
          Text('Overlay locked', style: TextStyle(fontSize: 12)),
        ],
      ),
    );
  }
}

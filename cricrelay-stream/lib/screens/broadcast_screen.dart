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
import '../services/rtmp_platform.dart';
import '../models/stream_quality.dart';
import '../utils/rtmp_endpoint.dart';
import '../widgets/scoring_mode_sheet.dart';
import '../widgets/stream_settings_sheet.dart';

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
  String _scoringLabel = 'Scoring';
  bool _androidCapture = false;
  /// Default: volunteer phone uses stream key from YouTube Studio (no club Google login).
  bool _useCustomDestination = true;
  bool _liveManagedByApi = false;
  String? _customRtmpUrl;
  String? _customStreamKey;
  String? _customWatchUrl;
  double _zoom = 1.0;
  double _minZoom = 1.0;
  double _maxZoom = 1.0;
  double _pinchBaseZoom = 1.0;
  StreamQualityProfile _quality = StreamQualityProfile.high;

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
      _useCustomDestination = true;
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
    await [Permission.camera, Permission.microphone].request();
    _quality = await loadStreamQualityProfile();
    await _loadSavedRtmp();
    if (Platform.isAndroid) {
      _androidCapture = await RtmpPlatform.isCaptureSupported;
    }
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
    try {
      final cfg = await widget.api.getScoring(widget.match.slug);
      if (mounted) _applyScoringLabel(cfg);
    } catch (_) {}
    final overlay = widget.match.overlayEmbedUrl.contains('embed=1')
        ? widget.match.overlayEmbedUrl
        : '${widget.match.overlayEmbedUrl}${widget.match.overlayEmbedUrl.contains('?') ? '&' : '?'}embed=1';
    final web = WebViewController()
      ..setJavaScriptMode(JavaScriptMode.unrestricted)
      ..setBackgroundColor(const Color(0x00000000));
    if (web.platform is AndroidWebViewController) {
      (web.platform as AndroidWebViewController).setMediaPlaybackRequiresUserGesture(false);
    }
    await web.loadRequest(Uri.parse(overlay));
    if (!mounted) return;
    setState(() => _web = web);
  }

  Future<void> _initZoomLevels() async {
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
    if (mounted) setState(() {});
  }

  Future<void> _setZoom(double level) async {
    final cam = _camera;
    if (cam == null || !cam.value.isInitialized) return;
    final clamped = level.clamp(_minZoom, _maxZoom);
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
      onChanged: (p) {
        if (mounted) {
          setState(() {
            _quality = p;
            _status = 'Stream quality: ${p.label} (${p.width}×${p.height})';
          });
        }
      },
    );
  }

  void _applyScoringLabel(ScoringConfig cfg) {
    _scoring = cfg;
    _scoringLabel = switch (cfg.mode) {
      'auto' => 'Scoring: Auto',
      'ble' => 'Scoring: BLE',
      _ => 'Scoring: Manual',
    };
  }

  @override
  void dispose() {
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
    if (!Platform.isAndroid) {
      throw Exception('In-app YouTube streaming is Android-only for now. Use an Android device.');
    }
    if (!_androidCapture) {
      throw Exception(
        'This APK cannot stream yet. Install the latest CricRelay Stream APK from cricrelay.co.uk',
      );
    }
    final connected = RtmpPlatform.waitForConnected();
    await RtmpPlatform.startStream(
      rtmpUrl: cred.rtmpUrl,
      streamKey: cred.streamKey,
      overlayUrl: cred.overlayEmbedUrl,
      width: _quality.width,
      height: _quality.height,
      bitrateBps: _quality.bitrateBps,
      fps: _quality.fps,
    );
    if (mounted) {
      setState(() => _status = 'Connecting to YouTube… (Studio must be live)');
    }
    await connected;
  }

  Future<void> _stopEncoder() async {
    if (Platform.isAndroid && _androidCapture) {
      await RtmpPlatform.stopStream();
    }
  }

  Future<void> _goLive() async {
    if (_useCustomDestination &&
        ((_customRtmpUrl ?? '').isEmpty || (_customStreamKey ?? '').isEmpty)) {
      setState(() => _status = 'Paste Studio stream URL + key first (antenna icon)');
      await _editCustomDestination();
      return;
    }
    setState(() {
      _busy = true;
      _status = _useCustomDestination
          ? 'Connecting to YouTube ingest…'
          : 'Starting YouTube stream…';
    });
    try {
      if (_scoring?.mode != 'auto') {
        try {
          final auto = await widget.api.setScoring(widget.match.slug, 'auto');
          if (mounted) setState(() => _applyScoringLabel(auto));
        } catch (_) {}
      }
      final cred = _useCustomDestination
          ? GoLiveResult(
              rtmpUrl: _customRtmpUrl ?? '',
              streamKey: _customStreamKey ?? '',
              watchUrl: _customWatchUrl ?? '',
              overlayEmbedUrl: widget.match.overlayEmbedUrl,
            )
          : await widget.api.goLive(widget.match.slug);
      await WakelockPlus.enable();
      await _startEncoder(cred);
      if (!mounted) return;
      setState(() {
        _liveManagedByApi = !_useCustomDestination;
        _watchUrl = cred.watchUrl;
        _live = true;
        _status = _useCustomDestination
            ? 'Live on custom RTMP destination'
            : (_androidCapture
                ? 'Live on YouTube — scoreboard is burned into the stream'
                : 'Live on YouTube');
      });
    } catch (e) {
      await _stopEncoder();
      if (mounted) setState(() => _status = e.toString());
    } finally {
      if (mounted) setState(() => _busy = false);
    }
  }

  Future<void> _stopInternal() async {
    try {
      if (_live) {
        await _stopEncoder();
        if (_liveManagedByApi) {
          await widget.api.stopLive();
        }
      }
    } catch (_) {}
    await WakelockPlus.disable();
    _live = false;
    _liveManagedByApi = false;
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
              title: const Text('Volunteer: YouTube Studio stream key'),
              subtitle: const Text(
                'Recommended — club starts live in Studio, volunteer pastes key. No Google login on this phone.',
              ),
              onTap: () => Navigator.of(ctx).pop('custom'),
            ),
            ListTile(
              leading: const Icon(Icons.account_circle),
              title: const Text('Club account (OAuth)'),
              subtitle: const Text('One phone logged into club YouTube — not for rotating volunteers'),
              onTap: () => Navigator.of(ctx).pop('oauth'),
            ),
          ],
        ),
      ),
    );
    if (!mounted || choice == null) return;
    if (choice == 'oauth') {
      setState(() {
        _useCustomDestination = false;
        _status = 'Destination: connected YouTube account';
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
        title: const Text('YouTube Studio stream key'),
        content: SingleChildScrollView(
          child: Column(
            mainAxisSize: MainAxisSize.min,
            children: [
              const Text(
                '1. Club admin: YouTube Studio → Go live → copy Stream URL + Stream key.\n'
                '2. Volunteer: paste below (no Google login on this phone).\n'
                '3. Tap Go Live.\n\n'
                'Server is usually rtmp://a.rtmp.youtube.com/live2',
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
      _useCustomDestination = true;
      _customRtmpUrl = server;
      _customStreamKey = key;
      _customWatchUrl = watch;
      _status = 'Volunteer RTMP saved — tap Go Live when Studio shows LIVE';
    });
    await _saveRtmpPrefs();
  }

  Widget _buildPreviewStack() {
    final camReady = _camera?.value.isInitialized ?? false;
    final portrait = MediaQuery.of(context).orientation == Orientation.portrait;
    final overlayH = _androidCapture ? (portrait ? 140.0 : 120.0) : (portrait ? 110.0 : 96.0);
    return GestureDetector(
      onScaleStart: (_) => _pinchBaseZoom = _zoom,
      onScaleUpdate: (d) => _setZoom(_pinchBaseZoom * d.scale),
      child: Listener(
        onPointerSignal: (event) {
          if (event is PointerScrollEvent) {
            _setZoom(_zoom + event.scrollDelta.dy * -0.002);
          }
        },
        child: Stack(
          fit: StackFit.expand,
          children: [
            if (camReady) CameraPreview(_camera!),
            if (_web != null)
              Positioned(
                left: 8,
                right: 8,
                bottom: 8,
                height: overlayH,
                child: IgnorePointer(
                  child: DecoratedBox(
                    decoration: BoxDecoration(
                      border: Border.all(color: const Color(0xFF22D3A8), width: 2),
                      borderRadius: BorderRadius.circular(8),
                    ),
                    child: ClipRRect(
                      borderRadius: BorderRadius.circular(6),
                      child: WebViewWidget(controller: _web!),
                    ),
                  ),
                ),
              ),
            if (_live) const Positioned(top: 12, left: 12, child: _LiveBadge()),
            if (camReady && _maxZoom > _minZoom)
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
        ),
      ),
    );
  }

  @override
  Widget build(BuildContext context) {
    final camReady = _camera?.value.isInitialized ?? false;
    final orient = MediaQuery.of(context).orientation;
    final isLandscape = orient == Orientation.landscape;
    return Scaffold(
      backgroundColor: Colors.black,
      appBar: AppBar(
        title: Text(widget.match.label),
        backgroundColor: Colors.black87,
        actions: [
          if (_watchUrl != null)
            IconButton(
              icon: const Icon(Icons.open_in_new),
              onPressed: _openWatchUrl,
              tooltip: 'Open YouTube',
            ),
          IconButton(
            icon: const Icon(Icons.hd),
            onPressed: _openStreamSettings,
            tooltip: 'Stream quality (${_quality.label})',
          ),
          IconButton(
            icon: const Icon(Icons.settings_input_antenna),
            onPressed: _chooseDestination,
            tooltip: _useCustomDestination ? 'Volunteer stream key (Studio)' : 'Club YouTube OAuth',
          ),
          TextButton(
            onPressed: _openScoringMenu,
            child: Text(_scoringLabel, style: const TextStyle(fontSize: 12)),
          ),
        ],
      ),
      body: isLandscape
          ? Row(
              children: [
                Expanded(child: _buildPreviewStack()),
                SizedBox(
                  width: 270,
                  child: Material(
                    color: Colors.black87,
                    child: Padding(
                      padding: const EdgeInsets.all(12),
                      child: _ControlPanel(
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
                        onGoLive: _goLive,
                        onStop: _stop,
                      ),
                    ),
                  ),
                ),
              ],
            )
          : Column(
              children: [
                Expanded(child: _buildPreviewStack()),
                Material(
                  color: Colors.black87,
                  child: Padding(
                    padding: const EdgeInsets.all(12),
                    child: _ControlPanel(
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
                      onGoLive: _goLive,
                      onStop: _stop,
                    ),
                  ),
                ),
              ],
            ),
    );
  }
}

class _ControlPanel extends StatelessWidget {
  const _ControlPanel({
    required this.status,
    required this.live,
    required this.busy,
    required this.camReady,
    required this.qualityLabel,
    required this.zoom,
    required this.minZoom,
    required this.maxZoom,
    required this.zoomDisplay,
    required this.onZoomChanged,
    required this.onOpenQuality,
    required this.onOpenScoring,
    required this.onGoLive,
    required this.onStop,
  });

  final String? status;
  final bool live;
  final bool busy;
  final bool camReady;
  final String qualityLabel;
  final double zoom;
  final double minZoom;
  final double maxZoom;
  final double zoomDisplay;
  final ValueChanged<double> onZoomChanged;
  final VoidCallback onOpenQuality;
  final VoidCallback onOpenScoring;
  final Future<void> Function() onGoLive;
  final Future<void> Function() onStop;

  @override
  Widget build(BuildContext context) {
    final canZoom = camReady && maxZoom > minZoom;
    return Column(
      crossAxisAlignment: CrossAxisAlignment.stretch,
      children: [
        if (status != null)
          Text(status!, textAlign: TextAlign.center, style: const TextStyle(fontSize: 13)),
        const SizedBox(height: 8),
        OutlinedButton.icon(
          onPressed: onOpenQuality,
          icon: const Icon(Icons.hd),
          label: Text('Quality: $qualityLabel'),
        ),
        if (canZoom) ...[
          const SizedBox(height: 6),
          Row(
            children: [
              const Icon(Icons.zoom_out, size: 18),
              Expanded(
                child: Slider(
                  value: zoom,
                  min: minZoom,
                  max: maxZoom,
                  onChanged: onZoomChanged,
                ),
              ),
              const Icon(Icons.zoom_in, size: 18),
              Text('${zoomDisplay.toStringAsFixed(1)}×', style: const TextStyle(fontSize: 12)),
            ],
          ),
          const Text(
            'Pinch on preview to zoom (uses full camera range)',
            textAlign: TextAlign.center,
            style: TextStyle(fontSize: 11, color: Colors.white54),
          ),
        ],
        const SizedBox(height: 8),
        OutlinedButton.icon(
          onPressed: onOpenScoring,
          icon: const Icon(Icons.scoreboard),
          label: const Text('Scoring'),
        ),
        const SizedBox(height: 8),
        !live
            ? FilledButton.icon(
                onPressed: (busy || !camReady) ? null : onGoLive,
                icon: const Icon(Icons.play_arrow),
                label: const Text('Go Live'),
              )
            : FilledButton.tonal(
                onPressed: busy ? null : onStop,
                style: FilledButton.styleFrom(
                  backgroundColor: Colors.red.shade800,
                ),
                child: const Text('Stop'),
              ),
      ],
    );
  }
}

class _LiveBadge extends StatelessWidget {
  const _LiveBadge();

  @override
  Widget build(BuildContext context) {
    return Container(
      padding: const EdgeInsets.symmetric(horizontal: 10, vertical: 4),
      color: Colors.red,
      child: const Text('LIVE', style: TextStyle(fontWeight: FontWeight.bold)),
    );
  }
}

import 'dart:io';

import 'package:flutter/material.dart';
import 'package:permission_handler/permission_handler.dart';
import 'package:camera/camera.dart';
import 'package:url_launcher/url_launcher.dart' show launchUrl, LaunchMode;
import 'package:wakelock_plus/wakelock_plus.dart';
import 'package:webview_flutter/webview_flutter.dart';
import 'package:webview_flutter_android/webview_flutter_android.dart';

import '../services/api.dart';
import '../services/rtmp_platform.dart';
import '../widgets/scoring_mode_sheet.dart';

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
  bool _useCustomDestination = false;
  bool _liveManagedByApi = false;
  String? _customRtmpUrl;
  String? _customStreamKey;
  String? _customWatchUrl;

  @override
  void initState() {
    super.initState();
    _init();
  }

  Future<void> _init() async {
    await [Permission.camera, Permission.microphone].request();
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
      throw Exception('Screen capture is not available on this device.');
    }
    await RtmpPlatform.startStream(
      rtmpUrl: cred.rtmpUrl,
      streamKey: cred.streamKey,
      overlayUrl: cred.overlayEmbedUrl,
    );
  }

  Future<void> _stopEncoder() async {
    if (Platform.isAndroid && _androidCapture) {
      await RtmpPlatform.stopStream();
    }
  }

  Future<void> _goLive() async {
    setState(() {
      _busy = true;
      _status = 'Starting YouTube stream…';
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
      setState(() => _status = e.toString());
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
              leading: const Icon(Icons.account_circle),
              title: const Text('Use connected YouTube account'),
              subtitle: const Text('Create live event via CricRelay'),
              onTap: () => Navigator.of(ctx).pop('oauth'),
            ),
            ListTile(
              leading: const Icon(Icons.link),
              title: const Text('Use custom RTMP URL / key'),
              subtitle: const Text('Paste YouTube or any RTMP destination'),
              onTap: () => Navigator.of(ctx).pop('custom'),
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
        title: const Text('Custom RTMP destination'),
        content: SingleChildScrollView(
          child: Column(
            mainAxisSize: MainAxisSize.min,
            children: [
              const Text(
                'Option A: paste RTMP server + stream key.\n'
                'Option B: paste full RTMP URL in server field and leave key empty.',
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
    String server = serverCtrl.text.trim();
    String key = keyCtrl.text.trim();
    final watch = watchCtrl.text.trim();
    if (server.isEmpty) {
      setState(() => _status = 'Enter RTMP URL/server first');
      return;
    }
    if (key.isEmpty && server.startsWith('rtmp://') && server.contains('/')) {
      final i = server.lastIndexOf('/');
      if (i > 'rtmp://'.length && i < server.length - 1) {
        key = server.substring(i + 1);
        server = server.substring(0, i);
      }
    }
    if (key.isEmpty) {
      setState(() => _status = 'Stream key missing. Paste full RTMP URL or provide key.');
      return;
    }
    setState(() {
      _useCustomDestination = true;
      _customRtmpUrl = server;
      _customStreamKey = key;
      _customWatchUrl = watch;
      _status = 'Destination: custom RTMP';
    });
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
            icon: const Icon(Icons.settings_input_antenna),
            onPressed: _chooseDestination,
            tooltip: _useCustomDestination ? 'Destination: Custom RTMP' : 'Destination: YouTube OAuth',
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
                Expanded(
                  child: Stack(
                    fit: StackFit.expand,
                    children: [
                      if (camReady) CameraPreview(_camera!),
                      if (_web != null)
                        Positioned(
                          left: 8,
                          right: 8,
                          bottom: 8,
                          height: _androidCapture ? 120 : 96,
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
                      if (_live)
                        const Positioned(
                          top: 12,
                          left: 12,
                          child: _LiveBadge(),
                        ),
                    ],
                  ),
                ),
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
                Expanded(
                  child: Stack(
                    fit: StackFit.expand,
                    children: [
                      if (camReady) CameraPreview(_camera!),
                      if (_web != null)
                        Positioned(
                          left: 8,
                          right: 8,
                          bottom: 8,
                          height: _androidCapture ? 140 : 110,
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
                      if (_live)
                        const Positioned(
                          top: 12,
                          left: 12,
                          child: _LiveBadge(),
                        ),
                    ],
                  ),
                ),
                Material(
                  color: Colors.black87,
                  child: Padding(
                    padding: const EdgeInsets.all(12),
                    child: _ControlPanel(
                      status: _status,
                      live: _live,
                      busy: _busy,
                      camReady: camReady,
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
    required this.onOpenScoring,
    required this.onGoLive,
    required this.onStop,
  });

  final String? status;
  final bool live;
  final bool busy;
  final bool camReady;
  final VoidCallback onOpenScoring;
  final Future<void> Function() onGoLive;
  final Future<void> Function() onStop;

  @override
  Widget build(BuildContext context) {
    return Column(
      crossAxisAlignment: CrossAxisAlignment.stretch,
      children: [
        if (status != null)
          Text(status!, textAlign: TextAlign.center, style: const TextStyle(fontSize: 13)),
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

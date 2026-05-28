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
      final cred = await widget.api.goLive(widget.match.slug);
      await WakelockPlus.enable();
      await _startEncoder(cred);
      if (!mounted) return;
      setState(() {
        _watchUrl = cred.watchUrl;
        _live = true;
        _status = _androidCapture
            ? 'Live on YouTube — scoreboard is burned into the stream'
            : 'Live on YouTube';
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
        await widget.api.stopLive();
      }
    } catch (_) {}
    await WakelockPlus.disable();
    _live = false;
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

  @override
  Widget build(BuildContext context) {
    final camReady = _camera?.value.isInitialized ?? false;
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
          TextButton(
            onPressed: _openScoringMenu,
            child: Text(_scoringLabel, style: const TextStyle(fontSize: 12)),
          ),
        ],
      ),
      body: Column(
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
              child: Column(
                crossAxisAlignment: CrossAxisAlignment.stretch,
                children: [
                  if (_status != null)
                    Text(_status!, textAlign: TextAlign.center, style: const TextStyle(fontSize: 13)),
                  const SizedBox(height: 8),
                  Row(
                    children: [
                      Expanded(
                        child: OutlinedButton.icon(
                          onPressed: _openScoringMenu,
                          icon: const Icon(Icons.scoreboard),
                          label: const Text('Scoring'),
                        ),
                      ),
                      const SizedBox(width: 8),
                      Expanded(
                        flex: 2,
                        child: !_live
                            ? FilledButton.icon(
                                onPressed: (_busy || !camReady) ? null : _goLive,
                                icon: const Icon(Icons.play_arrow),
                                label: const Text('Go Live'),
                              )
                            : FilledButton.tonal(
                                onPressed: _busy ? null : _stop,
                                style: FilledButton.styleFrom(
                                  backgroundColor: Colors.red.shade800,
                                ),
                                child: const Text('Stop'),
                              ),
                      ),
                    ],
                  ),
                ],
              ),
            ),
          ),
        ],
      ),
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

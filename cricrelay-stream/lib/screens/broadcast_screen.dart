import 'dart:async';

import 'package:flutter/material.dart';
import 'package:permission_handler/permission_handler.dart';
import 'package:rtmp_broadcaster/camera.dart';
import 'package:wakelock_plus/wakelock_plus.dart';
import 'package:webview_flutter/webview_flutter.dart';
import 'package:webview_flutter_android/webview_flutter_android.dart';

import '../services/api.dart';
import '../services/rtmp_platform.dart';

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
  GoLiveResult? _credentials;
  bool _useNativeCapture = false;

  @override
  void initState() {
    super.initState();
    _init();
  }

  Future<void> _init() async {
    await [
      Permission.camera,
      Permission.microphone,
    ].request();
    _useNativeCapture = await RtmpPlatform.isCaptureSupported;
    if (!_useNativeCapture) {
      final cams = await availableCameras();
      if (cams.isEmpty) return;
      final back = cams.firstWhere(
        (c) => c.lensDirection == CameraLensDirection.back,
        orElse: () => cams.first,
      );
      _camera = CameraController(back, ResolutionPreset.high, enableAudio: true);
      await _camera!.initialize();
    }
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
    setState(() {
      _web = web;
    });
  }

  @override
  void dispose() {
    _stopInternal();
    _camera?.dispose();
    super.dispose();
  }

  Future<void> _goLive() async {
    setState(() {
      _busy = true;
      _status = 'Creating YouTube broadcast…';
    });
    try {
      final cred = await widget.api.goLive(widget.match.slug);
      setState(() {
        _credentials = cred;
        _watchUrl = cred.watchUrl;
        _status = 'Starting encoder…';
      });
      await WakelockPlus.enable();
      if (_useNativeCapture) {
        await RtmpPlatform.startStream(
          rtmpUrl: cred.rtmpUrl,
          streamKey: cred.streamKey,
          overlayUrl: cred.overlayEmbedUrl,
        );
      } else if (_camera != null) {
        final url = cred.rtmpUrl.endsWith('/')
            ? '${cred.rtmpUrl}${cred.streamKey}'
            : '${cred.rtmpUrl}/${cred.streamKey}';
        await _camera!.startVideoStreaming(url);
      }
      setState(() {
        _live = true;
        _status = 'Live on YouTube';
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
        if (_useNativeCapture) {
          await RtmpPlatform.stopStream();
        } else if (_camera != null && _camera!.value.isStreamingVideoRtmp) {
          await _camera!.stopVideoStreaming();
        }
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

  @override
  Widget build(BuildContext context) {
    final camReady = _useNativeCapture || (_camera?.value.isInitialized ?? false);
    return Scaffold(
      appBar: AppBar(
        title: Text(widget.match.label),
        actions: [
          if (_watchUrl != null)
            IconButton(
              icon: const Icon(Icons.open_in_new),
              onPressed: () {},
              tooltip: _watchUrl,
            ),
        ],
      ),
      body: Column(
        children: [
          Expanded(
            child: Stack(
              fit: StackFit.expand,
              children: [
                if (!_useNativeCapture && camReady)
                  CameraPreview(_camera!),
                if (_useNativeCapture)
                  const ColoredBox(
                    color: Colors.black87,
                    child: Center(
                      child: Text(
                        'Screen capture includes score overlay\n(Point camera at pitch before Go Live)',
                        textAlign: TextAlign.center,
                      ),
                    ),
                  ),
                if (_web != null)
                  Positioned(
                    left: 0,
                    right: 0,
                    bottom: 0,
                    height: 120,
                    child: IgnorePointer(
                      child: WebViewWidget(controller: _web!),
                    ),
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
                  if (_status != null) Text(_status!, textAlign: TextAlign.center),
                  const SizedBox(height: 8),
                  if (!_live)
                    FilledButton(
                      onPressed: (_busy || !camReady) ? null : _goLive,
                      child: const Text('Go Live'),
                    )
                  else
                    FilledButton.tonal(
                      onPressed: _busy ? null : _stop,
                      style: FilledButton.styleFrom(
                        backgroundColor: Colors.red.shade800,
                      ),
                      child: const Text('Stop stream'),
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

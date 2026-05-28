import 'dart:async';

import 'package:flutter/services.dart';

/// Native RTMP with display capture (Android MediaProjection).
class RtmpPlatform {
  static const _ch = MethodChannel('uk.co.cricrelay.stream/rtmp');
  static const _events = EventChannel('uk.co.cricrelay.stream/rtmp_events');

  static Stream<RtmpStreamEvent>? _statusStream;

  static Stream<RtmpStreamEvent> get statusEvents {
    _statusStream ??= _events.receiveBroadcastStream().map((raw) {
      final m = Map<String, dynamic>.from(raw as Map);
      return RtmpStreamEvent(
        event: m['event']?.toString() ?? '',
        message: m['message']?.toString() ?? '',
      );
    });
    return _statusStream!;
  }

  static Future<bool> get isCaptureSupported async {
    try {
      final v = await _ch.invokeMethod<bool>('isCaptureSupported');
      return v == true;
    } catch (_) {
      return false;
    }
  }

  /// Starts capture + RTMP. Returns after screen-capture permission; use [waitForConnected] for YouTube ingest.
  static Future<void> startStream({
    required String rtmpUrl,
    required String streamKey,
    String? overlayUrl,
    int width = 1280,
    int height = 720,
    int bitrateBps = 2500000,
    int fps = 30,
  }) async {
    await _ch.invokeMethod('startStream', {
      'rtmpUrl': rtmpUrl,
      'streamKey': streamKey,
      'overlayUrl': overlayUrl,
      'width': width,
      'height': height,
      'bitrateBps': bitrateBps,
      'fps': fps,
    });
  }

  static Future<void> stopStream() async {
    await _ch.invokeMethod('stopStream');
  }

  /// Wait until RTMP connects or fails (YouTube Studio must be live first).
  static Future<void> waitForConnected({
    Duration timeout = const Duration(seconds: 25),
  }) async {
    final completer = Completer<void>();
    late StreamSubscription<RtmpStreamEvent> sub;
    Timer? timer;

    void finishOk() {
      timer?.cancel();
      if (!completer.isCompleted) completer.complete();
    }

    void finishErr(String msg) {
      timer?.cancel();
      if (!completer.isCompleted) completer.completeError(Exception(msg));
    }

    sub = statusEvents.listen((e) {
      switch (e.event) {
        case 'connected':
          finishOk();
        case 'error':
          finishErr(e.message.isNotEmpty ? e.message : 'Stream connection failed');
        case 'disconnected':
          if (!completer.isCompleted) {
            finishErr('Stream disconnected before going live');
          }
      }
    });

    timer = Timer(timeout, () {
      finishErr(
        'Timed out connecting to YouTube. Start the live in Studio first, then tap Go Live.',
      );
    });

    try {
      await completer.future;
    } finally {
      await sub.cancel();
      timer?.cancel();
    }
  }
}

class RtmpStreamEvent {
  const RtmpStreamEvent({required this.event, required this.message});
  final String event;
  final String message;
}

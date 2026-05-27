import 'package:flutter/services.dart';

/// Native RTMP with display capture (Android MediaProjection) or camera RTMP (iOS fallback).
class RtmpPlatform {
  static const _ch = MethodChannel('uk.co.cricrelay.stream/rtmp');

  static Future<bool> get isCaptureSupported async {
    try {
      final v = await _ch.invokeMethod<bool>('isCaptureSupported');
      return v == true;
    } catch (_) {
      return false;
    }
  }

  static Future<void> startStream({
    required String rtmpUrl,
    required String streamKey,
    String? overlayUrl,
  }) async {
    await _ch.invokeMethod('startStream', {
      'rtmpUrl': rtmpUrl,
      'streamKey': streamKey,
      'overlayUrl': overlayUrl,
    });
  }

  static Future<void> stopStream() async {
    await _ch.invokeMethod('stopStream');
  }
}

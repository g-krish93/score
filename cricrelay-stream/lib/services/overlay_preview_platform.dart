import 'dart:async';
import 'dart:io';

import 'package:flutter/services.dart';

/// Native scoreboard bitmap pushed from [OverlayWebViewCapture] while not streaming.
class OverlayPreviewPlatform {
  static const _events = EventChannel('uk.co.cricrelay.stream/overlay_preview_events');

  static Stream<OverlayPreviewFrame>? _frameStream;

  static Stream<OverlayPreviewFrame> get frames {
    if (!Platform.isAndroid) {
      return const Stream.empty();
    }
    _frameStream ??= _events.receiveBroadcastStream().map((raw) {
      final m = Map<String, dynamic>.from(raw as Map);
      final bytes = m['bytes'];
      return OverlayPreviewFrame(
        pngBytes: bytes is Uint8List ? bytes : Uint8List.fromList(List<int>.from(bytes as List)),
        width: (m['width'] as num?)?.toInt() ?? 0,
        height: (m['height'] as num?)?.toInt() ?? 0,
      );
    });
    return _frameStream!;
  }
}

class OverlayPreviewFrame {
  const OverlayPreviewFrame({
    required this.pngBytes,
    required this.width,
    required this.height,
  });

  final Uint8List pngBytes;
  final int width;
  final int height;
}

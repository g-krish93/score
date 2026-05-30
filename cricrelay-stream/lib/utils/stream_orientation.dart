import 'package:flutter/material.dart';
import 'package:flutter/services.dart';

import '../services/rtmp_platform.dart';

/// App-wide allowed orientations (matches [main.dart] bootstrap).
class StreamOrientationHelper {
  StreamOrientationHelper._();

  static const defaultOrientations = <DeviceOrientation>[
    DeviceOrientation.portraitUp,
    DeviceOrientation.landscapeLeft,
    DeviceOrientation.landscapeRight,
  ];

  static bool isPortrait(BuildContext context) {
    return MediaQuery.orientationOf(context) == Orientation.portrait;
  }

  static String labelFor(BuildContext context) {
    return isPortrait(context) ? 'portrait' : 'landscape';
  }

  static String displayLabel(BuildContext context) {
    return isPortrait(context) ? 'PORTRAIT' : 'LANDSCAPE';
  }

  /// Locks UI + Activity to the current orientation (call after Go Live succeeds).
  static Future<void> lockCurrentOrientation(BuildContext context) async {
    if (isPortrait(context)) {
      await SystemChrome.setPreferredOrientations([DeviceOrientation.portraitUp]);
      await RtmpPlatform.lockActivityOrientation('portrait');
    } else {
      await SystemChrome.setPreferredOrientations([
        DeviceOrientation.landscapeLeft,
        DeviceOrientation.landscapeRight,
      ]);
      await RtmpPlatform.lockActivityOrientation('landscape');
    }
  }

  static Future<void> restoreDefaultOrientations() async {
    await SystemChrome.setPreferredOrientations(defaultOrientations);
    await RtmpPlatform.lockActivityOrientation('unspecified');
  }
}

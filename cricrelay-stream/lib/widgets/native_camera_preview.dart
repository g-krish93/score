import 'package:flutter/foundation.dart';
import 'package:flutter/gestures.dart';
import 'package:flutter/material.dart';
import 'package:flutter/rendering.dart';

/// Native camera preview embedded in Flutter layout (iOS).
///
/// On Android, broadcast uses [RtmpPlatform.showNativePreview] — a full-screen
/// OpenGlView behind transparent Flutter. [AndroidView] / SurfaceProducer cannot
/// display RootEncoder's SurfaceView on Pixel (preview ready but black screen).
class NativeCameraPreview extends StatelessWidget {
  const NativeCameraPreview({super.key});

  static const viewType = 'cricrelay-camera-preview';

  @override
  Widget build(BuildContext context) {
    if (defaultTargetPlatform == TargetPlatform.android) {
      return AndroidView(
        viewType: viewType,
        layoutDirection: TextDirection.ltr,
        gestureRecognizers: const <Factory<OneSequenceGestureRecognizer>>{},
        hitTestBehavior: PlatformViewHitTestBehavior.opaque,
      );
    }
    return const UiKitView(
      viewType: viewType,
      layoutDirection: TextDirection.ltr,
    );
  }
}

import '../models/stream_quality.dart';

/// Stable encoder settings for native RTMP on Android (matches Kotlin caps).
class NativeEncoderProfile {
  NativeEncoderProfile._();

  static const maxWidth = 1280;
  static const maxHeight = 720;

  /// Clamps user quality to what the native encoder prepares once at preview time.
  static StreamQualityProfile forNative(StreamQualityProfile selected) {
    if (selected.width <= maxWidth &&
        selected.height <= maxHeight &&
        selected.bitrateBps <= 2500000) {
      return selected;
    }
    if (selected.width <= 854 && selected.height <= 480) {
      return StreamQualityProfile.medium;
    }
    return StreamQualityProfile.high;
  }

  /// Width, height, and RootEncoder rotation for landscape or portrait hold.
  ///
  /// RootEncoder expects landscape camera dimensions; [rotation] 90 rotates to portrait output.
  static ({int width, int height, int rotation}) paramsForOrientation(
    StreamQualityProfile selected,
    bool isPortrait,
  ) {
    final base = forNative(selected);
    return (
      width: base.width,
      height: base.height,
      rotation: isPortrait ? 90 : 0,
    );
  }
}

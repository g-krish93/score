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

  /// RootEncoder expects landscape camera dimensions; [rotation] rotates output for preview + stream.
  static ({int width, int height, int rotation}) paramsForOrientation(
    StreamQualityProfile selected,
    bool isPortrait,
  ) {
    return paramsFromDisplayRotation(selected, isPortrait ? 0 : 90);
  }

  /// Map Android display rotation (0/90/180/270) to RootEncoder prepareVideo rotation.
  static ({int width, int height, int rotation}) paramsFromDisplayRotation(
    StreamQualityProfile selected,
    int displayRotationDegrees,
  ) {
    final base = forNative(selected);
    final encoderRot = switch (displayRotationDegrees) {
      0 => 90,
      90 => 0,
      180 => 270,
      270 => 180,
      _ => displayRotationDegrees <= 45 || displayRotationDegrees >= 315
          ? 90
          : 0,
    };
    return (width: base.width, height: base.height, rotation: encoderRot);
  }
}

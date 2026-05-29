import 'package:flutter/services.dart';

/// Debug session 0ad848 — traces broadcast open crash (logcat + device file via native).
class DebugTrace {
  DebugTrace._();

  static const _ch = MethodChannel('uk.co.cricrelay.stream/debug');

  static void log(
    String location,
    String message, {
    required String hypothesisId,
    Map<String, Object?> data = const {},
  }) {
    // #region agent log
    _ch.invokeMethod<void>('log', {
      'location': location,
      'message': message,
      'hypothesisId': hypothesisId,
      'data': data,
    }).catchError((_) {});
    // #endregion
  }
}

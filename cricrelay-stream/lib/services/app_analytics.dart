import 'dart:async';

import 'package:firebase_analytics/firebase_analytics.dart';
import 'package:firebase_crashlytics/firebase_crashlytics.dart';
import 'package:flutter/foundation.dart';

/// Minimal product analytics + crash reporting. No PII in event params.
class AppAnalytics {
  AppAnalytics._();

  static bool _enabled = false;
  static FirebaseAnalytics? _analytics;
  static FirebaseCrashlytics? _crashlytics;

  static bool get enabled => _enabled;

  static Future<void> activate({
    required FirebaseAnalytics analytics,
    required FirebaseCrashlytics crashlytics,
  }) async {
    _analytics = analytics;
    _crashlytics = crashlytics;
    _enabled = true;

    FlutterError.onError = (details) {
      FlutterError.presentError(details);
      unawaited(
        crashlytics.recordFlutterFatalError(details),
      );
    };

    PlatformDispatcher.instance.onError = (error, stack) {
      unawaited(
        crashlytics.recordError(error, stack, fatal: true),
      );
      return true;
    };

    await crashlytics.setCrashlyticsCollectionEnabled(!kDebugMode);
    await analytics.setAnalyticsCollectionEnabled(!kDebugMode);
  }

  static Future<void> logEvent(String name, [Map<String, Object>? params]) async {
    if (!_enabled || _analytics == null) return;
    try {
      await _analytics!.logEvent(name: name, parameters: params);
    } catch (_) {}
  }

  static void logBreadcrumb(String message) {
    if (!_enabled || _crashlytics == null) return;
    try {
      _crashlytics!.log(message);
    } catch (_) {}
  }

  /// RTMP status breadcrumbs — never include stream keys or full RTMP URLs.
  static void logRtmpEvent(String event, String message) {
    if (!_enabled || _crashlytics == null) return;
    final safe = _redactSensitive(message);
    try {
      _crashlytics!.log('rtmp:$event $safe');
    } catch (_) {}
  }

  static String _redactSensitive(String raw) {
    var s = raw;
    s = s.replaceAll(RegExp(r'rtmp://[^\s]+', caseSensitive: false), 'rtmp://[REDACTED]');
    s = s.replaceAll(RegExp(r'key[=:]\S+', caseSensitive: false), 'key=[REDACTED]');
    s = s.replaceAll(RegExp(r'stream[_-]?key[=:]\S+', caseSensitive: false), 'stream_key=[REDACTED]');
    return s;
  }
}

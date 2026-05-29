/// User-facing copy for broadcast / RTMP failures (never show raw exceptions).
abstract final class StreamErrorMessages {
  static String fromError(Object error) => fromObject(error);

  static String fromObject(Object error) {
    return fromRaw(error.toString());
  }

  static String fromRaw(String raw) {
    final s = raw.replaceFirst('Exception: ', '').trim();
    if (s.isEmpty) return genericFailure;

    final low = s.toLowerCase();
    if (low.contains('preview not ready') || low.contains('camera preview')) {
      return previewNotReady;
    }
    if (low.contains('offline') ||
        low.contains('no internet') ||
        low.contains('network is unreachable') ||
        low.contains('failed host lookup')) {
      return offline;
    }
    if (low.contains('auth') && (low.contains('reject') || low.contains('denied') || low.contains('fail'))) {
      return authRejected;
    }
    if (low.contains('unauthorized') || low.contains('401')) {
      return authRejected;
    }
    if (low.contains('timeout') ||
        low.contains('timed out') ||
        low.contains('connection refused') ||
        low.contains('could not connect')) {
      return rtmpTimeout;
    }
    if (low.contains('rtmp') && (low.contains('fail') || low.contains('error') || low.contains('disconnect'))) {
      return rtmpTimeout;
    }
    if (s.length > 160) return genericFailure;
    return s;
  }

  static const previewNotReady =
      'Camera preview is not ready yet. Wait until you see the picture, then try again.';

  static const offline =
      'No internet connection. Connect to Wi‑Fi or mobile data, then try again.';

  static const authRejected =
      'Stream key was rejected. Check your RTMP URL and key in YouTube Studio or Twitch, then try again.';

  static const rtmpTimeout =
      'Could not reach the streaming server. Check your connection and stream key, then try again.';

  static const genericFailure = 'Could not start the stream. Check your settings and try again.';
}

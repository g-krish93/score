/// Normalize YouTube Studio RTMP server + stream key pasted by volunteers.
class RtmpEndpoint {
  static String sanitize(String s) =>
      s.trim().replaceAll(RegExp(r'[\u200B-\u200D\uFEFF\r\n]'), '');

  /// Returns server base (e.g. rtmp://a.rtmp.youtube.com/live2) and stream key.
  static ({String server, String key}) parse({
    required String serverInput,
    required String keyInput,
  }) {
    var server = sanitize(serverInput);
    var key = sanitize(keyInput);

    if (server.isEmpty) {
      return (server: '', key: key);
    }

    // Full RTMP URL in server field, key field empty.
    if (key.isEmpty && server.startsWith('rtmp://') && server.contains('/')) {
      final i = server.lastIndexOf('/');
      if (i > 'rtmp://'.length && i < server.length - 1) {
        final tail = server.substring(i + 1);
        if (tail != 'live2' && tail != 'live' && tail.length >= 8) {
          key = tail;
          server = server.substring(0, i);
        }
      }
    }

    server = server.replaceAll(RegExp(r'/+$'), '');

    // Key pasted in both fields (full URL + key again).
    if (key.isNotEmpty && server.endsWith('/$key')) {
      server = server.substring(0, server.length - key.length - 1);
    }

    return (server: server, key: key);
  }

  static String fullUrl(String server, String key) {
    final parsed = parse(serverInput: server, keyInput: key);
    if (parsed.server.isEmpty || parsed.key.isEmpty) return '';
    return '${parsed.server}/${parsed.key}';
  }
}

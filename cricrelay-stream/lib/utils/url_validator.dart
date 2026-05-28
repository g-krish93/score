/// Validates API base URLs — HTTPS required except local dev hosts.
bool isAllowedApiBaseUrl(String raw) {
  final trimmed = raw.trim().replaceAll(RegExp(r'/+$'), '');
  final uri = Uri.tryParse(trimmed);
  if (uri == null || uri.host.isEmpty) return false;

  if (uri.scheme == 'https') return true;

  if (uri.scheme == 'http') {
    final host = uri.host.toLowerCase();
    if (host == 'localhost' || host == '127.0.0.1') return true;
    if (host.startsWith('10.') || host.startsWith('192.168.') || host.endsWith('.local')) {
      return true;
    }
  }
  return false;
}

String normalizeApiBaseUrl(String raw) {
  return raw.trim().replaceAll(RegExp(r'/+$'), '');
}

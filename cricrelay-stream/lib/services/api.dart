import 'dart:convert';

import 'package:http/http.dart' as http;
import 'package:shared_preferences/shared_preferences.dart';

class CricRelayApi {
  CricRelayApi(this.baseUrl);

  final String baseUrl;
  String? _token;

  bool get hasToken => (_token ?? '').isNotEmpty;

  static const _kToken = 'stream_api_token';
  static const _kBase = 'stream_api_base';

  static Future<CricRelayApi> load() async {
    final prefs = await SharedPreferences.getInstance();
    final base = (prefs.getString(_kBase) ?? 'https://cricrelay.co.uk').trim();
    final api = CricRelayApi(base.replaceAll(RegExp(r'/+$'), ''));
    api._token = prefs.getString(_kToken);
    return api;
  }

  Future<void> saveSession(String base, String token) async {
    _token = token;
    final prefs = await SharedPreferences.getInstance();
    await prefs.setString(_kBase, base.replaceAll(RegExp(r'/+$'), ''));
    await prefs.setString(_kToken, token);
  }

  Future<void> clearSession() async {
    _token = null;
    final prefs = await SharedPreferences.getInstance();
    await prefs.remove(_kToken);
  }

  Map<String, String> get _headers => {
        'Content-Type': 'application/json',
        if (_token != null && _token!.isNotEmpty) 'Authorization': 'Bearer $_token',
      };

  Future<Map<String, dynamic>> login(String email, String password) async {
    final res = await http.post(
      Uri.parse('$baseUrl/api/auth/login'),
      headers: {'Content-Type': 'application/json'},
      body: jsonEncode({'email': email, 'password': password}),
    );
    final body = jsonDecode(res.body) as Map<String, dynamic>;
    if (res.statusCode != 200) {
      throw Exception(body['error']?.toString() ?? 'Login failed');
    }
    final token = body['token'] as String;
    await saveSession(baseUrl, token);
    return body;
  }

  Future<List<StreamMatch>> listStreams() async {
    final res = await http.get(
      Uri.parse('$baseUrl/api/streams'),
      headers: _headers,
    );
    final body = jsonDecode(res.body) as Map<String, dynamic>;
    if (res.statusCode != 200) {
      throw Exception(body['error']?.toString() ?? 'Failed to load streams');
    }
    final rows = body['streams'] as List<dynamic>? ?? [];
    return rows.map((e) => StreamMatch.fromJson(e as Map<String, dynamic>)).toList();
  }

  Future<GoLiveResult> goLive(String matchSlug) async {
    final res = await http.post(
      Uri.parse('$baseUrl/api/stream/go-live'),
      headers: _headers,
      body: jsonEncode({'match_slug': matchSlug}),
    );
    final body = jsonDecode(res.body) as Map<String, dynamic>;
    if (res.statusCode != 200) {
      throw Exception(body['error']?.toString() ?? 'Go live failed');
    }
    return GoLiveResult.fromJson(body);
  }

  Future<void> stopLive() async {
    final res = await http.post(
      Uri.parse('$baseUrl/api/stream/stop'),
      headers: _headers,
    );
    if (res.statusCode != 200) {
      final body = jsonDecode(res.body) as Map<String, dynamic>;
      throw Exception(body['error']?.toString() ?? 'Stop failed');
    }
  }

  Future<Map<String, dynamic>> youtubeStatus() async {
    final res = await http.get(
      Uri.parse('$baseUrl/api/stream/youtube-status'),
      headers: _headers,
    );
    return jsonDecode(res.body) as Map<String, dynamic>;
  }
}

class StreamMatch {
  StreamMatch({
    required this.slug,
    required this.label,
    required this.overlayEmbedUrl,
    required this.relaySource,
    required this.paused,
  });

  final String slug;
  final String label;
  final String overlayEmbedUrl;
  final String relaySource;
  final bool paused;

  factory StreamMatch.fromJson(Map<String, dynamic> j) {
    var overlay = (j['overlay_embed_url'] ?? '').toString();
    if (overlay.startsWith('/')) {
      // Caller should prefix with base if needed — fixed in UI
    }
    return StreamMatch(
      slug: (j['slug'] ?? '').toString(),
      label: (j['label'] ?? j['slug'] ?? '').toString(),
      overlayEmbedUrl: overlay,
      relaySource: (j['relay_source'] ?? 'scraper').toString(),
      paused: j['paused'] == true,
    );
  }
}

class GoLiveResult {
  GoLiveResult({
    required this.rtmpUrl,
    required this.streamKey,
    required this.watchUrl,
    required this.overlayEmbedUrl,
  });

  final String rtmpUrl;
  final String streamKey;
  final String watchUrl;
  final String overlayEmbedUrl;

  factory GoLiveResult.fromJson(Map<String, dynamic> j) => GoLiveResult(
        rtmpUrl: (j['rtmp_url'] ?? '').toString(),
        streamKey: (j['stream_key'] ?? '').toString(),
        watchUrl: (j['watch_url'] ?? '').toString(),
        overlayEmbedUrl: (j['overlay_embed_url'] ?? '').toString(),
      );
}

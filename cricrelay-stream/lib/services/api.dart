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
    return rows
        .map((e) => StreamMatch.fromJson(e as Map<String, dynamic>, baseUrl))
        .toList();
  }

  Future<String> youtubeAuthorizeUrl() async {
    final res = await http.get(
      Uri.parse('$baseUrl/api/stream/youtube/authorize'),
      headers: _headers,
    );
    final body = jsonDecode(res.body) as Map<String, dynamic>;
    if (res.statusCode != 200) {
      throw Exception(body['error']?.toString() ?? 'YouTube authorize failed');
    }
    return (body['authorize_url'] ?? '').toString();
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

  Future<ScoringConfig> getScoring(String matchSlug) async {
    final res = await http.get(
      Uri.parse('$baseUrl/api/match/$matchSlug/scoring'),
      headers: _headers,
    );
    final body = jsonDecode(res.body) as Map<String, dynamic>;
    if (res.statusCode != 200) {
      throw Exception(body['error']?.toString() ?? 'Failed to load scoring mode');
    }
    return ScoringConfig.fromJson(body, baseUrl);
  }

  Future<FixturesResponse> listFixtures() async {
    final res = await http.get(
      Uri.parse('$baseUrl/api/fixtures'),
      headers: _headers,
    );
    final body = jsonDecode(res.body) as Map<String, dynamic>;
    if (res.statusCode != 200) {
      throw Exception(body['error']?.toString() ?? 'Failed to load fixtures');
    }
    return FixturesResponse.fromJson(body);
  }

  Future<StreamMatch> createPlayCricketStream({
    required String matchId,
    String label = '',
  }) async {
    final res = await http.post(
      Uri.parse('$baseUrl/api/streams'),
      headers: _headers,
      body: jsonEncode({
        'type': 'play_cricket',
        'play_cricket_match_id': matchId,
        'label': label,
      }),
    );
    final body = jsonDecode(res.body) as Map<String, dynamic>;
    if (res.statusCode != 200) {
      throw Exception(body['error']?.toString() ?? 'Could not create stream');
    }
    final s = body['stream'] as Map<String, dynamic>;
    return StreamMatch.fromJson(s, baseUrl);
  }

  Future<StreamMatch> createPcsBleStream({required String label}) async {
    final res = await http.post(
      Uri.parse('$baseUrl/api/streams'),
      headers: _headers,
      body: jsonEncode({'type': 'pcs_ble', 'label': label}),
    );
    final body = jsonDecode(res.body) as Map<String, dynamic>;
    if (res.statusCode != 200) {
      throw Exception(body['error']?.toString() ?? 'Could not create stream');
    }
    final s = body['stream'] as Map<String, dynamic>;
    return StreamMatch.fromJson(s, baseUrl);
  }

  Future<ScoringConfig> setScoring(String matchSlug, String mode) async {
    final res = await http.post(
      Uri.parse('$baseUrl/api/match/$matchSlug/scoring'),
      headers: _headers,
      body: jsonEncode({'mode': mode}),
    );
    final body = jsonDecode(res.body) as Map<String, dynamic>;
    if (res.statusCode != 200) {
      throw Exception(body['error']?.toString() ?? 'Failed to set scoring mode');
    }
    return ScoringConfig.fromJson(body, baseUrl);
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

  factory StreamMatch.fromJson(Map<String, dynamic> j, [String? baseUrl]) {
    var overlay = (j['overlay_embed_url'] ?? '').toString();
    if (baseUrl != null && overlay.startsWith('/')) {
      final b = baseUrl.replaceAll(RegExp(r'/+$'), '');
      overlay = '$b$overlay';
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

class FixturesResponse {
  FixturesResponse({
    required this.fixtures,
    required this.activeMatchIds,
    this.error,
    this.slotsUsed = 0,
    this.slotsTotal = 6,
  });

  final List<FixtureItem> fixtures;
  final Set<String> activeMatchIds;
  final String? error;
  final int slotsUsed;
  final int slotsTotal;

  factory FixturesResponse.fromJson(Map<String, dynamic> j) {
    final rows = j['fixtures'] as List<dynamic>? ?? [];
    final active = (j['active_match_ids'] as List<dynamic>? ?? [])
        .map((e) => e.toString())
        .toSet();
    return FixturesResponse(
      fixtures: rows
          .map((e) => FixtureItem.fromJson(e as Map<String, dynamic>))
          .toList(),
      activeMatchIds: active,
      error: j['error']?.toString(),
      slotsUsed: int.tryParse('${j['slots_used']}') ?? 0,
      slotsTotal: int.tryParse('${j['slots_total']}') ?? 6,
    );
  }
}

class FixtureItem {
  FixtureItem({required this.matchId, required this.title});

  final String matchId;
  final String title;

  factory FixtureItem.fromJson(Map<String, dynamic> j) => FixtureItem(
        matchId: (j['match_id'] ?? '').toString(),
        title: (j['title'] ?? '').toString(),
      );
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

class ScoringConfig {
  ScoringConfig({
    required this.mode,
    required this.manualInputUrl,
    required this.pcsIngestUrl,
    required this.pcsIngestToken,
    required this.pcsRelayApkUrl,
  });

  final String mode;
  final String manualInputUrl;
  final String pcsIngestUrl;
  final String pcsIngestToken;
  final String pcsRelayApkUrl;

  factory ScoringConfig.fromJson(Map<String, dynamic> j, String baseUrl) {
    String abs(String path) {
      final p = (path).toString();
      if (p.startsWith('http')) return p;
      final b = baseUrl.replaceAll(RegExp(r'/+$'), '');
      return p.startsWith('/') ? '$b$p' : '$b/$p';
    }

    return ScoringConfig(
      mode: (j['mode'] ?? 'manual').toString(),
      manualInputUrl: abs(j['manual_input_url'] ?? ''),
      pcsIngestUrl: abs(j['pcs_ingest_url'] ?? ''),
      pcsIngestToken: (j['pcs_ingest_token'] ?? '').toString(),
      pcsRelayApkUrl: abs(j['pcs_relay_apk_url'] ?? ''),
    );
  }
}

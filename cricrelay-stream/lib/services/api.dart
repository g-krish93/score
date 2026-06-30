import 'dart:convert';

import 'package:http/http.dart' as http;

import '../models/overlay_layout_prefs.dart';
import '../utils/url_validator.dart';
import 'app_analytics.dart';
import 'secure_session.dart';

class CricRelayApi {
  CricRelayApi(this.baseUrl);

  final String baseUrl;
  String? _token;

  bool get hasToken => (_token ?? '').isNotEmpty;

  static Future<CricRelayApi> load() async {
    final base = normalizeApiBaseUrl(await SecureSession.readBaseUrl());
    final api = CricRelayApi(base);
    api._token = await SecureSession.readToken();
    return api;
  }

  Future<void> saveSession(String base, String token) async {
    final normalized = normalizeApiBaseUrl(base);
    if (!isAllowedApiBaseUrl(normalized)) {
      throw Exception('Server URL must use HTTPS (http only for local testing).');
    }
    _token = token;
    await SecureSession.writeBaseUrl(normalized);
    await SecureSession.writeToken(token);
  }

  Future<void> clearSession() async {
    _token = null;
    await SecureSession.clearToken();
  }

  Map<String, String> get _headers => {
        'Content-Type': 'application/json',
        'Accept': 'application/json',
        if (_token != null && _token!.isNotEmpty) 'Authorization': 'Bearer $_token',
      };

  Uri _matchApiUri(String matchSlug, String suffix) {
    final slug = Uri.encodeComponent(matchSlug);
    return Uri.parse('$baseUrl/api/match/$slug/$suffix');
  }

  Map<String, dynamic> _parseJsonResponse(http.Response res, {String fallback = 'Request failed'}) {
    final raw = res.body.trim();
    final ct = (res.headers['content-type'] ?? '').toLowerCase();
    final looksHtml = raw.toLowerCase().startsWith('<!doctype') ||
        raw.toLowerCase().startsWith('<html') ||
        (!ct.contains('json') && raw.startsWith('<'));

    if (looksHtml) {
      if (res.statusCode == 401) {
        throw Exception('Session expired — log out and sign in again.');
      }
      if (res.statusCode == 404) {
        throw Exception(
          'API not found on $baseUrl (404). The server may need updating, or this stream no longer exists.',
        );
      }
      throw Exception(
        'Server returned a web page instead of JSON (${res.statusCode}) from $baseUrl. '
        'Check the club server URL on the login screen and sign in again.',
      );
    }

    try {
      final decoded = jsonDecode(raw);
      if (decoded is Map<String, dynamic>) return decoded;
      if (decoded is Map) return Map<String, dynamic>.from(decoded);
      throw const FormatException('not a JSON object');
    } on FormatException {
      throw Exception(
        'Invalid server response (${res.statusCode}). ${raw.length > 120 ? '${raw.substring(0, 120)}…' : raw}',
      );
    }
  }

  Future<Map<String, dynamic>> login(String email, String password) async {
    if (!isAllowedApiBaseUrl(baseUrl)) {
      throw Exception('Server URL must use HTTPS (http only for local testing).');
    }
    final res = await http.post(
      Uri.parse('$baseUrl/api/auth/login'),
      headers: {'Content-Type': 'application/json'},
      body: jsonEncode({'email': email, 'password': password}),
    );
    final body = _parseJsonResponse(res, fallback: 'Login failed');
    if (res.statusCode != 200) {
      throw Exception(body['error']?.toString() ?? 'Login failed');
    }
    final token = body['token'] as String;
    await saveSession(baseUrl, token);
    await AppAnalytics.logEvent('login_success');
    return body;
  }

  Future<StreamAppBuilds> getAppBuilds() async {
    final res = await http.get(
      Uri.parse('$baseUrl/api/stream/app-builds'),
      headers: _headers,
    );
    final body = _parseJsonResponse(res, fallback: 'Failed to load app downloads');
    if (res.statusCode != 200) {
      throw Exception(body['error']?.toString() ?? 'Failed to load app downloads');
    }
    return StreamAppBuilds.fromJson(body);
  }

  Future<List<StreamMatch>> listStreams() async {
    final res = await http.get(
      Uri.parse('$baseUrl/api/streams'),
      headers: _headers,
    );
    final body = _parseJsonResponse(res, fallback: 'Failed to load streams');
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
    final body = _parseJsonResponse(res, fallback: 'YouTube authorize failed');
    if (res.statusCode != 200) {
      throw Exception(body['error']?.toString() ?? 'YouTube authorize failed');
    }
    return (body['authorize_url'] ?? '').toString();
  }

  Future<GoLiveResult> goLive(String matchSlug, {String platform = 'youtube'}) async {
    final res = await http.post(
      Uri.parse('$baseUrl/api/stream/go-live'),
      headers: _headers,
      body: jsonEncode({'match_slug': matchSlug, 'platform': platform}),
    );
    final body = _parseJsonResponse(res, fallback: 'Go live failed');
    if (res.statusCode != 200) {
      throw Exception(body['error']?.toString() ?? 'Go live failed');
    }
    return GoLiveResult.fromJson(body);
  }

  Future<void> stopLive({String? platform}) async {
    final body = <String, dynamic>{};
    if (platform != null && platform.isNotEmpty) {
      body['platform'] = platform;
    }
    final res = await http.post(
      Uri.parse('$baseUrl/api/stream/stop'),
      headers: _headers,
      body: body.isEmpty ? null : jsonEncode(body),
    );
    if (res.statusCode != 200) {
      final body = _parseJsonResponse(res, fallback: 'Stop failed');
      throw Exception(body['error']?.toString() ?? 'Stop failed');
    }
  }

  Future<Map<String, dynamic>> youtubeStatus() async {
    final res = await http.get(
      Uri.parse('$baseUrl/api/stream/youtube-status'),
      headers: _headers,
    );
    return _parseJsonResponse(res, fallback: 'YouTube status failed');
  }

  Future<void> youtubeDisconnect() async {
    final res = await http.post(
      Uri.parse('$baseUrl/api/stream/youtube-disconnect'),
      headers: _headers,
    );
    if (res.statusCode != 200) {
      final body = _parseJsonResponse(res, fallback: 'Disconnect failed');
      throw Exception(body['error']?.toString() ?? 'Disconnect failed');
    }
  }

  Future<String> twitchAuthorizeUrl() async {
    final res = await http.get(
      Uri.parse('$baseUrl/api/stream/twitch/authorize'),
      headers: _headers,
    );
    final body = _parseJsonResponse(res, fallback: 'Twitch authorize failed');
    if (res.statusCode != 200) {
      throw Exception(body['error']?.toString() ?? 'Twitch authorize failed');
    }
    return (body['authorize_url'] ?? '').toString();
  }

  Future<Map<String, dynamic>> twitchStatus() async {
    final res = await http.get(
      Uri.parse('$baseUrl/api/stream/twitch-status'),
      headers: _headers,
    );
    return _parseJsonResponse(res, fallback: 'Twitch status failed');
  }

  Future<void> twitchDisconnect() async {
    final res = await http.post(
      Uri.parse('$baseUrl/api/stream/twitch-disconnect'),
      headers: _headers,
    );
    if (res.statusCode != 200) {
      final body = _parseJsonResponse(res, fallback: 'Disconnect failed');
      throw Exception(body['error']?.toString() ?? 'Disconnect failed');
    }
  }

  Future<ScoringConfig> getScoring(String matchSlug) async {
    final res = await http.get(
      _matchApiUri(matchSlug, 'scoring'),
      headers: _headers,
    );
    final body = _parseJsonResponse(res, fallback: 'Failed to load scoring mode');
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
    final body = _parseJsonResponse(res, fallback: 'Failed to load fixtures');
    if (res.statusCode != 200) {
      throw Exception(body['error']?.toString() ?? 'Failed to load fixtures');
    }
    return FixturesResponse.fromJson(body);
  }

  Future<StreamMatch> createPlayCricketStream({
    required String matchId,
    String label = '',
  }) async {
    return createPlayCricketStreamWithOptions(
      matchId: matchId,
      label: label,
    );
  }

  Future<StreamMatch> createPlayCricketStreamWithOptions({
    required String matchId,
    String label = '',
    String playCricketBaseUrl = '',
  }) async {
    final res = await http.post(
      Uri.parse('$baseUrl/api/streams'),
      headers: _headers,
      body: jsonEncode({
        'type': 'play_cricket',
        'play_cricket_match_id': matchId,
        'label': label,
        if (playCricketBaseUrl.isNotEmpty) 'play_cricket_base_url': playCricketBaseUrl,
      }),
    );
    final body = _parseJsonResponse(res, fallback: 'Could not create stream');
    if (res.statusCode != 200) {
      throw Exception(body['error']?.toString() ?? 'Could not create stream');
    }
    final s = body['stream'] as Map<String, dynamic>;
    final match = StreamMatch.fromJson(s, baseUrl);
    await AppAnalytics.logEvent('stream_created');
    return match;
  }

  Future<StreamMatch> createCricHeroesStreamWithOptions({
    required String matchUrl,
    String label = '',
  }) async {
    final res = await http.post(
      Uri.parse('$baseUrl/api/streams'),
      headers: _headers,
      body: jsonEncode({
        'type': 'cricheroes',
        'match_url': matchUrl,
        'label': label,
      }),
    );
    final body = _parseJsonResponse(res, fallback: 'Could not create stream');
    if (res.statusCode != 200) {
      throw Exception(body['error']?.toString() ?? 'Could not create stream');
    }
    final s = body['stream'] as Map<String, dynamic>;
    final match = StreamMatch.fromJson(s, baseUrl);
    await AppAnalytics.logEvent('stream_created');
    return match;
  }

  Future<Map<String, dynamic>> getOverlayPrefs(String matchSlug) async {
    final res = await http.get(
      _matchApiUri(matchSlug, 'overlay'),
      headers: _headers,
    );
    final body = _parseJsonResponse(res, fallback: 'Failed to load overlay settings');
    if (res.statusCode != 200) {
      throw Exception(body['error']?.toString() ?? 'Failed to load overlay settings');
    }
    return body;
  }

  Future<Map<String, dynamic>> setOverlayPrefs(String matchSlug, OverlayLayoutPrefs prefs) async {
    final res = await http.post(
      _matchApiUri(matchSlug, 'overlay'),
      headers: _headers,
      body: jsonEncode(prefs.toServerJson()),
    );
    final body = _parseJsonResponse(res, fallback: 'Failed to save overlay settings');
    if (res.statusCode != 200) {
      throw Exception(body['error']?.toString() ?? 'Failed to save overlay settings');
    }
    return body;
  }

  Future<ScoringConfig> setScoring(String matchSlug, String mode, {String? provider}) async {
    try {
      final res = await http.post(
        _matchApiUri(matchSlug, 'scoring'),
        headers: _headers,
        body: jsonEncode({
          'mode': mode,
          if (provider != null && provider.isNotEmpty) 'provider': provider,
        }),
      );
      final body = _parseJsonResponse(res, fallback: 'Failed to set scoring mode');
      if (res.statusCode != 200) {
        throw Exception(body['error']?.toString() ?? 'Failed to set scoring mode');
      }
      return ScoringConfig.fromJson(body, baseUrl);
    } catch (e) {
      final msg = e.toString();
      final htmlLike = msg.contains('web page instead of JSON') || msg.contains('Invalid server response');
      if (htmlLike && mode == 'manual') {
        return ScoringConfig.localFallback(baseUrl, matchSlug, mode);
      }
      rethrow;
    }
  }

  Future<MatchDayStatus> getMatchDayStatus(String matchSlug) async {
    final res = await http.get(
      _matchApiUri(matchSlug, 'match-day'),
      headers: _headers,
    );
    final body = _parseJsonResponse(res, fallback: 'Failed to load match status');
    if (res.statusCode != 200) {
      throw Exception(body['error']?.toString() ?? 'Failed to load match status');
    }
    return MatchDayStatus.fromJson(body);
  }

  Future<void> updateBroadcastStatus(
    String matchSlug, {
    required String status,
    String? platform,
    String? watchUrl,
  }) async {
    final res = await http.post(
      _matchApiUri(matchSlug, 'broadcast-status'),
      headers: _headers,
      body: jsonEncode({
        'status': status,
        if (platform != null) 'platform': platform,
        if (watchUrl != null) 'watch_url': watchUrl,
      }),
    );
    if (res.statusCode != 200) {
      final body = _parseJsonResponse(res, fallback: 'Failed to update broadcast status');
      throw Exception(body['error']?.toString() ?? 'Failed to update broadcast status');
    }
  }

  Future<void> renameStream(String matchSlug, String label) async {
    final slug = Uri.encodeComponent(matchSlug);
    final res = await http.patch(
      Uri.parse('$baseUrl/api/streams/$slug'),
      headers: _headers,
      body: jsonEncode({'label': label}),
    );
    if (res.statusCode != 200) {
      final body = _parseJsonResponse(res, fallback: 'Failed to rename stream');
      throw Exception(body['error']?.toString() ?? 'Failed to rename stream');
    }
  }

  Future<void> deleteStream(String matchSlug) async {
    final slug = Uri.encodeComponent(matchSlug);
    final res = await http.delete(
      Uri.parse('$baseUrl/api/streams/$slug'),
      headers: _headers,
    );
    if (res.statusCode != 200) {
      final body = _parseJsonResponse(res, fallback: 'Failed to delete stream');
      throw Exception(body['error']?.toString() ?? 'Failed to delete stream');
    }
  }

  Future<void> setRelayPause(String matchSlug, {required bool paused}) async {
    final res = await http.post(
      _matchApiUri(matchSlug, 'relay-pause'),
      headers: _headers,
      body: jsonEncode({'paused': paused}),
    );
    if (res.statusCode != 200) {
      final body = _parseJsonResponse(res, fallback: 'Failed to update relay pause');
      throw Exception(body['error']?.toString() ?? 'Failed to update relay pause');
    }
  }
}

class BroadcastStatus {
  const BroadcastStatus({
    this.status = 'idle',
    this.platform,
    this.watchUrl,
  });

  final String status;
  final String? platform;
  final String? watchUrl;

  bool get isStreaming => status == 'streaming';
  bool get isPaused => status == 'paused';

  factory BroadcastStatus.fromJson(Map<String, dynamic>? j) {
    if (j == null) return BroadcastStatus();
    return BroadcastStatus(
      status: (j['status'] ?? 'idle').toString(),
      platform: j['platform']?.toString(),
      watchUrl: j['watch_url']?.toString(),
    );
  }
}

class StreamMatch {
  StreamMatch({
    required this.slug,
    required this.label,
    required this.overlayEmbedUrl,
    required this.relaySource,
    required this.relayPaused,
    this.scoringMode = 'manual',
    this.scoringActive = false,
    this.scoringStale = false,
    this.isLive = false,
    this.broadcast = const BroadcastStatus(),
  });

  final String slug;
  final String label;
  final String overlayEmbedUrl;
  final String relaySource;
  final bool relayPaused;
  final String scoringMode;
  final bool scoringActive;
  final bool scoringStale;
  final bool isLive;
  final BroadcastStatus broadcast;

  bool get paused => relayPaused;

  factory StreamMatch.fromJson(Map<String, dynamic> j, [String? baseUrl]) {
    var overlay = (j['overlay_embed_url'] ?? '').toString();
    if (baseUrl != null && overlay.startsWith('/')) {
      final b = baseUrl.replaceAll(RegExp(r'/+$'), '');
      overlay = '$b$overlay';
    }
    final broadcastRaw = j['broadcast'];
    return StreamMatch(
      slug: (j['slug'] ?? '').toString(),
      label: (j['label'] ?? j['slug'] ?? '').toString(),
      overlayEmbedUrl: overlay,
      relaySource: (j['relay_source'] ?? 'scraper').toString(),
      relayPaused: j['paused'] == true || j['relay_paused'] == true,
      scoringMode: (j['scoring_mode'] ?? 'manual').toString(),
      scoringActive: j['scoring_active'] == true,
      scoringStale: j['scoring_stale'] == true,
      isLive: j['is_live'] == true || j['live'] == true,
      broadcast: BroadcastStatus.fromJson(
        broadcastRaw is Map ? Map<String, dynamic>.from(broadcastRaw) : null,
      ),
    );
  }
}

class MatchDayStatus {
  MatchDayStatus({
    required this.slug,
    required this.label,
    required this.scoringMode,
    required this.scoringActive,
    required this.scoringStale,
    required this.relayPaused,
    required this.broadcast,
    this.manualScorerUrl = '',
  });

  final String slug;
  final String label;
  final String scoringMode;
  final bool scoringActive;
  final bool scoringStale;
  final bool relayPaused;
  final BroadcastStatus broadcast;
  final String manualScorerUrl;

  factory MatchDayStatus.fromJson(Map<String, dynamic> j) {
    final broadcastRaw = j['broadcast'];
    return MatchDayStatus(
      slug: (j['slug'] ?? '').toString(),
      label: (j['label'] ?? '').toString(),
      scoringMode: (j['scoring_mode'] ?? 'manual').toString(),
      scoringActive: j['scoring_active'] == true,
      scoringStale: j['scoring_stale'] == true,
      relayPaused: j['relay_paused'] == true || j['paused'] == true,
      broadcast: BroadcastStatus.fromJson(
        broadcastRaw is Map ? Map<String, dynamic>.from(broadcastRaw) : null,
      ),
      manualScorerUrl: (j['manual_scorer_url'] ?? '').toString(),
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

class StreamAppBuilds {
  StreamAppBuilds({
    required this.version,
    required this.android,
    required this.ios,
  });

  final String version;
  final StreamAppPlatformBuild android;
  final StreamAppPlatformBuild ios;

  factory StreamAppBuilds.fromJson(Map<String, dynamic> j) {
    return StreamAppBuilds(
      version: (j['version'] ?? '').toString(),
      android: StreamAppPlatformBuild.fromJson(
        Map<String, dynamic>.from(j['android'] as Map? ?? {}),
      ),
      ios: StreamAppPlatformBuild.fromJson(
        Map<String, dynamic>.from(j['ios'] as Map? ?? {}),
      ),
    );
  }
}

class StreamAppPlatformBuild {
  StreamAppPlatformBuild({
    required this.available,
    this.url,
    this.otaInstallUrl,
    this.label = '',
    this.installNote = '',
    this.streamingNote = '',
  });

  final bool available;
  final String? url;
  final String? otaInstallUrl;
  final String label;
  final String installNote;
  final String streamingNote;

  factory StreamAppPlatformBuild.fromJson(Map<String, dynamic> j) {
    return StreamAppPlatformBuild(
      available: j['available'] == true,
      url: j['url']?.toString(),
      otaInstallUrl: j['ota_install_url']?.toString(),
      label: (j['label'] ?? '').toString(),
      installNote: (j['install_note'] ?? '').toString(),
      streamingNote: (j['streaming_note'] ?? '').toString(),
    );
  }
}

class ScoringConfig {
  ScoringConfig({
    required this.mode,
    required this.manualInputUrl,
    required this.manualScorerUrl,
    required this.pcsIngestUrl,
    required this.pcsIngestToken,
    required this.pcsRelayApkUrl,
  });

  final String mode;
  final String manualInputUrl;
  final String manualScorerUrl;
  final String pcsIngestUrl;
  final String pcsIngestToken;
  final String pcsRelayApkUrl;

  String get scorerUrl => manualScorerUrl.isNotEmpty ? manualScorerUrl : manualInputUrl.replaceAll('/input', '/score');

  /// When the scoring API is unreachable, still expose scorer URLs for manual mode.
  factory ScoringConfig.localFallback(String baseUrl, String matchSlug, String mode) {
    final b = baseUrl.replaceAll(RegExp(r'/+$'), '');
    final slug = matchSlug.trim();
    return ScoringConfig(
      mode: mode,
      manualInputUrl: '$b/m/$slug/input',
      manualScorerUrl: '$b/m/$slug/score',
      pcsIngestUrl: '$b/relay/pcs-ingest?match=$slug',
      pcsIngestToken: '',
      pcsRelayApkUrl: '$b/download/pcs-relay.apk',
    );
  }

  factory ScoringConfig.fromJson(Map<String, dynamic> j, String baseUrl) {
    String abs(String path) {
      final p = (path).toString();
      if (p.startsWith('http')) return p;
      final b = baseUrl.replaceAll(RegExp(r'/+$'), '');
      return p.startsWith('/') ? '$b$p' : '$b/$p';
    }

    final inputUrl = abs(j['manual_input_url'] ?? '');
    final scorerUrl = abs(j['manual_scorer_url'] ?? '');
    return ScoringConfig(
      mode: (j['mode'] ?? 'manual').toString(),
      manualInputUrl: inputUrl,
      manualScorerUrl: scorerUrl.isNotEmpty
          ? scorerUrl
          : inputUrl.replaceAll('/input', '/score'),
      pcsIngestUrl: abs(j['pcs_ingest_url'] ?? ''),
      pcsIngestToken: (j['pcs_ingest_token'] ?? '').toString(),
      pcsRelayApkUrl: abs(j['pcs_relay_apk_url'] ?? ''),
    );
  }
}

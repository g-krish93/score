import 'dart:convert';

import 'package:shared_preferences/shared_preferences.dart';

import '../models/overlay_layout_prefs.dart';
import 'api.dart';

const _kPrefix = 'overlay_layout_';

class OverlayLayoutStore {
  OverlayLayoutStore(this.api, this.matchSlug);

  final CricRelayApi api;
  final String matchSlug;

  String get _key => '$_kPrefix$matchSlug';

  Future<OverlayLayoutPrefs> load() async {
    final prefs = await SharedPreferences.getInstance();
    OverlayLayoutPrefs local = const OverlayLayoutPrefs();
    final raw = prefs.getString(_key);
    if (raw != null && raw.isNotEmpty) {
      try {
        local = OverlayLayoutPrefs.fromJson(
          Map<String, dynamic>.from(jsonDecode(raw) as Map),
        );
      } catch (_) {}
    }
    try {
      final remote = await api.getOverlayPrefs(matchSlug);
      return OverlayLayoutPrefs(
        size: (int.tryParse('${remote['overlay_size']}') ?? local.size).clamp(1, 5),
        theme: (remote['theme'] ?? local.theme).toString(),
        density: (remote['overlay_density'] ?? local.density).toString(),
        heightFraction: local.heightFraction,
        bottomMargin: local.bottomMargin,
        horizontalInset: local.horizontalInset,
        keepScreenOn: local.keepScreenOn,
      );
    } catch (_) {
      return local;
    }
  }

  Future<void> saveLocal(OverlayLayoutPrefs p) async {
    final prefs = await SharedPreferences.getInstance();
    await prefs.setString(_key, jsonEncode(p.toLocalJson()));
  }

  Future<OverlayLayoutPrefs> saveAndSync(OverlayLayoutPrefs p) async {
    await saveLocal(p);
    final remote = await api.setOverlayPrefs(matchSlug, p);
    return OverlayLayoutPrefs(
      size: (int.tryParse('${remote['overlay_size']}') ?? p.size).clamp(1, 5),
      theme: (remote['theme'] ?? p.theme).toString(),
      density: (remote['overlay_density'] ?? p.density).toString(),
      heightFraction: p.heightFraction,
      bottomMargin: p.bottomMargin,
      horizontalInset: p.horizontalInset,
      keepScreenOn: p.keepScreenOn,
    );
  }

  String embedUrl(String baseOverlayUrl, OverlayLayoutPrefs p) {
    final uri = Uri.parse(baseOverlayUrl);
    final q = Map<String, String>.from(uri.queryParameters);
    q['embed'] = '1';
    q['overlay_size'] = '${p.size}';
    return uri.replace(queryParameters: q).toString();
  }
}

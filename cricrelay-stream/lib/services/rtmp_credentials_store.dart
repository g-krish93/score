import 'package:flutter_secure_storage/flutter_secure_storage.dart';
import 'package:shared_preferences/shared_preferences.dart';

/// Per-match RTMP URL, key, and watch link in encrypted storage.
class RtmpCredentialsStore {
  RtmpCredentialsStore(this.matchSlug);

  final String matchSlug;

  static const _storage = FlutterSecureStorage(
    aOptions: AndroidOptions(encryptedSharedPreferences: true),
  );

  String _fieldKey(String field) => 'rtmp_${field}_$matchSlug';

  String _legacyPrefsKey(String field) => 'rtmp_${field}_$matchSlug';

  Future<({String? server, String? key, String? watch})> load() async {
    var server = await _storage.read(key: _fieldKey('server'));
    var key = await _storage.read(key: _fieldKey('key'));
    var watch = await _storage.read(key: _fieldKey('watch'));

    if ((server ?? '').isNotEmpty && (key ?? '').isNotEmpty) {
      return (server: server, key: key, watch: watch);
    }

    final prefs = await SharedPreferences.getInstance();
    server = prefs.getString(_legacyPrefsKey('server'));
    key = prefs.getString(_legacyPrefsKey('key'));
    watch = prefs.getString(_legacyPrefsKey('watch'));
    if ((server ?? '').isNotEmpty && (key ?? '').isNotEmpty) {
      await save(server: server!, key: key!, watch: watch);
      await prefs.remove(_legacyPrefsKey('server'));
      await prefs.remove(_legacyPrefsKey('key'));
      await prefs.remove(_legacyPrefsKey('watch'));
    }
    return (server: server, key: key, watch: watch);
  }

  Future<void> save({required String server, required String key, String? watch}) async {
    await _storage.write(key: _fieldKey('server'), value: server);
    await _storage.write(key: _fieldKey('key'), value: key);
    if (watch != null && watch.isNotEmpty) {
      await _storage.write(key: _fieldKey('watch'), value: watch);
    } else {
      await _storage.delete(key: _fieldKey('watch'));
    }
  }

  Future<void> clear() async {
    await _storage.delete(key: _fieldKey('server'));
    await _storage.delete(key: _fieldKey('key'));
    await _storage.delete(key: _fieldKey('watch'));
  }
}

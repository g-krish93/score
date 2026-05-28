import 'package:flutter_secure_storage/flutter_secure_storage.dart';
import 'package:shared_preferences/shared_preferences.dart';

/// Bearer token in encrypted storage; base URL in prefs (not secret).
class SecureSession {
  SecureSession._();

  static const _storage = FlutterSecureStorage(
    aOptions: AndroidOptions(encryptedSharedPreferences: true),
  );

  static const _kToken = 'stream_api_token_secure';
  static const _kLegacyToken = 'stream_api_token';
  static const _kBase = 'stream_api_base';

  static Future<String?> readToken() async {
    var token = await _storage.read(key: _kToken);
    if (token != null && token.isNotEmpty) return token;

    final prefs = await SharedPreferences.getInstance();
    final legacy = prefs.getString(_kLegacyToken);
    if (legacy != null && legacy.isNotEmpty) {
      await _storage.write(key: _kToken, value: legacy);
      await prefs.remove(_kLegacyToken);
      return legacy;
    }
    return null;
  }

  static Future<void> writeToken(String token) async {
    await _storage.write(key: _kToken, value: token);
    final prefs = await SharedPreferences.getInstance();
    await prefs.remove(_kLegacyToken);
  }

  static Future<void> clearToken() async {
    await _storage.delete(key: _kToken);
    final prefs = await SharedPreferences.getInstance();
    await prefs.remove(_kLegacyToken);
  }

  static Future<String> readBaseUrl({String fallback = 'https://cricrelay.co.uk'}) async {
    final prefs = await SharedPreferences.getInstance();
    return (prefs.getString(_kBase) ?? fallback).trim();
  }

  static Future<void> writeBaseUrl(String base) async {
    final prefs = await SharedPreferences.getInstance();
    await prefs.setString(_kBase, base);
  }
}

import 'dart:io';

import 'package:shared_preferences/shared_preferences.dart';

import '../models/stream_quality.dart';
import '../services/rtmp_platform.dart';
import '../widgets/stream_settings_sheet.dart';

/// Applies phone-appropriate defaults on first run (Android tiers).
class DeviceProfile {
  const DeviceProfile({
    required this.tier,
    required this.lowRam,
    required this.suggestedQuality,
    required this.defaultEis,
    required this.powerSave,
  });

  final String tier;
  final bool lowRam;
  final String suggestedQuality;
  final bool defaultEis;
  final bool powerSave;

  bool get isLowTier => tier == 'low' || lowRam;

  static Future<DeviceProfile?> loadAndroid() async {
    if (!Platform.isAndroid) return null;
    try {
      final raw = await RtmpPlatform.getDeviceCapabilities();
      return DeviceProfile(
        tier: (raw['tier'] ?? 'high').toString(),
        lowRam: raw['lowRam'] == true,
        suggestedQuality: (raw['suggestedQuality'] ?? 'high').toString(),
        defaultEis: raw['defaultEis'] != false,
        powerSave: raw['powerSave'] == true,
      );
    } catch (_) {
      return null;
    }
  }

  /// Pick stream quality from device tier when the volunteer has not chosen one yet.
  static Future<StreamQualityProfile> resolveInitialQuality() async {
    final saved = await loadStreamQualityProfile();
    final prefs = await SharedPreferences.getInstance();
    if (prefs.containsKey(kStreamQualityPref)) return saved;

    final profile = await loadAndroid();
    if (profile == null) return saved;
    return StreamQualityProfile.fromId(profile.suggestedQuality);
  }
}

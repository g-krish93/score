/// RTMP encoder presets — lower bitrate for weak mobile data at the ground.
enum StreamQualityPreset {
  low,
  medium,
  high,
  max,
}

class StreamQualityProfile {
  const StreamQualityProfile({
    required this.id,
    required this.label,
    required this.hint,
    required this.width,
    required this.height,
    required this.bitrateBps,
    required this.fps,
  });

  final String id;
  final String label;
  final String hint;
  final int width;
  final int height;
  final int bitrateBps;
  final int fps;

  static const low = StreamQualityProfile(
    id: 'low',
    label: 'Low',
    hint: 'Weak signal / patchy data (~0.8 Mbps)',
    width: 640,
    height: 360,
    bitrateBps: 800000,
    fps: 24,
  );

  static const medium = StreamQualityProfile(
    id: 'medium',
    label: 'Medium',
    hint: 'Average mobile data (~1.5 Mbps)',
    width: 854,
    height: 480,
    bitrateBps: 1500000,
    fps: 30,
  );

  static const high = StreamQualityProfile(
    id: 'high',
    label: 'High',
    hint: 'Good 4G / 5G (~2.5 Mbps)',
    width: 1280,
    height: 720,
    bitrateBps: 2500000,
    fps: 30,
  );

  static const max = StreamQualityProfile(
    id: 'max',
    label: 'Max',
    hint: 'Strong Wi‑Fi only (~4.5 Mbps)',
    width: 1920,
    height: 1080,
    bitrateBps: 4500000,
    fps: 30,
  );

  static List<StreamQualityProfile> get all => [low, medium, high, max];

  static StreamQualityProfile fromId(String? raw) {
    switch (raw) {
      case 'low':
        return low;
      case 'medium':
        return medium;
      case 'max':
        return max;
      case 'high':
      default:
        return high;
    }
  }

  static StreamQualityPreset presetFromId(String? raw) {
    switch (raw) {
      case 'low':
        return StreamQualityPreset.low;
      case 'medium':
        return StreamQualityPreset.medium;
      case 'max':
        return StreamQualityPreset.max;
      default:
        return StreamQualityPreset.high;
    }
  }

  static StreamQualityProfile profileFor(StreamQualityPreset p) => switch (p) {
        StreamQualityPreset.low => low,
        StreamQualityPreset.medium => medium,
        StreamQualityPreset.high => high,
        StreamQualityPreset.max => max,
      };
}

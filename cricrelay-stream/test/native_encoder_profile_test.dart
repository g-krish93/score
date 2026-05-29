import 'package:cricrelay_stream/models/stream_quality.dart';
import 'package:cricrelay_stream/utils/native_encoder_profile.dart';
import 'package:flutter_test/flutter_test.dart';

void main() {
  test('forNative caps 1080p to 720p high preset', () {
    final capped = NativeEncoderProfile.forNative(StreamQualityProfile.max);
    expect(capped.width, 1280);
    expect(capped.height, 720);
  });

  test('forNative keeps medium preset', () {
    final capped = NativeEncoderProfile.forNative(StreamQualityProfile.medium);
    expect(capped.width, 854);
    expect(capped.height, 480);
  });
}

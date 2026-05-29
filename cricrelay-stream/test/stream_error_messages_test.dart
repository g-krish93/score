import 'package:cricrelay_stream/utils/stream_error_messages.dart';
import 'package:flutter_test/flutter_test.dart';

void main() {
  group('StreamErrorMessages', () {
    test('maps RTMP timeout to friendly copy', () {
      expect(
        StreamErrorMessages.fromError(Exception('RTMP connection timed out')),
        StreamErrorMessages.rtmpTimeout,
      );
    });

    test('maps auth rejection to stream key guidance', () {
      expect(
        StreamErrorMessages.fromError(Exception('Auth rejected by server')),
        StreamErrorMessages.authRejected,
      );
    });

    test('maps preview and offline errors', () {
      expect(
        StreamErrorMessages.fromError(Exception('Camera preview not ready')),
        StreamErrorMessages.previewNotReady,
      );
      expect(
        StreamErrorMessages.fromError(Exception('Network unavailable')),
        StreamErrorMessages.offline,
      );
    });

    test('strips Exception prefix from unknown short errors', () {
      expect(
        StreamErrorMessages.fromError(Exception('Encoder failed to start')),
        'Encoder failed to start',
      );
    });
  });
}

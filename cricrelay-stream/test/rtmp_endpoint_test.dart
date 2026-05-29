import 'package:cricrelay_stream/utils/rtmp_endpoint.dart';
import 'package:flutter_test/flutter_test.dart';

void main() {
  group('RtmpEndpoint', () {
    test('splits full RTMP URL pasted in server field', () {
      const full = 'rtmp://a.rtmp.youtube.com/live2/abcd-efgh-12345678';
      final parsed = RtmpEndpoint.parse(serverInput: full, keyInput: '');
      expect(parsed.server, 'rtmp://a.rtmp.youtube.com/live2');
      expect(parsed.key, 'abcd-efgh-12345678');
    });

    test('strips duplicate key suffix from server', () {
      const key = 'abcd-efgh-12345678';
      final parsed = RtmpEndpoint.parse(
        serverInput: 'rtmp://a.rtmp.youtube.com/live2/$key',
        keyInput: key,
      );
      expect(parsed.server, 'rtmp://a.rtmp.youtube.com/live2');
      expect(parsed.key, key);
    });

    test('builds publish URL from server and key', () {
      expect(
        RtmpEndpoint.fullUrl('rtmp://a.rtmp.youtube.com/live2', 'abcd-efgh-12345678'),
        'rtmp://a.rtmp.youtube.com/live2/abcd-efgh-12345678',
      );
      expect(RtmpEndpoint.fullUrl('', 'key'), '');
    });
  });
}

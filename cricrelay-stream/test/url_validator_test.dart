import 'package:cricrelay_stream/utils/url_validator.dart';
import 'package:flutter_test/flutter_test.dart';

void main() {
  group('isAllowedApiBaseUrl', () {
    test('accepts HTTPS club servers', () {
      expect(isAllowedApiBaseUrl('https://cricrelay.co.uk'), isTrue);
      expect(isAllowedApiBaseUrl('https://club.example.com/api/'), isTrue);
    });

    test('rejects plain HTTP except local dev hosts', () {
      expect(isAllowedApiBaseUrl('http://cricrelay.co.uk'), isFalse);
      expect(isAllowedApiBaseUrl('http://localhost:5000'), isTrue);
      expect(isAllowedApiBaseUrl('http://127.0.0.1'), isTrue);
      expect(isAllowedApiBaseUrl('http://192.168.1.10'), isTrue);
      expect(isAllowedApiBaseUrl(''), isFalse);
      expect(isAllowedApiBaseUrl('not-a-url'), isFalse);
    });
  });

  group('normalizeApiBaseUrl', () {
    test('trims whitespace and trailing slashes', () {
      expect(normalizeApiBaseUrl('  https://cricrelay.co.uk///  '), 'https://cricrelay.co.uk');
    });
  });
}

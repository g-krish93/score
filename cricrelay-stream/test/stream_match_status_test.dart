import 'package:cricrelay_stream/services/api.dart';
import 'package:flutter_test/flutter_test.dart';

void main() {
  group('StreamMatch enriched status', () {
    test('parses broadcast and scoring fields', () {
      final m = StreamMatch.fromJson({
        'slug': 'club-123',
        'label': '1st XI vs Rivals',
        'overlay_embed_url': 'https://x/m/club-123/stream?embed=1',
        'relay_source': 'scraper',
        'paused': false,
        'scoring_mode': 'manual',
        'scoring_active': true,
        'scoring_stale': false,
        'is_live': false,
        'broadcast': {
          'status': 'streaming',
          'platform': 'youtube',
          'watch_url': 'https://youtube.com/watch?v=abc',
        },
      });

      expect(m.slug, 'club-123');
      expect(m.scoringMode, 'manual');
      expect(m.scoringActive, isTrue);
      expect(m.broadcast.isStreaming, isTrue);
      expect(m.broadcast.platform, 'youtube');
    });

    test('defaults broadcast to idle', () {
      final m = StreamMatch.fromJson({
        'slug': 'club-456',
        'label': 'Test',
        'overlay_embed_url': '/m/club-456/stream?embed=1',
        'relay_source': 'scraper',
        'paused': true,
      }, 'https://club.example');

      expect(m.relayPaused, isTrue);
      expect(m.broadcast.status, 'idle');
      expect(m.overlayEmbedUrl, startsWith('https://club.example'));
    });
  });

  group('MatchDayStatus', () {
    test('parses aggregated match-day payload', () {
      final d = MatchDayStatus.fromJson({
        'slug': 'club-123',
        'label': 'Match',
        'scoring_mode': 'auto',
        'scoring_active': false,
        'scoring_stale': true,
        'relay_paused': true,
        'broadcast': {'status': 'paused'},
        'manual_scorer_url': 'https://x/m/club-123/score',
      });

      expect(d.scoringStale, isTrue);
      expect(d.relayPaused, isTrue);
      expect(d.broadcast.isPaused, isTrue);
      expect(d.manualScorerUrl, contains('/score'));
    });
  });
}

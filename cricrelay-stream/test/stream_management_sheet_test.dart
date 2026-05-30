import 'package:cricrelay_stream/services/api.dart';
import 'package:cricrelay_stream/theme/app_theme.dart';
import 'package:cricrelay_stream/widgets/stream_management_sheet.dart';
import 'package:flutter/material.dart';
import 'package:flutter_test/flutter_test.dart';

class _FakeApi extends CricRelayApi {
  _FakeApi() : super('https://club.example');

  @override
  Future<void> renameStream(String matchSlug, String label) async {}

  @override
  Future<void> setRelayPause(String matchSlug, {required bool paused}) async {}

  @override
  Future<void> deleteStream(String matchSlug) async {}
}

void main() {
  testWidgets('stream management shows auto-sync pause copy', (tester) async {
    final match = StreamMatch(
      slug: 'club-1',
      label: 'Test match',
      overlayEmbedUrl: 'https://club.example/m/club-1/stream?embed=1',
      relaySource: 'scraper',
      relayPaused: false,
      scoringMode: 'auto',
    );

    await tester.pumpWidget(
      MaterialApp(
        theme: buildAppTheme(),
        home: Scaffold(
          body: Builder(
            builder: (ctx) => FilledButton(
              onPressed: () => showStreamManagementSheet(
                context: ctx,
                api: _FakeApi(),
                match: match,
              ),
              child: const Text('open'),
            ),
          ),
        ),
      ),
    );

    await tester.tap(find.text('open'));
    await tester.pumpAndSettle();

    expect(find.text('Stream settings'), findsOneWidget);
    expect(find.textContaining('Play-Cricket sync'), findsOneWidget);
    expect(find.text('Delete stream'), findsOneWidget);
  });
}

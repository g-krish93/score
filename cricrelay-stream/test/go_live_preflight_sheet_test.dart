import 'package:cricrelay_stream/theme/app_theme.dart';
import 'package:cricrelay_stream/widgets/go_live_preflight_sheet.dart';
import 'package:flutter/material.dart';
import 'package:flutter_test/flutter_test.dart';

void main() {
  Widget wrap(GoLivePreflightSheetContent child) {
    return MaterialApp(
      theme: buildAppTheme().copyWith(textTheme: appTextTheme),
      home: Scaffold(body: child),
    );
  }

  group('GoLivePreflightSheetContent', () {
    testWidgets('Go Live disabled when camera or network checks fail', (tester) async {
      await tester.pumpWidget(
        wrap(
          GoLivePreflightSheetContent(
            initial: const GoLivePreflightResult(
              cameraReady: false,
              streamKeySet: true,
              networkOk: true,
              overlayLocked: false,
              orientationLabel: 'landscape',
            ),
          ),
        ),
      );

      final goLive = tester.widget<FilledButton>(find.byType(FilledButton));
      expect(goLive.onPressed, isNull);
    });

    testWidgets('Go Live allowed in portrait — landscape is optional advice', (tester) async {
      await tester.pumpWidget(
        wrap(
          GoLivePreflightSheetContent(
            initial: const GoLivePreflightResult(
              cameraReady: true,
              streamKeySet: true,
              networkOk: true,
              overlayLocked: false,
              orientationLabel: 'portrait',
            ),
          ),
        ),
      );

      final goLive = tester.widget<FilledButton>(find.byType(FilledButton));
      expect(goLive.onPressed, isNotNull);
      expect(find.textContaining('Landscape recommended'), findsOneWidget);
    });

    testWidgets('overlay lock is optional and does not block Go Live', (tester) async {
      await tester.pumpWidget(
        wrap(
          GoLivePreflightSheetContent(
            initial: const GoLivePreflightResult(
              cameraReady: true,
              streamKeySet: true,
              networkOk: true,
              overlayLocked: false,
              orientationLabel: 'landscape',
            ),
          ),
        ),
      );

      expect(find.textContaining('Optional'), findsOneWidget);
      expect(tester.widget<FilledButton>(find.byType(FilledButton)).onPressed, isNotNull);
    });
  });
}

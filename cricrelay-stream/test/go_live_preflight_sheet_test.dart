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
            ),
          ),
        ),
      );

      final goLive = tester.widget<FilledButton>(
        find.widgetWithText(FilledButton, 'Go Live'),
      );
      expect(goLive.onPressed, isNull);
    });

    testWidgets('Go Live enabled when critical checks pass', (tester) async {
      await tester.pumpWidget(
        wrap(
          GoLivePreflightSheetContent(
            initial: const GoLivePreflightResult(
              cameraReady: true,
              streamKeySet: true,
              networkOk: true,
              overlayLocked: false,
            ),
          ),
        ),
      );

      final goLive = tester.widget<FilledButton>(
        find.widgetWithText(FilledButton, 'Go Live'),
      );
      expect(goLive.onPressed, isNotNull);
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
            ),
          ),
        ),
      );

      expect(find.textContaining('Recommended'), findsOneWidget);
      expect(
        tester.widget<FilledButton>(find.widgetWithText(FilledButton, 'Go Live')).onPressed,
        isNotNull,
      );
    });
  });
}

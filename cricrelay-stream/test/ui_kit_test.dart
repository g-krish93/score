import 'package:cricrelay_stream/theme/app_theme.dart';
import 'package:cricrelay_stream/widgets/ui_kit.dart';
import 'package:flutter/material.dart';
import 'package:flutter_test/flutter_test.dart';

void main() {
  Widget wrap(Widget child) {
    return MaterialApp(
      theme: buildAppTheme().copyWith(textTheme: appTextTheme),
      home: Scaffold(body: child),
    );
  }

  group('CrGoLiveButton pre-flight disabled states', () {
    testWidgets('Go live button disabled when checks fail', (tester) async {
      var tapped = false;
      await tester.pumpWidget(
        wrap(
          CrGoLiveButton(
            live: false,
            busy: false,
            enabled: false,
            onGoLive: () => tapped = true,
            onStop: () {},
          ),
        ),
      );

      expect(tester.widget<FilledButton>(find.byType(FilledButton)).onPressed, isNull);
      await tester.tap(find.text('Go live'));
      await tester.pump();
      expect(tapped, isFalse);
    });

    testWidgets('Go live button disabled while busy', (tester) async {
      await tester.pumpWidget(
        wrap(
          CrGoLiveButton(
            live: false,
            busy: true,
            enabled: true,
            onGoLive: () {},
            onStop: () {},
          ),
        ),
      );

      expect(tester.widget<FilledButton>(find.byType(FilledButton)).onPressed, isNull);
      expect(find.text('Starting…'), findsOneWidget);
    });
  });

  group('CrStatusChip checklist states', () {
    testWidgets('shows warning when a pre-flight check fails', (tester) async {
      await tester.pumpWidget(
        wrap(const CrStatusChip(label: 'Stream key set', ok: false)),
      );

      expect(find.byIcon(Icons.warning_amber_rounded), findsOneWidget);
    });
  });
}

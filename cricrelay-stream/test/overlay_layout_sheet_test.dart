import 'package:cricrelay_stream/theme/app_theme.dart';
import 'package:cricrelay_stream/widgets/overlay_layout_sheet.dart';
import 'package:flutter/material.dart';
import 'package:flutter_test/flutter_test.dart';

import 'package:cricrelay_stream/models/overlay_layout_prefs.dart';

void main() {
  testWidgets('overlay width slider is bound and updates label', (tester) async {
    await tester.pumpWidget(
      MaterialApp(
        theme: buildAppTheme().copyWith(textTheme: appTextTheme),
        home: Builder(
          builder: (context) {
            return Scaffold(
              body: Center(
                child: ElevatedButton(
                  onPressed: () => showOverlayLayoutSheet(
                    context: context,
                    initial: const OverlayLayoutPrefs(widthFraction: 0.5),
                  ),
                  child: const Text('open'),
                ),
              ),
            );
          },
        ),
      ),
    );

    await tester.tap(find.text('open'));
    await tester.pumpAndSettle();

    expect(find.text('Preview width: 50%'), findsOneWidget);
    expect(find.byType(Slider), findsWidgets);
  });
}

import 'package:cricrelay_stream/theme/app_theme.dart';
import 'package:cricrelay_stream/widgets/scoring_mode_sheet.dart';
import 'package:flutter/material.dart';
import 'package:flutter_test/flutter_test.dart';

void main() {
  testWidgets('manual scoring link card shows copyable URL for teammates', (tester) async {
    var copied = false;
    await tester.pumpWidget(
      MaterialApp(
        theme: buildAppTheme().copyWith(textTheme: appTextTheme),
        home: Scaffold(
          body: ManualScorerLinkCard(
            url: 'https://club.cricrelay.co.uk/m/my-match/input',
            onCopy: () => copied = true,
            onOpenHere: () {},
          ),
        ),
      ),
    );

    expect(find.text('Copy scorer link'), findsOneWidget);
    expect(find.textContaining('club.cricrelay.co.uk/m/my-match/input'), findsOneWidget);
    expect(find.textContaining('2nd or 3rd phone'), findsOneWidget);
    await tester.tap(find.text('Copy scorer link'));
    expect(copied, isTrue);
  });
}

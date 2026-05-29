import 'package:flutter/material.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:shared_preferences/shared_preferences.dart';

import 'package:cricrelay_stream/screens/live_home_screen.dart';
import 'package:cricrelay_stream/screens/onboarding_screen.dart';
import 'package:cricrelay_stream/services/api.dart';

void main() {
  TestWidgetsFlutterBinding.ensureInitialized();

  testWidgets('Skip opens home using onboarding context', (tester) async {
    SharedPreferences.setMockInitialValues({});
    final api = CricRelayApi('https://cricrelay.co.uk');

    await tester.pumpWidget(
      MaterialApp(home: OnboardingScreen(api: api)),
    );

    await tester.tap(find.text('Skip'));
    await tester.pumpAndSettle();

    expect(find.byType(LiveHomeScreen), findsOneWidget);
  });

  testWidgets('Get started opens home after last step', (tester) async {
    SharedPreferences.setMockInitialValues({});
    final api = CricRelayApi('https://cricrelay.co.uk');

    await tester.pumpWidget(
      MaterialApp(home: OnboardingScreen(api: api)),
    );

    await tester.tap(find.text('Next'));
    await tester.pumpAndSettle();
    await tester.tap(find.text('Next'));
    await tester.pumpAndSettle();
    await tester.tap(find.text('Get started'));
    await tester.pumpAndSettle();

    expect(find.byType(LiveHomeScreen), findsOneWidget);
  });
}

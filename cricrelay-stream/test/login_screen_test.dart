import 'package:cricrelay_stream/screens/login_screen.dart';
import 'package:cricrelay_stream/services/api.dart';
import 'package:cricrelay_stream/theme/app_theme.dart';
import 'package:flutter/material.dart';
import 'package:flutter_test/flutter_test.dart';

void main() {
  Widget buildLogin(CricRelayApi api) {
    return MaterialApp(
      theme: buildAppTheme().copyWith(textTheme: appTextTheme),
      home: LoginScreen(api: api),
    );
  }

  group('LoginScreen validation', () {
    testWidgets('shows errors when email and password are empty', (tester) async {
      await tester.pumpWidget(buildLogin(CricRelayApi('https://cricrelay.co.uk')));
      await tester.pumpAndSettle();

      await tester.tap(find.widgetWithText(FilledButton, 'Sign in'));
      await tester.pumpAndSettle();

      expect(find.text('Enter your club email'), findsOneWidget);
      expect(find.text('Enter your password'), findsOneWidget);
    });

    testWidgets('rejects non-HTTPS server URLs', (tester) async {
      await tester.pumpWidget(buildLogin(CricRelayApi('https://cricrelay.co.uk')));
      await tester.pumpAndSettle();

      await tester.enterText(find.byType(TextFormField).at(0), 'http://evil.example.com');
      await tester.enterText(find.byType(TextFormField).at(1), 'volunteer@club.test');
      await tester.enterText(find.byType(TextFormField).at(2), 'secret');
      await tester.tap(find.widgetWithText(FilledButton, 'Sign in'));
      await tester.pumpAndSettle();

      expect(find.text('HTTPS required (http only for local dev)'), findsOneWidget);
    });
  });
}

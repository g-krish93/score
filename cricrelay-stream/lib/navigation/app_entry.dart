import 'package:flutter/material.dart';

import '../screens/live_home_screen.dart';
import '../screens/login_screen.dart';
import '../screens/onboarding_screen.dart';
import '../services/api.dart';

/// After login or cold start with a saved session — home or first-run onboarding.
Future<void> openHomeOrOnboarding(BuildContext context, CricRelayApi api) async {
  final onboardingDone = await isOnboardingComplete();
  if (!context.mounted) return;
  if (!onboardingDone) {
    Navigator.of(context).pushReplacement(
      MaterialPageRoute(builder: (_) => OnboardingScreen(api: api)),
    );
    return;
  }
  Navigator.of(context).pushReplacement(
    MaterialPageRoute(builder: (_) => LiveHomeScreen(api: api)),
  );
}

/// Resolves the first screen when the app launches with a stored token.
Future<Widget> bootstrapHome(CricRelayApi api) async {
  if (!api.hasToken) return LoginScreen(api: api);
  final onboardingDone = await isOnboardingComplete();
  if (!onboardingDone) return OnboardingScreen(api: api);
  return LiveHomeScreen(api: api);
}

import 'package:flutter/material.dart';
import 'package:shared_preferences/shared_preferences.dart';

import '../services/api.dart';
import '../theme/app_theme.dart';
import 'live_home_screen.dart';

const kOnboardingCompleteKey = 'stream_onboarding_complete_v1';

Future<bool> isOnboardingComplete() async {
  final prefs = await SharedPreferences.getInstance();
  return prefs.getBool(kOnboardingCompleteKey) == true;
}

Future<void> markOnboardingComplete() async {
  final prefs = await SharedPreferences.getInstance();
  await prefs.setBool(kOnboardingCompleteKey, true);
}

/// First-run 3-step carousel for volunteers.
class OnboardingScreen extends StatefulWidget {
  const OnboardingScreen({super.key, required this.api});

  final CricRelayApi api;

  @override
  State<OnboardingScreen> createState() => _OnboardingScreenState();
}

class _OnboardingScreenState extends State<OnboardingScreen> {
  final _pageCtrl = PageController();
  int _page = 0;
  bool _finishing = false;

  static const _steps = [
    _OnboardingStep(
      icon: Icons.settings_input_antenna,
      title: 'Paste your stream key',
      body:
          'On the broadcast screen, tap the antenna icon and paste the RTMP URL and key from YouTube Studio or Twitch. No Google login needed on match day.',
    ),
    _OnboardingStep(
      icon: Icons.lock_outline,
      title: 'Lock the scoreboard overlay',
      body:
          'Adjust overlay size if needed, then lock it so preview touches do not move the scoreboard while you film.',
    ),
    _OnboardingStep(
      icon: Icons.sensors,
      title: 'Go live when ready',
      body:
          'Wait for the camera preview, run the pre-flight checklist, then tap Go Live. Keep the phone plugged in and on a stable connection.',
    ),
  ];

  @override
  void dispose() {
    _pageCtrl.dispose();
    super.dispose();
  }

  Future<void> _finish() async {
    if (_finishing) return;
    _finishing = true;
    try {
      await markOnboardingComplete();
    } catch (_) {}
    if (!mounted) return;
    Navigator.of(context).pushReplacement(
      MaterialPageRoute(builder: (_) => LiveHomeScreen(api: widget.api)),
    );
  }

  void _next() {
    if (_page < _steps.length - 1) {
      _pageCtrl.nextPage(
        duration: const Duration(milliseconds: 300),
        curve: Curves.easeOut,
      );
      return;
    }
    _finish();
  }

  @override
  Widget build(BuildContext context) {
    return Scaffold(
      backgroundColor: AppColors.background,
      body: SafeArea(
        child: Column(
          children: [
            Align(
              alignment: Alignment.centerRight,
              child: TextButton(
                onPressed: _finishing ? null : _finish,
                child: const Text('Skip'),
              ),
            ),
            Expanded(
              child: PageView.builder(
                controller: _pageCtrl,
                itemCount: _steps.length,
                onPageChanged: (i) => setState(() => _page = i),
                itemBuilder: (_, i) => _StepPage(step: _steps[i]),
              ),
            ),
            Row(
              mainAxisAlignment: MainAxisAlignment.center,
              children: List.generate(
                _steps.length,
                (i) => Container(
                  width: 8,
                  height: 8,
                  margin: const EdgeInsets.symmetric(horizontal: 4),
                  decoration: BoxDecoration(
                    shape: BoxShape.circle,
                    color: i == _page ? AppColors.primary : AppColors.surfaceVariant,
                  ),
                ),
              ),
            ),
            const SizedBox(height: AppSpacing.lg),
            Padding(
              padding: const EdgeInsets.fromLTRB(AppSpacing.lg, 0, AppSpacing.lg, AppSpacing.lg),
              child: SizedBox(
                width: double.infinity,
                height: 48,
                child: FilledButton(
                  onPressed: _finishing ? null : _next,
                  child: Text(_page == _steps.length - 1 ? 'Get started' : 'Next'),
                ),
              ),
            ),
          ],
        ),
      ),
    );
  }
}

class _OnboardingStep {
  const _OnboardingStep({
    required this.icon,
    required this.title,
    required this.body,
  });

  final IconData icon;
  final String title;
  final String body;
}

class _StepPage extends StatelessWidget {
  const _StepPage({required this.step});

  final _OnboardingStep step;

  @override
  Widget build(BuildContext context) {
    return Padding(
      padding: const EdgeInsets.symmetric(horizontal: AppSpacing.lg),
      child: Column(
        mainAxisAlignment: MainAxisAlignment.center,
        children: [
          Container(
            width: 88,
            height: 88,
            decoration: BoxDecoration(
              color: AppColors.surface,
              borderRadius: BorderRadius.circular(20),
              border: Border.all(color: AppColors.border),
            ),
            child: Icon(step.icon, size: 44, color: AppColors.accentGreen),
          ),
          const SizedBox(height: AppSpacing.xl),
          Text(step.title, style: appTextTheme.headlineSmall, textAlign: TextAlign.center),
          const SizedBox(height: AppSpacing.md),
          Text(step.body, style: appTextTheme.bodyLarge, textAlign: TextAlign.center),
        ],
      ),
    );
  }
}

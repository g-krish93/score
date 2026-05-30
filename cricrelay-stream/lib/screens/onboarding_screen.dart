import 'package:flutter/material.dart';
import 'package:shared_preferences/shared_preferences.dart';

import '../services/api.dart';
import '../theme/app_theme.dart';
import '../widgets/studio/studio_shell.dart';
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
      icon: Icons.settings_input_antenna_rounded,
      title: 'Paste your stream key',
      body:
          'On the broadcast screen, tap Destination and paste the RTMP URL and key from YouTube Studio or Twitch. No Google login needed on match day.',
      accent: AppColors.accentBlue,
    ),
    _OnboardingStep(
      icon: Icons.layers_outlined,
      title: 'Position & lock overlay',
      body:
          'Drag the scoreboard to the right spot, resize if needed, then lock it so touches do not move it while you film.',
      accent: AppColors.accent,
    ),
    _OnboardingStep(
      icon: Icons.sensors_rounded,
      title: 'Go live when ready',
      body:
          'Wait for the camera preview, run the pre-flight checklist, then tap Go Live. Keep the phone plugged in on a stable connection.',
      accent: AppColors.primary,
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
        duration: AppMotion.normal,
        curve: AppMotion.curve,
      );
      return;
    }
    _finish();
  }

  @override
  Widget build(BuildContext context) {
    return Scaffold(
      body: CrStudioBackdrop(
        child: SafeArea(
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
                  itemBuilder: (_, i) => _StepPage(step: _steps[i], index: i + 1, total: _steps.length),
                ),
              ),
              Row(
                mainAxisAlignment: MainAxisAlignment.center,
                children: List.generate(
                  _steps.length,
                  (i) => AnimatedContainer(
                    duration: AppMotion.fast,
                    curve: AppMotion.curve,
                    width: i == _page ? 24 : 8,
                    height: 8,
                    margin: const EdgeInsets.symmetric(horizontal: 4),
                    decoration: BoxDecoration(
                      borderRadius: BorderRadius.circular(AppSpacing.radiusPill),
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
                  height: 52,
                  child: FilledButton(
                    onPressed: _finishing ? null : _next,
                    child: Text(_page == _steps.length - 1 ? 'Enter studio' : 'Continue'),
                  ),
                ),
              ),
            ],
          ),
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
    required this.accent,
  });

  final IconData icon;
  final String title;
  final String body;
  final Color accent;
}

class _StepPage extends StatelessWidget {
  const _StepPage({required this.step, required this.index, required this.total});

  final _OnboardingStep step;
  final int index;
  final int total;

  @override
  Widget build(BuildContext context) {
    return Padding(
      padding: const EdgeInsets.symmetric(horizontal: AppSpacing.lg),
      child: Column(
        mainAxisAlignment: MainAxisAlignment.center,
        children: [
          Text(
            'STEP $index OF $total',
            style: const TextStyle(
              fontSize: 11,
              fontWeight: FontWeight.w800,
              letterSpacing: 1.4,
              color: AppColors.onBackgroundDim,
            ),
          ),
          const SizedBox(height: AppSpacing.lg),
          CrGlassPanel(
            padding: const EdgeInsets.all(28),
            borderRadius: AppSpacing.radiusXl,
            child: Icon(step.icon, size: 52, color: step.accent),
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

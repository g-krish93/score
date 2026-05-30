import 'package:flutter/material.dart';
import 'package:shared_preferences/shared_preferences.dart';

import '../models/stream_destination.dart';
import '../services/api.dart';
import '../theme/app_theme.dart';
import 'ui_kit.dart';

/// First-run guided setup per stream slug.
Future<void> maybeShowMatchDayWizard({
  required BuildContext context,
  required CricRelayApi api,
  required String matchSlug,
  required String matchLabel,
  required StreamDestination destination,
  required VoidCallback onOpenDestination,
  required VoidCallback onOpenScoring,
  required VoidCallback onOpenOverlay,
}) async {
  final prefs = await SharedPreferences.getInstance();
  final key = 'match_day_wizard_done_$matchSlug';
  if (prefs.getBool(key) == true) return;
  if (!context.mounted) return;

  await showCrBottomSheet<void>(
    context: context,
    child: _MatchDayWizardBody(
      matchLabel: matchLabel,
      onComplete: () async {
        await prefs.setBool(key, true);
      },
      onOpenDestination: () {
        Navigator.pop(context);
        onOpenDestination();
      },
      onOpenScoring: () {
        Navigator.pop(context);
        onOpenScoring();
      },
      onOpenOverlay: () {
        Navigator.pop(context);
        onOpenOverlay();
      },
    ),
  );
}

class _MatchDayWizardBody extends StatefulWidget {
  const _MatchDayWizardBody({
    required this.matchLabel,
    required this.onComplete,
    required this.onOpenDestination,
    required this.onOpenScoring,
    required this.onOpenOverlay,
  });

  final String matchLabel;
  final Future<void> Function() onComplete;
  final VoidCallback onOpenDestination;
  final VoidCallback onOpenScoring;
  final VoidCallback onOpenOverlay;

  @override
  State<_MatchDayWizardBody> createState() => _MatchDayWizardBodyState();
}

class _MatchDayWizardBodyState extends State<_MatchDayWizardBody> {
  int _step = 0;

  static const _steps = [
    ('Landscape', 'Hold the phone sideways for cricket. You can set up in portrait, but Go Live requires landscape.'),
    ('Destination', 'Paste your YouTube or Twitch stream key, or use a connected club channel.'),
    ('Scoring', 'Pick Auto (Play-Cricket), Manual (teammate scorer link), or BLE (advanced).'),
    ('Overlay', 'Drag the scoreboard frame on the preview. Lock it when positioned.'),
  ];

  Future<void> _finish() async {
    await widget.onComplete();
    if (mounted) Navigator.pop(context);
  }

  @override
  Widget build(BuildContext context) {
    final (title, body) = _steps[_step];
    return Padding(
      padding: const EdgeInsets.fromLTRB(AppSpacing.lg, 0, AppSpacing.lg, AppSpacing.lg),
      child: Column(
        mainAxisSize: MainAxisSize.min,
        crossAxisAlignment: CrossAxisAlignment.stretch,
        children: [
          const CrBottomSheetHandle(),
          Text('Match day setup', style: appTextTheme.headlineSmall),
          Text(widget.matchLabel, style: appTextTheme.bodySmall),
          const SizedBox(height: AppSpacing.md),
          Text('Step ${_step + 1} of ${_steps.length}: $title', style: appTextTheme.titleSmall),
          const SizedBox(height: AppSpacing.sm),
          Text(body, style: appTextTheme.bodyMedium),
          const SizedBox(height: AppSpacing.lg),
          if (_step == 1)
            OutlinedButton(onPressed: widget.onOpenDestination, child: const Text('Set destination')),
          if (_step == 2)
            OutlinedButton(onPressed: widget.onOpenScoring, child: const Text('Choose scoring mode')),
          if (_step == 3)
            OutlinedButton(onPressed: widget.onOpenOverlay, child: const Text('Position overlay')),
          const SizedBox(height: AppSpacing.md),
          Row(
            children: [
              TextButton(
                onPressed: _finish,
                child: const Text('Skip'),
              ),
              const Spacer(),
              if (_step > 0)
                TextButton(
                  onPressed: () => setState(() => _step--),
                  child: const Text('Back'),
                ),
              FilledButton(
                onPressed: () async {
                  if (_step >= _steps.length - 1) {
                    await _finish();
                  } else {
                    setState(() => _step++);
                  }
                },
                child: Text(_step >= _steps.length - 1 ? 'Done' : 'Next'),
              ),
            ],
          ),
        ],
      ),
    );
  }
}

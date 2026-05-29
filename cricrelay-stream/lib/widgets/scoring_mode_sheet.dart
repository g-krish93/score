import 'package:flutter/material.dart';
import 'package:flutter/services.dart';

import '../screens/manual_scoring_screen.dart';
import '../services/api.dart';
import '../theme/app_theme.dart';
import 'ui_kit.dart';

Future<void> showScoringModeSheet({
  required BuildContext context,
  required CricRelayApi api,
  required String matchSlug,
  required ScoringConfig initial,
  required void Function(ScoringConfig) onUpdated,
}) async {
  await showCrBottomSheet<void>(
    context: context,
    child: _ScoringModeBody(
      api: api,
      matchSlug: matchSlug,
      initial: initial,
      onUpdated: onUpdated,
    ),
  );
}

class _ScoringModeBody extends StatefulWidget {
  const _ScoringModeBody({
    required this.api,
    required this.matchSlug,
    required this.initial,
    required this.onUpdated,
  });

  final CricRelayApi api;
  final String matchSlug;
  final ScoringConfig initial;
  final void Function(ScoringConfig) onUpdated;

  @override
  State<_ScoringModeBody> createState() => _ScoringModeBodyState();
}

class _ScoringModeBodyState extends State<_ScoringModeBody> {
  late ScoringConfig _cfg;
  bool _busy = false;
  String? _error;

  @override
  void initState() {
    super.initState();
    _cfg = widget.initial;
  }

  Future<void> _pick(String mode) async {
    setState(() {
      _busy = true;
      _error = null;
    });
    try {
      final next = await widget.api.setScoring(widget.matchSlug, mode);
      widget.onUpdated(next);
      if (!mounted) return;
      setState(() => _cfg = next);
      if (mode == 'manual' && next.manualInputUrl.isNotEmpty) {
        Navigator.pop(context);
        Navigator.of(context).push(
          MaterialPageRoute(
            builder: (_) => ManualScoringScreen(inputUrl: next.manualInputUrl),
          ),
        );
      }
    } catch (e) {
      setState(() => _error = e.toString().replaceFirst('Exception: ', ''));
    } finally {
      if (mounted) setState(() => _busy = false);
    }
  }

  @override
  Widget build(BuildContext context) {
    return SafeArea(
      child: Padding(
        padding: EdgeInsets.only(
          left: AppSpacing.md,
          right: AppSpacing.md,
          bottom: AppSpacing.md + MediaQuery.of(context).padding.bottom,
        ),
        child: Column(
          mainAxisSize: MainAxisSize.min,
          crossAxisAlignment: CrossAxisAlignment.stretch,
          children: [
            const CrBottomSheetHandle(),
            const CrSheetHeader(
              title: 'Scoring while live',
              subtitle: 'Choose how the scoreboard on your stream is updated.',
            ),
            if (_error != null) ...[
              CrErrorBanner(message: _error!),
              const SizedBox(height: AppSpacing.sm),
            ],
            _ModeTile(
              title: 'Auto',
              subtitle: 'Play-Cricket scraper (hands-off)',
              selected: _cfg.mode == 'auto',
              busy: _busy,
              onTap: () => _pick('auto'),
            ),
            _ModeTile(
              title: 'Manual',
              subtitle: 'Over-by-over scoring in CricRelay',
              selected: _cfg.mode == 'manual',
              busy: _busy,
              onTap: () => _pick('manual'),
            ),
            if (_cfg.mode == 'manual')
              Padding(
                padding: const EdgeInsets.only(left: 12, bottom: 8),
                child: OutlinedButton.icon(
                  onPressed: () {
                    Navigator.pop(context);
                    Navigator.of(context).push(
                      MaterialPageRoute(
                        builder: (_) => ManualScoringScreen(inputUrl: _cfg.manualInputUrl),
                      ),
                    );
                  },
                  icon: const Icon(Icons.edit),
                  label: const Text('Open scorer'),
                ),
              ),
            _ModeTile(
              title: 'BLE (R&D)',
              subtitle: 'PCS Bluetooth relay from another phone',
              selected: _cfg.mode == 'ble',
              busy: _busy,
              onTap: () => _pick('ble'),
            ),
            if (_cfg.mode == 'ble') ...[
              const SizedBox(height: 8),
              Text(
                'Install PCS Relay APK, paste ingest URL + token in Settings, '
                'advertise as scoreboard near the iPad.',
                style: appTextTheme.bodySmall,
              ),
              const SizedBox(height: 8),
              SelectableText(
                'Ingest: ${_cfg.pcsIngestUrl}\nToken: ${_cfg.pcsIngestToken}',
                style: const TextStyle(
                  fontSize: 12,
                  fontFamily: 'monospace',
                  color: AppColors.onBackgroundMuted,
                ),
              ),
              Row(
                children: [
                  TextButton(
                    onPressed: () {
                      Clipboard.setData(ClipboardData(text: _cfg.pcsIngestUrl));
                      ScaffoldMessenger.of(context).showSnackBar(
                        const SnackBar(content: Text('Ingest URL copied')),
                      );
                    },
                    child: const Text('Copy URL'),
                  ),
                  TextButton(
                    onPressed: () {
                      Clipboard.setData(
                        ClipboardData(text: 'Bearer ${_cfg.pcsIngestToken}'),
                      );
                      ScaffoldMessenger.of(context).showSnackBar(
                        const SnackBar(content: Text('Bearer token copied')),
                      );
                    },
                    child: const Text('Copy token'),
                  ),
                ],
              ),
            ],
          ],
        ),
      ),
    );
  }
}

class _ModeTile extends StatelessWidget {
  const _ModeTile({
    required this.title,
    required this.subtitle,
    required this.selected,
    required this.busy,
    required this.onTap,
  });

  final String title;
  final String subtitle;
  final bool selected;
  final bool busy;
  final VoidCallback onTap;

  @override
  Widget build(BuildContext context) {
    return ListTile(
      contentPadding: EdgeInsets.zero,
      leading: Icon(
        selected ? Icons.radio_button_checked : Icons.radio_button_off,
        color: selected ? AppColors.accentGreen : AppColors.onBackgroundDim,
      ),
      title: Text(title, style: const TextStyle(color: AppColors.onBackground)),
      subtitle: Text(subtitle, style: appTextTheme.bodySmall),
      enabled: !busy,
      onTap: busy ? null : onTap,
    );
  }
}

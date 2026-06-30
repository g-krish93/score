import 'dart:async';

import 'package:flutter/material.dart';
import 'package:flutter/services.dart';
import 'package:qr_flutter/qr_flutter.dart';
import 'package:share_plus/share_plus.dart';

import '../screens/manual_scoring_screen.dart';
import '../services/api.dart';
import '../theme/app_theme.dart';
import 'studio/studio_shell.dart';
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
  bool _scorerActive = false;
  bool _showAutoProviders = false;
  Timer? _statusPoll;

  @override
  void initState() {
    super.initState();
    _cfg = widget.initial;
    _pollScorerStatus();
    _statusPoll = Timer.periodic(const Duration(seconds: 5), (_) => _pollScorerStatus());
  }

  @override
  void dispose() {
    _statusPoll?.cancel();
    super.dispose();
  }

  Future<void> _pollScorerStatus() async {
    if (_cfg.mode != 'manual') return;
    try {
      final day = await widget.api.getMatchDayStatus(widget.matchSlug);
      if (mounted) setState(() => _scorerActive = day.scoringActive);
    } catch (_) {}
  }

  Future<void> _pick(String mode, {String? provider}) async {
    setState(() {
      _busy = true;
      _error = null;
    });
    try {
      final next = await widget.api.setScoring(widget.matchSlug, mode, provider: provider);
      widget.onUpdated(next);
      if (!mounted) return;
      setState(() => _cfg = next);
    } catch (e) {
      setState(() => _error = e.toString().replaceFirst('Exception: ', ''));
    } finally {
      if (mounted) setState(() => _busy = false);
    }
  }

  void _copyManualLink() {
    final url = _cfg.scorerUrl;
    if (url.isEmpty) return;
    Clipboard.setData(ClipboardData(text: url));
    ScaffoldMessenger.of(context).showSnackBar(
      const SnackBar(
        content: Text('Scorer link copied — send it to a teammate (WhatsApp, SMS, etc.)'),
      ),
    );
  }

  Future<void> _shareManualLink() async {
    final url = _cfg.scorerUrl;
    if (url.isEmpty) return;
    await Share.share('Score this match: $url');
  }

  void _openScorerOnThisPhone() {
    final url = _cfg.scorerUrl;
    if (url.isEmpty) return;
    Navigator.pop(context);
    Navigator.of(context).push(
      MaterialPageRoute(
        builder: (_) => ManualScoringScreen(
          inputUrl: url,
          matchLabel: widget.matchSlug,
        ),
      ),
    );
  }

  @override
  Widget build(BuildContext context) {
    return SafeArea(
      child: SingleChildScrollView(
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
              subtitle: 'Play-Cricket or CricHeroes scraper',
              selected: _cfg.mode == 'auto',
              busy: _busy,
              onTap: () => setState(() => _showAutoProviders = !_showAutoProviders),
            ),
            if (_showAutoProviders || _cfg.mode == 'auto') ...[
              const SizedBox(height: 4),
              _ModeTile(
                title: 'Play-Cricket',
                subtitle: 'Club Play-Cricket scorer (hands-off)',
                selected: _cfg.mode == 'auto',
                busy: _busy,
                onTap: () => _pick('auto', provider: 'play_cricket'),
              ),
              _ModeTile(
                title: 'CricHeroes',
                subtitle: 'Best-effort CricHeroes scorecard scrape',
                selected: false,
                busy: _busy,
                onTap: () => _pick('auto', provider: 'cricheroes'),
              ),
            ],
            _ModeTile(
              title: 'Manual',
              subtitle: 'Teammate scores over-by-over in a browser',
              selected: _cfg.mode == 'manual',
              busy: _busy,
              onTap: () => _pick('manual'),
            ),
            if (_cfg.mode == 'manual') ...[
              const SizedBox(height: AppSpacing.sm),
              ManualScorerLinkCard(
                url: _cfg.scorerUrl,
                scorerActive: _scorerActive,
                onCopy: _copyManualLink,
                onShare: _shareManualLink,
                onOpenHere: _openScorerOnThisPhone,
              ),
            ],
          ],
        ),
      ),
    );
  }
}

/// Shareable scorer URL — teammates open this on any phone browser.
class ManualScorerLinkCard extends StatelessWidget {
  const ManualScorerLinkCard({
    super.key,
    required this.url,
    required this.onCopy,
    required this.onShare,
    required this.onOpenHere,
    this.scorerActive = false,
  });

  final String url;
  final VoidCallback onCopy;
  final VoidCallback onShare;
  final VoidCallback onOpenHere;
  final bool scorerActive;

  @override
  Widget build(BuildContext context) {
    if (url.isEmpty) {
      return const CrInfoBanner(
        title: 'Scorer link unavailable',
        body: 'Check your internet connection and try selecting Manual again.',
        accentColor: AppColors.warning,
      );
    }
    return CrGlassPanel(
      padding: const EdgeInsets.all(AppSpacing.md),
      borderRadius: AppSpacing.radiusMd,
      child: Column(
        crossAxisAlignment: CrossAxisAlignment.stretch,
        children: [
          Text(
            'Scorer link for teammates',
            style: appTextTheme.titleSmall?.copyWith(fontWeight: FontWeight.w700),
          ),
          const SizedBox(height: AppSpacing.sm),
          Text(
            'Copy this link and send it to a scorer on a 2nd or 3rd phone. '
            'They open it in Chrome/Safari and enter ball-by-ball scores. '
            'Scores save to your club server and update the live stream overlay automatically.',
            style: appTextTheme.bodySmall?.copyWith(color: AppColors.onBackgroundMuted),
          ),
          const SizedBox(height: AppSpacing.sm),
          Row(
            children: [
              Icon(
                scorerActive ? Icons.check_circle_rounded : Icons.hourglass_empty_rounded,
                size: 18,
                color: scorerActive ? AppColors.accentGreen : AppColors.warning,
              ),
              const SizedBox(width: 8),
              Expanded(
                child: Text(
                  scorerActive ? 'Scorer active on another phone' : 'Waiting for scorer to connect',
                  style: appTextTheme.bodySmall,
                ),
              ),
            ],
          ),
          const SizedBox(height: AppSpacing.md),
          Center(
            child: QrImageView(
              data: url,
              size: 160,
              backgroundColor: Colors.white,
            ),
          ),
          const SizedBox(height: AppSpacing.md),
          DecoratedBox(
            decoration: BoxDecoration(
              color: AppColors.surfaceVariant.withValues(alpha: 0.6),
              borderRadius: BorderRadius.circular(AppSpacing.radiusSm),
              border: Border.all(color: AppColors.glassBorder),
            ),
            child: Padding(
              padding: const EdgeInsets.all(12),
              child: SelectableText(
                url,
                style: const TextStyle(
                  fontSize: 13,
                  fontFamily: 'monospace',
                  color: AppColors.accentGreen,
                  height: 1.4,
                ),
              ),
            ),
          ),
          const SizedBox(height: AppSpacing.md),
          FilledButton.icon(
            onPressed: onCopy,
            icon: const Icon(Icons.link_rounded),
            label: const Text('Copy scorer link'),
          ),
          const SizedBox(height: AppSpacing.sm),
          OutlinedButton.icon(
            onPressed: onShare,
            icon: const Icon(Icons.share_rounded),
            label: const Text('Share link'),
          ),
          const SizedBox(height: AppSpacing.sm),
          OutlinedButton.icon(
            onPressed: onOpenHere,
            icon: const Icon(Icons.phone_android_rounded),
            label: const Text('Open scorer on this phone'),
          ),
        ],
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

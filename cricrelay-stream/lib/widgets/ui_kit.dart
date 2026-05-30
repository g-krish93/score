import 'dart:async';

import 'package:flutter/material.dart';

import '../theme/app_theme.dart';

class CrBottomSheetHandle extends StatelessWidget {
  const CrBottomSheetHandle({super.key});

  @override
  Widget build(BuildContext context) {
    return Center(
      child: Container(
        width: 40,
        height: 4,
        margin: const EdgeInsets.only(top: 10, bottom: 8),
        decoration: BoxDecoration(
          color: AppColors.onBackgroundDim,
          borderRadius: BorderRadius.circular(2),
        ),
      ),
    );
  }
}

class CrSheetHeader extends StatelessWidget {
  const CrSheetHeader({
    super.key,
    required this.title,
    this.subtitle,
  });

  final String title;
  final String? subtitle;

  @override
  Widget build(BuildContext context) {
    return Padding(
      padding: const EdgeInsets.fromLTRB(20, 4, 20, 12),
      child: Column(
        crossAxisAlignment: CrossAxisAlignment.start,
        children: [
          Text(title, style: appTextTheme.headlineSmall),
          if (subtitle != null) ...[
            const SizedBox(height: 6),
            Text(subtitle!, style: appTextTheme.bodyMedium),
          ],
        ],
      ),
    );
  }
}

Future<T?> showCrBottomSheet<T>({
  required BuildContext context,
  required Widget child,
}) {
  return showModalBottomSheet<T>(
    context: context,
    isScrollControlled: true,
    backgroundColor: AppColors.surfaceElevated,
    barrierColor: Colors.black54,
    shape: const RoundedRectangleBorder(
      borderRadius: BorderRadius.vertical(top: Radius.circular(AppSpacing.radiusXl)),
    ),
    builder: (ctx) => Padding(
      padding: EdgeInsets.only(bottom: MediaQuery.of(ctx).viewInsets.bottom),
      child: child,
    ),
  );
}

Future<bool?> showCrConfirmDialog({
  required BuildContext context,
  required String title,
  required String message,
  String cancelLabel = 'Cancel',
  String confirmLabel = 'Confirm',
  bool destructive = false,
}) {
  return showDialog<bool>(
    context: context,
    builder: (ctx) => AlertDialog(
      title: Text(title),
      content: Text(message, style: appTextTheme.bodyMedium),
      actions: [
        TextButton(onPressed: () => Navigator.pop(ctx, false), child: Text(cancelLabel)),
        FilledButton(
          onPressed: () => Navigator.pop(ctx, true),
          style: destructive
              ? FilledButton.styleFrom(backgroundColor: AppColors.error)
              : null,
          child: Text(confirmLabel),
        ),
      ],
    ),
  );
}

class CrErrorBanner extends StatelessWidget {
  const CrErrorBanner({super.key, required this.message});

  final String message;

  @override
  Widget build(BuildContext context) {
    return Container(
      width: double.infinity,
      padding: const EdgeInsets.all(12),
      decoration: BoxDecoration(
        color: AppColors.error.withValues(alpha: 0.12),
        borderRadius: BorderRadius.circular(AppSpacing.radiusSm),
        border: Border.all(color: AppColors.error.withValues(alpha: 0.4)),
      ),
      child: Row(
        crossAxisAlignment: CrossAxisAlignment.start,
        children: [
          const Icon(Icons.error_outline, color: AppColors.error, size: 20),
          const SizedBox(width: 10),
          Expanded(child: Text(message, style: const TextStyle(color: AppColors.error, fontSize: 13))),
        ],
      ),
    );
  }
}

class CrInfoBanner extends StatelessWidget {
  const CrInfoBanner({
    super.key,
    required this.title,
    required this.body,
    this.icon = Icons.info_outline,
    this.accentColor = AppColors.accent,
  });

  final String title;
  final String body;
  final IconData icon;
  final Color accentColor;

  @override
  Widget build(BuildContext context) {
    return Container(
      width: double.infinity,
      padding: const EdgeInsets.all(14),
      decoration: BoxDecoration(
        color: accentColor.withValues(alpha: 0.08),
        borderRadius: BorderRadius.circular(AppSpacing.radiusMd),
        border: Border.all(color: accentColor.withValues(alpha: 0.25)),
      ),
      child: Column(
        crossAxisAlignment: CrossAxisAlignment.start,
        children: [
          Row(
            children: [
              Icon(icon, color: accentColor, size: 20),
              const SizedBox(width: 8),
              Expanded(
                child: Text(title, style: const TextStyle(fontWeight: FontWeight.w600, fontSize: 14)),
              ),
            ],
          ),
          const SizedBox(height: 8),
          Text(body, style: appTextTheme.bodyMedium),
        ],
      ),
    );
  }
}

class CrStatusChip extends StatelessWidget {
  const CrStatusChip({
    super.key,
    required this.label,
    required this.ok,
  });

  final String label;
  final bool ok;

  @override
  Widget build(BuildContext context) {
    final color = ok ? AppColors.success : AppColors.warning;
    return Container(
      padding: const EdgeInsets.symmetric(horizontal: 10, vertical: 5),
      decoration: BoxDecoration(
        color: color.withValues(alpha: 0.15),
        borderRadius: BorderRadius.circular(20),
        border: Border.all(color: color.withValues(alpha: 0.5)),
      ),
      child: Row(
        mainAxisSize: MainAxisSize.min,
        children: [
          Icon(ok ? Icons.check_circle : Icons.warning_amber_rounded, size: 14, color: color),
          const SizedBox(width: 6),
          Flexible(
            child: Text(
              label,
              style: TextStyle(color: color, fontSize: 12, fontWeight: FontWeight.w500),
              overflow: TextOverflow.ellipsis,
            ),
          ),
        ],
      ),
    );
  }
}

class CrStreamTile extends StatelessWidget {
  const CrStreamTile({
    super.key,
    required this.title,
    required this.subtitle,
    required this.onTap,
    this.isLive = false,
  });

  final String title;
  final String subtitle;
  final VoidCallback onTap;
  final bool isLive;

  @override
  Widget build(BuildContext context) {
    return Material(
      color: AppColors.surface,
      borderRadius: BorderRadius.circular(AppSpacing.radiusMd),
      child: InkWell(
        onTap: onTap,
        borderRadius: BorderRadius.circular(AppSpacing.radiusMd),
        child: Ink(
          decoration: BoxDecoration(
            borderRadius: BorderRadius.circular(AppSpacing.radiusMd),
            border: Border.all(
              color: isLive ? AppColors.live.withValues(alpha: 0.35) : AppColors.borderSubtle,
            ),
            gradient: isLive
                ? LinearGradient(
                    colors: [
                      AppColors.live.withValues(alpha: 0.08),
                      AppColors.surface,
                    ],
                    begin: Alignment.centerLeft,
                    end: Alignment.centerRight,
                  )
                : null,
          ),
          padding: const EdgeInsets.symmetric(horizontal: 14, vertical: 14),
          child: Row(
            children: [
              Container(
                width: 52,
                height: 52,
                decoration: BoxDecoration(
                  gradient: LinearGradient(
                    colors: isLive
                        ? [AppColors.live.withValues(alpha: 0.25), AppColors.primaryMuted.withValues(alpha: 0.15)]
                        : [AppColors.surfaceVariant, AppColors.surfaceElevated],
                    begin: Alignment.topLeft,
                    end: Alignment.bottomRight,
                  ),
                  borderRadius: BorderRadius.circular(AppSpacing.radiusSm),
                  border: Border.all(
                    color: isLive ? AppColors.live.withValues(alpha: 0.4) : AppColors.borderSubtle,
                  ),
                ),
                child: Icon(
                  isLive ? Icons.sensors : Icons.play_circle_outline_rounded,
                  color: isLive ? AppColors.live : AppColors.onBackgroundMuted,
                  size: 28,
                ),
              ),
              const SizedBox(width: 14),
              Expanded(
                child: Column(
                  crossAxisAlignment: CrossAxisAlignment.start,
                  children: [
                    Row(
                      children: [
                        if (isLive) ...[
                          Container(
                            width: 7,
                            height: 7,
                            margin: const EdgeInsets.only(right: 6),
                            decoration: const BoxDecoration(color: AppColors.live, shape: BoxShape.circle),
                          ),
                          Text('LIVE', style: metricStyle(size: 10, color: AppColors.live)),
                          const SizedBox(width: 8),
                        ],
                        Expanded(
                          child: Text(
                            title,
                            style: const TextStyle(fontWeight: FontWeight.w700, fontSize: 15, letterSpacing: -0.2),
                            maxLines: 2,
                            overflow: TextOverflow.ellipsis,
                          ),
                        ),
                      ],
                    ),
                    const SizedBox(height: 4),
                    Text(subtitle, style: appTextTheme.bodySmall, maxLines: 1, overflow: TextOverflow.ellipsis),
                  ],
                ),
              ),
              const Icon(Icons.arrow_forward_ios_rounded, size: 16, color: AppColors.onBackgroundDim),
            ],
          ),
        ),
      ),
    );
  }
}

class CrGoLiveButton extends StatelessWidget {
  const CrGoLiveButton({
    super.key,
    required this.live,
    required this.busy,
    required this.enabled,
    required this.onGoLive,
    required this.onStop,
  });

  final bool live;
  final bool busy;
  final bool enabled;
  final VoidCallback? onGoLive;
  final VoidCallback? onStop;

  @override
  Widget build(BuildContext context) {
    if (live) {
      return SizedBox(
        height: 56,
        width: double.infinity,
        child: DecoratedBox(
          decoration: BoxDecoration(
            borderRadius: BorderRadius.circular(AppSpacing.radiusMd),
            border: Border.all(color: AppColors.live.withValues(alpha: 0.5)),
            boxShadow: [
              BoxShadow(color: AppColors.live.withValues(alpha: 0.2), blurRadius: 16, offset: const Offset(0, 4)),
            ],
          ),
          child: FilledButton(
            onPressed: busy ? null : onStop,
            style: FilledButton.styleFrom(
              backgroundColor: AppColors.surfaceVariant,
              foregroundColor: AppColors.onBackground,
              shape: RoundedRectangleBorder(borderRadius: BorderRadius.circular(AppSpacing.radiusMd)),
            ),
            child: Row(
              mainAxisAlignment: MainAxisAlignment.center,
              children: [
                Container(
                  width: 10,
                  height: 10,
                  decoration: const BoxDecoration(color: AppColors.live, shape: BoxShape.circle),
                ),
                const SizedBox(width: 10),
                const Text('Stop broadcast', style: TextStyle(fontWeight: FontWeight.w800, fontSize: 16, letterSpacing: 0.3)),
              ],
            ),
          ),
        ),
      );
    }
    return SizedBox(
      height: 56,
      width: double.infinity,
      child: DecoratedBox(
        decoration: BoxDecoration(
          borderRadius: BorderRadius.circular(AppSpacing.radiusMd),
          gradient: enabled && !busy
              ? const LinearGradient(
                  colors: [Color(0xFFFF4D57), Color(0xFFE8232E)],
                  begin: Alignment.topLeft,
                  end: Alignment.bottomRight,
                )
              : null,
          color: enabled && !busy ? null : AppColors.surfaceVariant,
          boxShadow: enabled && !busy
              ? [BoxShadow(color: AppColors.primaryGlow, blurRadius: 20, offset: const Offset(0, 6))]
              : null,
        ),
        child: FilledButton.icon(
          onPressed: (busy || !enabled) ? null : onGoLive,
          style: FilledButton.styleFrom(
            backgroundColor: Colors.transparent,
            shadowColor: Colors.transparent,
            shape: RoundedRectangleBorder(borderRadius: BorderRadius.circular(AppSpacing.radiusMd)),
          ),
          icon: busy
              ? const SizedBox(
                  width: 22,
                  height: 22,
                  child: CircularProgressIndicator(strokeWidth: 2.5, color: Colors.white),
                )
              : const Icon(Icons.sensors_rounded, size: 24),
          label: Text(
            busy ? 'Going live…' : 'Go live',
            style: const TextStyle(fontWeight: FontWeight.w800, fontSize: 17, letterSpacing: 0.4),
          ),
        ),
      ),
    );
  }
}

class CrLiveTimerBadge extends StatefulWidget {
  const CrLiveTimerBadge({super.key, this.startedAt, this.onTick, this.paused = false});

  final DateTime? startedAt;
  final ValueChanged<Duration>? onTick;
  final bool paused;

  @override
  State<CrLiveTimerBadge> createState() => _CrLiveTimerBadgeState();
}

class _CrLiveTimerBadgeState extends State<CrLiveTimerBadge> {
  Timer? _timer;
  Duration _elapsed = Duration.zero;

  @override
  void initState() {
    super.initState();
    _syncElapsed();
    _timer = Timer.periodic(const Duration(seconds: 1), (_) => _syncElapsed());
  }

  @override
  void didUpdateWidget(covariant CrLiveTimerBadge oldWidget) {
    super.didUpdateWidget(oldWidget);
    if (oldWidget.startedAt != widget.startedAt || oldWidget.paused != widget.paused) {
      _syncElapsed();
    }
  }

  void _syncElapsed() {
    final start = widget.startedAt;
    if (start == null) return;
    if (widget.paused) return;
    final next = DateTime.now().difference(start);
    if (!mounted) return;
    setState(() => _elapsed = next);
    widget.onTick?.call(next);
  }

  @override
  void dispose() {
    _timer?.cancel();
    super.dispose();
  }

  String _format(Duration d) {
    final h = d.inHours;
    final m = d.inMinutes.remainder(60).toString().padLeft(2, '0');
    final s = d.inSeconds.remainder(60).toString().padLeft(2, '0');
    if (h > 0) return '$h:$m:$s';
    return '$m:$s';
  }

  @override
  Widget build(BuildContext context) {
    return Container(
      padding: const EdgeInsets.symmetric(horizontal: 12, vertical: 6),
      decoration: BoxDecoration(
        gradient: const LinearGradient(colors: [Color(0xFFFF4D57), Color(0xFFCC2F38)]),
        borderRadius: BorderRadius.circular(AppSpacing.radiusSm),
        boxShadow: [
          BoxShadow(color: AppColors.live.withValues(alpha: 0.45), blurRadius: 12, offset: const Offset(0, 2)),
        ],
      ),
      child: Row(
        mainAxisSize: MainAxisSize.min,
        children: [
          const Icon(Icons.fiber_manual_record_rounded, size: 10, color: Colors.white),
          const SizedBox(width: 6),
          Text(
            widget.paused ? 'PAUSED ${_format(_elapsed)}' : 'LIVE ${_format(_elapsed)}',
            style: metricStyle(size: 12, color: Colors.white, weight: FontWeight.w800),
          ),
        ],
      ),
    );
  }
}

class CrLiveBadge extends StatelessWidget {
  const CrLiveBadge({super.key});

  @override
  Widget build(BuildContext context) {
    return const CrLiveTimerBadge();
  }
}

class CrBootstrapLoading extends StatelessWidget {
  const CrBootstrapLoading({super.key});

  @override
  Widget build(BuildContext context) {
    return Scaffold(
      backgroundColor: AppColors.background,
      body: Center(
        child: Column(
          mainAxisSize: MainAxisSize.min,
          children: [
            Container(
              width: 72,
              height: 72,
              decoration: BoxDecoration(
                gradient: LinearGradient(
                  colors: [AppColors.primary.withValues(alpha: 0.2), AppColors.accent.withValues(alpha: 0.1)],
                  begin: Alignment.topLeft,
                  end: Alignment.bottomRight,
                ),
                borderRadius: BorderRadius.circular(20),
                border: Border.all(color: AppColors.glassBorder),
              ),
              child: const Icon(Icons.sensors_rounded, color: AppColors.primary, size: 38),
            ),
            const SizedBox(height: 24),
            Text('CricRelay Live', style: appTextTheme.headlineSmall),
            const SizedBox(height: 8),
            Text('Loading studio…', style: appTextTheme.bodySmall),
            const SizedBox(height: 24),
            const SizedBox(width: 28, height: 28, child: CircularProgressIndicator(strokeWidth: 2.5)),
          ],
        ),
      ),
    );
  }
}

class CrSectionLabel extends StatelessWidget {
  const CrSectionLabel(this.text, {super.key});

  final String text;

  @override
  Widget build(BuildContext context) {
    return Padding(
      padding: const EdgeInsets.only(bottom: 10, top: 4),
      child: Text(
        text.toUpperCase(),
        style: const TextStyle(
          fontSize: 11,
          fontWeight: FontWeight.w700,
          letterSpacing: 1.1,
          color: AppColors.onBackgroundDim,
        ),
      ),
    );
  }
}

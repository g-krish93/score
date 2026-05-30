import 'dart:ui';

import 'package:flutter/material.dart';
import 'package:flutter/services.dart';

import '../theme/app_theme.dart';
import 'studio/studio_shell.dart';
import 'ui_kit.dart';

/// Pro broadcast control rail — studio-grade layout below or beside preview.
class BroadcastControlDock extends StatelessWidget {
  const BroadcastControlDock({
    super.key,
    required this.status,
    required this.live,
    required this.paused,
    required this.busy,
    required this.camReady,
    required this.qualityLabel,
    required this.zoom,
    required this.minZoom,
    required this.maxZoom,
    required this.zoomDisplay,
    required this.onZoomChanged,
    required this.onOpenQuality,
    required this.onOpenScoring,
    required this.onOpenOverlay,
    required this.onToggleOverlayLock,
    required this.onOpenDestination,
    required this.overlayLocked,
    required this.onGoLive,
    required this.onStop,
    required this.onTogglePause,
  });

  final String? status;
  final bool live;
  final bool paused;
  final bool busy;
  final bool camReady;
  final String qualityLabel;
  final double zoom;
  final double minZoom;
  final double maxZoom;
  final double zoomDisplay;
  final ValueChanged<double> onZoomChanged;
  final VoidCallback onOpenQuality;
  final VoidCallback onOpenScoring;
  final VoidCallback onOpenOverlay;
  final VoidCallback onToggleOverlayLock;
  final VoidCallback onOpenDestination;
  final bool overlayLocked;
  final Future<void> Function() onGoLive;
  final Future<void> Function() onStop;
  final Future<void> Function() onTogglePause;

  Future<void> _confirmStop(BuildContext context) async {
    if (!live) {
      await onStop();
      return;
    }
    final confirmed = await showCrConfirmDialog(
      context: context,
      title: 'End broadcast?',
      message:
          'This stops the live stream immediately. You can go live again after checking your destination and stream key.',
      confirmLabel: 'Stop stream',
      destructive: true,
    );
    if (confirmed == true) await onStop();
  }

  @override
  Widget build(BuildContext context) {
    final canZoom = camReady && maxZoom > minZoom;
    final bottomInset = MediaQuery.of(context).padding.bottom;

    return ClipRRect(
      borderRadius: const BorderRadius.vertical(top: Radius.circular(AppSpacing.radiusXl)),
      child: BackdropFilter(
        filter: ImageFilter.blur(sigmaX: 24, sigmaY: 24),
        child: DecoratedBox(
          decoration: BoxDecoration(
            color: AppColors.surfaceElevated.withValues(alpha: 0.92),
            border: const Border(top: BorderSide(color: AppColors.glassBorder)),
            boxShadow: [
              BoxShadow(
                color: Colors.black.withValues(alpha: 0.45),
                blurRadius: 32,
                offset: const Offset(0, -8),
              ),
            ],
          ),
          child: Padding(
            padding: EdgeInsets.fromLTRB(AppSpacing.md, AppSpacing.sm, AppSpacing.md, AppSpacing.md + bottomInset),
            child: Column(
              mainAxisSize: MainAxisSize.min,
              crossAxisAlignment: CrossAxisAlignment.stretch,
              children: [
                const CrBottomSheetHandle(),
                if (status != null && status!.isNotEmpty) ...[
                  const SizedBox(height: AppSpacing.sm),
                  CrStatusPill(
                    label: paused ? 'PAUSED' : (live ? 'STREAMING' : 'READY'),
                    color: paused ? AppColors.warning : (live ? AppColors.live : AppColors.accent),
                    pulse: live && !paused,
                  ),
                  const SizedBox(height: AppSpacing.sm),
                  Text(
                    status!,
                    style: appTextTheme.bodySmall?.copyWith(color: AppColors.onBackgroundMuted),
                    maxLines: 3,
                    overflow: TextOverflow.ellipsis,
                  ),
                ],
                const SizedBox(height: AppSpacing.md),
                CrGoLiveButton(
                  live: live,
                  busy: busy,
                  enabled: camReady,
                  onGoLive: () {
                    HapticFeedback.mediumImpact();
                    onGoLive();
                  },
                  onStop: () {
                    HapticFeedback.lightImpact();
                    _confirmStop(context);
                  },
                ),
                if (live) ...[
                  const SizedBox(height: AppSpacing.sm),
                  SizedBox(
                    height: 48,
                    width: double.infinity,
                    child: OutlinedButton.icon(
                      onPressed: busy ? null : () {
                        HapticFeedback.selectionClick();
                        onTogglePause();
                      },
                      icon: Icon(paused ? Icons.play_arrow_rounded : Icons.pause_rounded),
                      label: Text(paused ? 'Resume broadcast' : 'Pause broadcast'),
                    ),
                  ),
                ],
                const SizedBox(height: AppSpacing.md),
                const Text('STUDIO CONTROLS', style: _sectionStyle),
                const SizedBox(height: AppSpacing.sm),
                Row(
                  children: [
                    _StudioControl(
                      icon: Icons.settings_input_antenna_rounded,
                      label: 'Destination',
                      accent: AppColors.accentBlue,
                      onTap: onOpenDestination,
                    ),
                    _StudioControl(
                      icon: Icons.layers_outlined,
                      label: 'Overlay',
                      accent: AppColors.accent,
                      onTap: overlayLocked ? null : onOpenOverlay,
                    ),
                    _StudioControl(
                      icon: overlayLocked ? Icons.lock_rounded : Icons.lock_open_rounded,
                      label: overlayLocked ? 'Locked' : 'Lock board',
                      accent: overlayLocked ? AppColors.warning : AppColors.onBackgroundMuted,
                      onTap: onToggleOverlayLock,
                    ),
                  ],
                ),
                const SizedBox(height: AppSpacing.sm),
                Row(
                  children: [
                    _StudioControl(
                      icon: Icons.scoreboard_rounded,
                      label: 'Scoring',
                      accent: AppColors.accentPurple,
                      onTap: onOpenScoring,
                    ),
                    _StudioControl(
                      icon: Icons.tune_rounded,
                      label: qualityLabel,
                      accent: AppColors.accentGreen,
                      onTap: onOpenQuality,
                    ),
                    const Expanded(child: SizedBox()),
                  ],
                ),
                if (canZoom) ...[
                  const SizedBox(height: AppSpacing.md),
                  Row(
                    children: [
                      const Icon(Icons.zoom_out_map_rounded, size: 18, color: AppColors.onBackgroundDim),
                      Expanded(
                        child: Slider(
                          value: zoom,
                          min: minZoom,
                          max: maxZoom,
                          onChanged: onZoomChanged,
                        ),
                      ),
                      Text(
                        '${zoomDisplay.toStringAsFixed(1)}×',
                        style: metricStyle(size: 13, color: AppColors.accent),
                      ),
                    ],
                  ),
                ],
              ],
            ),
          ),
        ),
      ),
    );
  }
}

const _sectionStyle = TextStyle(
  fontSize: 10,
  fontWeight: FontWeight.w800,
  letterSpacing: 1.4,
  color: AppColors.onBackgroundDim,
);

class _StudioControl extends StatelessWidget {
  const _StudioControl({
    required this.icon,
    required this.label,
    required this.accent,
    this.onTap,
  });

  final IconData icon;
  final String label;
  final Color accent;
  final VoidCallback? onTap;

  @override
  Widget build(BuildContext context) {
    final enabled = onTap != null;
    return Expanded(
      child: Padding(
        padding: const EdgeInsets.symmetric(horizontal: 4),
        child: Material(
          color: enabled ? AppColors.surfaceVariant.withValues(alpha: 0.55) : AppColors.surfaceVariant.withValues(alpha: 0.25),
          borderRadius: BorderRadius.circular(AppSpacing.radiusSm),
          child: InkWell(
            onTap: enabled ? onTap : null,
            borderRadius: BorderRadius.circular(AppSpacing.radiusSm),
            child: Padding(
              padding: const EdgeInsets.symmetric(vertical: 14, horizontal: 6),
              child: Column(
                children: [
                  Icon(icon, size: 24, color: enabled ? accent : AppColors.onBackgroundDim),
                  const SizedBox(height: 6),
                  Text(
                    label,
                    style: TextStyle(
                      fontSize: 11,
                      fontWeight: FontWeight.w600,
                      color: enabled ? AppColors.onBackgroundMuted : AppColors.onBackgroundDim,
                    ),
                    maxLines: 1,
                    overflow: TextOverflow.ellipsis,
                    textAlign: TextAlign.center,
                  ),
                ],
              ),
            ),
          ),
        ),
      ),
    );
  }
}

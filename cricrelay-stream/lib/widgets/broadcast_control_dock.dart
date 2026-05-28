import 'package:flutter/material.dart';

import '../theme/app_theme.dart';
import 'ui_kit.dart';

/// YouTube-style control dock below the camera preview.
class BroadcastControlDock extends StatelessWidget {
  const BroadcastControlDock({
    super.key,
    required this.status,
    required this.live,
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
  });

  final String? status;
  final bool live;
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

  @override
  Widget build(BuildContext context) {
    final canZoom = camReady && maxZoom > minZoom;
    return Container(
      decoration: const BoxDecoration(
        color: AppColors.surface,
        borderRadius: BorderRadius.vertical(top: Radius.circular(AppSpacing.radiusXl)),
        border: Border(top: BorderSide(color: AppColors.border)),
      ),
      padding: EdgeInsets.fromLTRB(
        AppSpacing.md,
        AppSpacing.md,
        AppSpacing.md,
        AppSpacing.md + MediaQuery.of(context).padding.bottom,
      ),
      child: Column(
        mainAxisSize: MainAxisSize.min,
        crossAxisAlignment: CrossAxisAlignment.stretch,
        children: [
          Center(
            child: Container(
              width: 40,
              height: 4,
              decoration: BoxDecoration(
                color: AppColors.surfaceVariant,
                borderRadius: BorderRadius.circular(2),
              ),
            ),
          ),
          if (status != null && status!.isNotEmpty) ...[
            const SizedBox(height: AppSpacing.md),
            Text(
              status!,
              textAlign: TextAlign.center,
              style: appTextTheme.bodySmall,
              maxLines: 3,
              overflow: TextOverflow.ellipsis,
            ),
          ],
          const SizedBox(height: AppSpacing.md),
          CrGoLiveButton(
            live: live,
            busy: busy,
            enabled: camReady,
            onGoLive: () => onGoLive(),
            onStop: () => onStop(),
          ),
          const SizedBox(height: AppSpacing.md),
          Row(
            children: [
              _DockAction(
                icon: Icons.settings_input_antenna_outlined,
                label: 'Stream',
                onTap: onOpenDestination,
              ),
              _DockAction(
                icon: Icons.tune,
                label: 'Overlay',
                onTap: overlayLocked ? null : onOpenOverlay,
              ),
              _DockAction(
                icon: overlayLocked ? Icons.lock : Icons.lock_open_outlined,
                label: overlayLocked ? 'Locked' : 'Lock',
                onTap: onToggleOverlayLock,
              ),
              _DockAction(icon: Icons.scoreboard_outlined, label: 'Score', onTap: onOpenScoring),
              _DockAction(icon: Icons.hd_outlined, label: qualityLabel, onTap: onOpenQuality),
            ],
          ),
          if (canZoom) ...[
            const SizedBox(height: AppSpacing.sm),
            Row(
              children: [
                const Icon(Icons.zoom_out, size: 18, color: AppColors.onBackgroundDim),
                Expanded(
                  child: Slider(
                    value: zoom,
                    min: minZoom,
                    max: maxZoom,
                    onChanged: onZoomChanged,
                  ),
                ),
                const Icon(Icons.zoom_in, size: 18, color: AppColors.onBackgroundDim),
                Text(
                  '${zoomDisplay.toStringAsFixed(1)}×',
                  style: const TextStyle(fontSize: 12, fontWeight: FontWeight.w600),
                ),
              ],
            ),
          ],
        ],
      ),
    );
  }
}

class _DockAction extends StatelessWidget {
  const _DockAction({
    required this.icon,
    required this.label,
    this.onTap,
  });

  final IconData icon;
  final String label;
  final VoidCallback? onTap;

  @override
  Widget build(BuildContext context) {
    return Expanded(
      child: InkWell(
        onTap: onTap,
        borderRadius: BorderRadius.circular(AppSpacing.radiusSm),
        child: Padding(
          padding: const EdgeInsets.symmetric(vertical: 8),
          child: Column(
            children: [
              Icon(icon, size: 22, color: onTap == null ? AppColors.onBackgroundDim : AppColors.onBackground),
              const SizedBox(height: 4),
              Text(
                label,
                style: TextStyle(
                  fontSize: 10,
                  fontWeight: FontWeight.w500,
                  color: onTap == null ? AppColors.onBackgroundDim : AppColors.onBackgroundMuted,
                ),
                maxLines: 1,
                overflow: TextOverflow.ellipsis,
                textAlign: TextAlign.center,
              ),
            ],
          ),
        ),
      ),
    );
  }
}

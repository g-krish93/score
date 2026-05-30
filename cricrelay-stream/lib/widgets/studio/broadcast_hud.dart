import 'package:flutter/material.dart';

import '../../theme/app_theme.dart';
import 'studio_shell.dart';

/// Top telemetry strip over the camera preview (OBS-style).
class BroadcastPreviewHud extends StatelessWidget {
  const BroadcastPreviewHud({
    super.key,
    required this.live,
    required this.paused,
    required this.qualityLabel,
    required this.orientationLabel,
    this.stabilizationOn = true,
    this.focusLocked = false,
    this.zoomLabel,
    this.liveTimer,
  });

  final bool live;
  final bool paused;
  final String qualityLabel;
  final String orientationLabel;
  final bool stabilizationOn;
  final bool focusLocked;
  final String? zoomLabel;
  final Widget? liveTimer;

  @override
  Widget build(BuildContext context) {
    return Padding(
      padding: const EdgeInsets.fromLTRB(12, 8, 12, 0),
      child: CrGlassPanel(
        padding: const EdgeInsets.symmetric(horizontal: 12, vertical: 8),
        borderRadius: AppSpacing.radiusSm,
        blur: 14,
        child: Row(
          children: [
            CrStatusPill(
              label: paused ? 'PAUSED' : (live ? 'ON AIR' : 'STANDBY'),
              color: paused ? AppColors.warning : (live ? AppColors.live : AppColors.onBackgroundDim),
              pulse: live && !paused,
            ),
            const SizedBox(width: 10),
            _HudMetric(label: 'QUAL', value: qualityLabel),
            const SizedBox(width: 10),
            _HudMetric(label: 'ORIENT', value: orientationLabel.toUpperCase()),
            const SizedBox(width: 10),
            _HudMetric(
              label: 'STEADY',
              value: stabilizationOn ? 'EIS ON' : 'OFF',
            ),
            if (focusLocked) ...[
              const SizedBox(width: 10),
              _HudMetric(label: 'FOCUS', value: 'LOCKED'),
            ],
            if (zoomLabel != null) ...[
              const SizedBox(width: 10),
              _HudMetric(label: 'ZOOM', value: zoomLabel!),
            ],
            const Spacer(),
            if (liveTimer != null) liveTimer!,
          ],
        ),
      ),
    );
  }
}

class _HudMetric extends StatelessWidget {
  const _HudMetric({required this.label, required this.value});

  final String label;
  final String value;

  @override
  Widget build(BuildContext context) {
    return Column(
      crossAxisAlignment: CrossAxisAlignment.start,
      mainAxisSize: MainAxisSize.min,
      children: [
        Text(label, style: metricStyle(size: 9, color: AppColors.onBackgroundDim, weight: FontWeight.w500)),
        Text(
          value,
          style: metricStyle(size: 11, color: AppColors.onBackground),
          maxLines: 1,
          overflow: TextOverflow.ellipsis,
        ),
      ],
    );
  }
}

/// Bottom status strip on preview when not using full dock status.
class BroadcastStatusStrip extends StatelessWidget {
  const BroadcastStatusStrip({super.key, required this.message});

  final String message;

  @override
  Widget build(BuildContext context) {
    if (message.isEmpty) return const SizedBox.shrink();
    return Padding(
      padding: const EdgeInsets.fromLTRB(12, 0, 12, 12),
      child: CrGlassPanel(
        padding: const EdgeInsets.symmetric(horizontal: 14, vertical: 10),
        borderRadius: AppSpacing.radiusSm,
        blur: 12,
        child: Text(
          message,
          style: appTextTheme.bodySmall?.copyWith(color: AppColors.onBackgroundMuted),
          maxLines: 2,
          overflow: TextOverflow.ellipsis,
        ),
      ),
    );
  }
}

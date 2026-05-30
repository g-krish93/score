import 'package:flutter/material.dart';

import '../../theme/app_theme.dart';
import 'studio_shell.dart';

/// Hero header for the home / studio hub screen.
class CrStudioHero extends StatelessWidget {
  const CrStudioHero({super.key});

  @override
  Widget build(BuildContext context) {
    return CrGlassPanel(
      padding: const EdgeInsets.all(AppSpacing.lg),
      borderRadius: AppSpacing.radiusLg,
      child: Row(
        children: [
          Container(
            width: 56,
            height: 56,
            decoration: BoxDecoration(
              gradient: LinearGradient(
                colors: [AppColors.primary.withValues(alpha: 0.25), AppColors.accent.withValues(alpha: 0.15)],
                begin: Alignment.topLeft,
                end: Alignment.bottomRight,
              ),
              borderRadius: BorderRadius.circular(AppSpacing.radiusSm),
              border: Border.all(color: AppColors.glassBorder),
            ),
            child: const Icon(Icons.sensors_rounded, color: AppColors.primary, size: 30),
          ),
          const SizedBox(width: AppSpacing.md),
          Expanded(
            child: Column(
              crossAxisAlignment: CrossAxisAlignment.start,
              children: [
                Text('Broadcast studio', style: appTextTheme.headlineSmall?.copyWith(fontSize: 18)),
                const SizedBox(height: 4),
                Text(
                  'One-phone cricket streaming with live scoreboard overlay.',
                  style: appTextTheme.bodySmall,
                ),
              ],
            ),
          ),
        ],
      ),
    );
  }
}

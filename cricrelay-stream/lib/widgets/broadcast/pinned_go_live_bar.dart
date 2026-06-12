import 'dart:async';

import 'package:flutter/material.dart';
import 'package:flutter/services.dart';

import '../../theme/app_theme.dart';
import '../ui_kit.dart';

/// Primary Go Live / Stop — always visible above the dock; never hideable.
class PinnedGoLiveBar extends StatelessWidget {
  const PinnedGoLiveBar({
    super.key,
    required this.live,
    required this.busy,
    required this.camReady,
    required this.onGoLive,
    required this.onStop,
  });

  final bool live;
  final bool busy;
  final bool camReady;
  final Future<void> Function() onGoLive;
  final Future<void> Function() onStop;

  @override
  Widget build(BuildContext context) {
    return Material(
      elevation: 12,
      shadowColor: Colors.black54,
      borderRadius: BorderRadius.circular(AppSpacing.radiusMd),
      color: Colors.transparent,
      child: CrGoLiveButton(
        live: live,
        busy: busy,
        enabled: camReady,
        onGoLive: () {
          HapticFeedback.mediumImpact();
          unawaited(onGoLive());
        },
        onStop: () {
          HapticFeedback.lightImpact();
          unawaited(onStop());
        },
      ),
    );
  }
}

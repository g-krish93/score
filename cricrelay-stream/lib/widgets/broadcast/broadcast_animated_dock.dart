import 'package:flutter/material.dart';

import '../../theme/app_theme.dart';

/// Slide-in control dock — portrait bottom sheet or landscape side rail.
class BroadcastAnimatedDock extends StatelessWidget {
  const BroadcastAnimatedDock({
    super.key,
    required this.visible,
    required this.landscape,
    required this.panelHeight,
    required this.sideWidth,
    required this.child,
  });

  final bool visible;
  final bool landscape;
  final double panelHeight;
  final double sideWidth;
  final Widget child;

  static const _duration = Duration(milliseconds: 280);
  static const _curve = Curves.easeOutCubic;

  @override
  Widget build(BuildContext context) {
    if (landscape) {
      return AnimatedPositioned(
        duration: _duration,
        curve: _curve,
        right: visible ? 0 : -sideWidth,
        top: 0,
        bottom: 0,
        width: sideWidth,
        child: Material(
          color: AppColors.surfaceElevated,
          elevation: 24,
          child: SingleChildScrollView(
            physics: const ClampingScrollPhysics(),
            child: child,
          ),
        ),
      );
    }

    return AnimatedPositioned(
      duration: _duration,
      curve: _curve,
      left: 0,
      right: 0,
      bottom: visible ? 0 : -panelHeight,
      height: panelHeight,
      child: Material(
        color: AppColors.surfaceElevated,
        elevation: 24,
        child: SingleChildScrollView(
          physics: const ClampingScrollPhysics(),
          child: child,
        ),
      ),
    );
  }
}

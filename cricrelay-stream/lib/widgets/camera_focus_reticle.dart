import 'package:flutter/material.dart';

import '../theme/app_theme.dart';

/// Tap-to-focus reticle shown over the camera preview.
class CameraFocusReticle extends StatelessWidget {
  const CameraFocusReticle({
    super.key,
    required this.center,
    required this.locked,
  });

  final Offset center;
  final bool locked;

  static const _size = 72.0;

  @override
  Widget build(BuildContext context) {
    return Positioned(
      left: center.dx - _size / 2,
      top: center.dy - _size / 2,
      width: _size,
      height: _size,
      child: IgnorePointer(
        child: AnimatedContainer(
          duration: const Duration(milliseconds: 180),
          decoration: BoxDecoration(
            border: Border.all(
              color: locked ? AppColors.warning : AppColors.accentGreen,
              width: locked ? 2.5 : 2,
            ),
            borderRadius: BorderRadius.circular(4),
          ),
          child: locked
              ? const Center(
                  child: Icon(Icons.lock_rounded, color: AppColors.warning, size: 18),
                )
              : null,
        ),
      ),
    );
  }
}

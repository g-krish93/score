import 'dart:ui';

import 'package:flutter/material.dart';
import 'package:flutter/services.dart';

import '../../theme/app_theme.dart';

/// Full-screen camera controls with persistent status — phone camera layout + stream clarity.
class BroadcastCameraUi extends StatelessWidget {
  const BroadcastCameraUi({
    super.key,
    required this.live,
    required this.paused,
    required this.busy,
    required this.camReady,
    required this.destinationReady,
    required this.overlayLocked,
    required this.destinationLabel,
    required this.statusLine,
    required this.onBack,
    required this.onShutter,
    required this.onPause,
    required this.onDestination,
    required this.onOverlay,
    required this.onToggleOverlayLock,
    required this.onScoring,
    required this.onMenu,
    this.onShare,
    this.liveTimer,
    this.onPreviewScaleStart,
    this.onPreviewScaleUpdate,
    this.onPreviewScaleEnd,
    this.onPreviewTapUp,
  });

  final bool live;
  final bool paused;
  final bool busy;
  final bool camReady;
  final bool destinationReady;
  final bool overlayLocked;
  final String destinationLabel;
  final String? statusLine;
  final VoidCallback onBack;
  final VoidCallback onShutter;
  final VoidCallback onPause;
  final VoidCallback onDestination;
  final VoidCallback onOverlay;
  final VoidCallback onToggleOverlayLock;
  final VoidCallback onScoring;
  final VoidCallback onMenu;
  final VoidCallback? onShare;
  final Widget? liveTimer;
  final GestureScaleStartCallback? onPreviewScaleStart;
  final GestureScaleUpdateCallback? onPreviewScaleUpdate;
  final GestureScaleEndCallback? onPreviewScaleEnd;
  final GestureTapUpCallback? onPreviewTapUp;

  @override
  Widget build(BuildContext context) {
    final top = MediaQuery.paddingOf(context).top;
    final bottom = MediaQuery.paddingOf(context).bottom;
    final shutterLabel = live
        ? 'STOP'
        : busy
            ? 'CONNECTING…'
            : camReady
                ? 'GO LIVE'
                : 'PREPARING…';

    return Stack(
      fit: StackFit.expand,
      children: [
        if (onPreviewScaleStart != null || onPreviewTapUp != null)
          Positioned.fill(
            child: GestureDetector(
              behavior: HitTestBehavior.translucent,
              onScaleStart: onPreviewScaleStart,
              onScaleUpdate: onPreviewScaleUpdate,
              onScaleEnd: onPreviewScaleEnd,
              onTapUp: onPreviewTapUp,
            ),
          ),
        Positioned(
          top: 0,
          left: 0,
          right: 0,
          height: top + 88,
          child: IgnorePointer(
            child: DecoratedBox(
              decoration: BoxDecoration(
                gradient: LinearGradient(
                  begin: Alignment.topCenter,
                  end: Alignment.bottomCenter,
                  colors: [
                    Colors.black.withValues(alpha: 0.6),
                    Colors.black.withValues(alpha: 0),
                  ],
                ),
              ),
            ),
          ),
        ),
        Positioned(
          top: 0,
          left: 0,
          right: 0,
          height: top + 88,
          child: Padding(
            padding: EdgeInsets.fromLTRB(8, top + 4, 8, 0),
            child: Column(
              children: [
                Row(
                  children: [
                    _CameraCircleButton(
                      icon: Icons.close_rounded,
                      onPressed: onBack,
                    ),
                    Expanded(
                      child: Center(
                        child: live
                            ? (liveTimer ?? _LivePill(paused: paused))
                            : GestureDetector(
                                onTap: onDestination,
                                child: _DestinationChip(
                                  label: destinationLabel,
                                  ready: destinationReady,
                                ),
                              ),
                      ),
                    ),
                    if (live && onShare != null)
                      _CameraCircleButton(
                        icon: Icons.ios_share_rounded,
                        onPressed: onShare!,
                      )
                    else
                      _CameraCircleButton(
                        icon: Icons.more_horiz_rounded,
                        onPressed: onMenu,
                      ),
                  ],
                ),
                const SizedBox(height: 6),
                if (!live)
                  _PreviewReadyChip(ready: camReady),
              ],
            ),
          ),
        ),
        Positioned(
          left: 0,
          right: 0,
          bottom: 0,
          height: bottom + 220,
          child: IgnorePointer(
            child: DecoratedBox(
              decoration: BoxDecoration(
                gradient: LinearGradient(
                  begin: Alignment.bottomCenter,
                  end: Alignment.topCenter,
                  colors: [
                    Colors.black.withValues(alpha: 0.78),
                    Colors.black.withValues(alpha: 0),
                  ],
                ),
              ),
            ),
          ),
        ),
        Positioned(
          left: 0,
          right: 0,
          bottom: bottom + 12,
          child: Column(
            mainAxisSize: MainAxisSize.min,
            children: [
              if (statusLine != null && statusLine!.isNotEmpty)
                Padding(
                  padding: const EdgeInsets.fromLTRB(20, 0, 20, 10),
                  child: _StatusBanner(message: statusLine!),
                ),
              Padding(
                padding: const EdgeInsets.symmetric(horizontal: 28),
                child: Row(
                  mainAxisAlignment: MainAxisAlignment.spaceEvenly,
                  children: [
                    _CameraToolButton(
                      icon: Icons.cast_rounded,
                      label: 'Dest',
                      active: destinationReady,
                      onPressed: onDestination,
                    ),
                    _CameraToolButton(
                      icon: Icons.layers_outlined,
                      label: 'Board',
                      onPressed: onOverlay,
                    ),
                    _CameraToolButton(
                      icon: overlayLocked ? Icons.lock_rounded : Icons.lock_open_rounded,
                      label: 'Lock',
                      active: overlayLocked,
                      onPressed: onToggleOverlayLock,
                    ),
                    _CameraToolButton(
                      icon: Icons.scoreboard_outlined,
                      label: 'Score',
                      onPressed: onScoring,
                    ),
                  ],
                ),
              ),
              const SizedBox(height: 16),
              Row(
                mainAxisAlignment: MainAxisAlignment.center,
                crossAxisAlignment: CrossAxisAlignment.end,
                children: [
                  SizedBox(
                    width: 72,
                    child: live
                        ? _CameraCircleButton(
                            icon: paused ? Icons.play_arrow_rounded : Icons.pause_rounded,
                            size: 48,
                            onPressed: busy ? onPause : onPause,
                          )
                        : const SizedBox.shrink(),
                  ),
                  Column(
                    mainAxisSize: MainAxisSize.min,
                    children: [
                      _CameraShutterButton(
                        live: live,
                        busy: busy,
                        enabled: camReady && (live || destinationReady),
                        onPressed: (busy || (!live && !camReady))
                            ? null
                            : (!live && !destinationReady)
                                ? onDestination
                                : onShutter,
                      ),
                      const SizedBox(height: 8),
                      Text(
                        shutterLabel,
                        style: TextStyle(
                          color: live
                              ? AppColors.live
                              : (camReady && destinationReady ? Colors.white : Colors.white54),
                          fontSize: 11,
                          fontWeight: FontWeight.w700,
                          letterSpacing: 1.2,
                        ),
                      ),
                    ],
                  ),
                  const SizedBox(width: 72),
                ],
              ),
            ],
          ),
        ),
      ],
    );
  }
}

class _StatusBanner extends StatelessWidget {
  const _StatusBanner({required this.message});

  final String message;

  @override
  Widget build(BuildContext context) {
    return ClipRRect(
      borderRadius: BorderRadius.circular(10),
      child: BackdropFilter(
        filter: ImageFilter.blur(sigmaX: 10, sigmaY: 10),
        child: Container(
          width: double.infinity,
          padding: const EdgeInsets.symmetric(horizontal: 14, vertical: 10),
          decoration: BoxDecoration(
            color: Colors.black.withValues(alpha: 0.45),
            borderRadius: BorderRadius.circular(10),
            border: Border.all(color: Colors.white.withValues(alpha: 0.12)),
          ),
          child: Text(
            message,
            style: const TextStyle(
              color: Colors.white,
              fontSize: 13,
              height: 1.3,
            ),
            textAlign: TextAlign.center,
            maxLines: 3,
            overflow: TextOverflow.ellipsis,
          ),
        ),
      ),
    );
  }
}

class _PreviewReadyChip extends StatelessWidget {
  const _PreviewReadyChip({required this.ready});

  final bool ready;

  @override
  Widget build(BuildContext context) {
    final color = ready ? AppColors.accentGreen : AppColors.warning;
    return Row(
      mainAxisAlignment: MainAxisAlignment.center,
      mainAxisSize: MainAxisSize.min,
      children: [
        Icon(
          ready ? Icons.videocam_rounded : Icons.hourglass_top_rounded,
          color: color,
          size: 14,
        ),
        const SizedBox(width: 6),
        Text(
          ready ? 'Preview ready' : 'Starting camera…',
          style: TextStyle(color: color, fontSize: 12, fontWeight: FontWeight.w600),
        ),
      ],
    );
  }
}

class _LivePill extends StatelessWidget {
  const _LivePill({required this.paused});

  final bool paused;

  @override
  Widget build(BuildContext context) {
    final color = paused ? AppColors.warning : AppColors.live;
    return ClipRRect(
      borderRadius: BorderRadius.circular(AppSpacing.radiusPill),
      child: BackdropFilter(
        filter: ImageFilter.blur(sigmaX: 12, sigmaY: 12),
        child: Container(
          padding: const EdgeInsets.symmetric(horizontal: 12, vertical: 6),
          decoration: BoxDecoration(
            color: Colors.black.withValues(alpha: 0.35),
            borderRadius: BorderRadius.circular(AppSpacing.radiusPill),
            border: Border.all(color: color.withValues(alpha: 0.5)),
          ),
          child: Row(
            mainAxisSize: MainAxisSize.min,
            children: [
              Container(
                width: 8,
                height: 8,
                decoration: BoxDecoration(color: color, shape: BoxShape.circle),
              ),
              const SizedBox(width: 8),
              Text(
                paused ? 'PAUSED' : 'LIVE',
                style: TextStyle(
                  color: color,
                  fontSize: 12,
                  fontWeight: FontWeight.w700,
                  letterSpacing: 0.6,
                ),
              ),
            ],
          ),
        ),
      ),
    );
  }
}

class _DestinationChip extends StatelessWidget {
  const _DestinationChip({required this.label, required this.ready});

  final String label;
  final bool ready;

  @override
  Widget build(BuildContext context) {
    return ClipRRect(
      borderRadius: BorderRadius.circular(AppSpacing.radiusPill),
      child: BackdropFilter(
        filter: ImageFilter.blur(sigmaX: 12, sigmaY: 12),
        child: Container(
          padding: const EdgeInsets.symmetric(horizontal: 14, vertical: 6),
          decoration: BoxDecoration(
            color: Colors.black.withValues(alpha: 0.35),
            borderRadius: BorderRadius.circular(AppSpacing.radiusPill),
            border: Border.all(
              color: ready ? AppColors.accentGreen.withValues(alpha: 0.5) : AppColors.warning.withValues(alpha: 0.5),
            ),
          ),
          child: Row(
            mainAxisSize: MainAxisSize.min,
            children: [
              Icon(
                ready ? Icons.check_circle_rounded : Icons.warning_amber_rounded,
                size: 14,
                color: ready ? AppColors.accentGreen : AppColors.warning,
              ),
              const SizedBox(width: 6),
              Text(
                label,
                style: const TextStyle(
                  color: Colors.white,
                  fontSize: 13,
                  fontWeight: FontWeight.w600,
                ),
              ),
            ],
          ),
        ),
      ),
    );
  }
}

class _CameraCircleButton extends StatelessWidget {
  const _CameraCircleButton({
    required this.icon,
    required this.onPressed,
    this.size = 44,
  });

  final IconData icon;
  final VoidCallback onPressed;
  final double size;

  @override
  Widget build(BuildContext context) {
    return Material(
      color: Colors.black.withValues(alpha: 0.35),
      shape: const CircleBorder(),
      clipBehavior: Clip.antiAlias,
      child: InkWell(
        onTap: () {
          HapticFeedback.selectionClick();
          onPressed();
        },
        child: SizedBox(
          width: size,
          height: size,
          child: Icon(icon, color: Colors.white, size: size * 0.5),
        ),
      ),
    );
  }
}

class _CameraToolButton extends StatelessWidget {
  const _CameraToolButton({
    required this.icon,
    required this.label,
    required this.onPressed,
    this.active = false,
  });

  final IconData icon;
  final String label;
  final VoidCallback onPressed;
  final bool active;

  @override
  Widget build(BuildContext context) {
    return InkWell(
      onTap: () {
        HapticFeedback.selectionClick();
        onPressed();
      },
      borderRadius: BorderRadius.circular(12),
      child: Padding(
        padding: const EdgeInsets.symmetric(horizontal: 4, vertical: 6),
        child: Column(
          mainAxisSize: MainAxisSize.min,
          children: [
            Icon(
              icon,
              color: active ? AppColors.accentGreen : Colors.white,
              size: 26,
            ),
            const SizedBox(height: 4),
            Text(
              label,
              style: TextStyle(
                color: active ? AppColors.accentGreen : Colors.white70,
                fontSize: 11,
                fontWeight: FontWeight.w500,
              ),
            ),
          ],
        ),
      ),
    );
  }
}

class _CameraShutterButton extends StatelessWidget {
  const _CameraShutterButton({
    required this.live,
    required this.busy,
    required this.enabled,
    required this.onPressed,
  });

  final bool live;
  final bool busy;
  final bool enabled;
  final VoidCallback? onPressed;

  @override
  Widget build(BuildContext context) {
    const outer = 76.0;
    const ring = 4.0;

    return GestureDetector(
      onTap: onPressed == null
          ? null
          : () {
              HapticFeedback.mediumImpact();
              onPressed!();
            },
      child: SizedBox(
        width: outer,
        height: outer,
        child: Stack(
          alignment: Alignment.center,
          children: [
            Container(
              width: outer,
              height: outer,
              decoration: BoxDecoration(
                shape: BoxShape.circle,
                border: Border.all(color: Colors.white, width: ring),
              ),
            ),
            if (busy)
              const SizedBox(
                width: 32,
                height: 32,
                child: CircularProgressIndicator(strokeWidth: 3, color: Colors.white),
              )
            else if (live)
              Container(
                width: 28,
                height: 28,
                decoration: BoxDecoration(
                  color: AppColors.live,
                  borderRadius: BorderRadius.circular(6),
                ),
              )
            else
              Container(
                width: outer - ring * 2 - 8,
                height: outer - ring * 2 - 8,
                decoration: BoxDecoration(
                  shape: BoxShape.circle,
                  color: enabled ? AppColors.live : Colors.white38,
                ),
              ),
          ],
        ),
      ),
    );
  }
}


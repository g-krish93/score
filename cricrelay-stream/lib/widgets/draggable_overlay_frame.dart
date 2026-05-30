import 'dart:async';

import 'package:flutter/material.dart';

import '../models/overlay_layout_prefs.dart';
import '../theme/app_theme.dart';

/// Draggable + corner-resizable scoreboard frame on the camera preview.
class DraggableOverlayFrame extends StatefulWidget {
  const DraggableOverlayFrame({
    super.key,
    required this.prefs,
    required this.locked,
    required this.onChanged,
    this.onDragEnd,
  });

  final OverlayLayoutPrefs prefs;
  final bool locked;
  final ValueChanged<OverlayLayoutPrefs> onChanged;
  final VoidCallback? onDragEnd;

  @override
  State<DraggableOverlayFrame> createState() => _DraggableOverlayFrameState();
}

class _DraggableOverlayFrameState extends State<DraggableOverlayFrame> {
  Timer? _throttle;

  void _emit(OverlayLayoutPrefs next) {
    widget.onChanged(next);
    _throttle?.cancel();
    _throttle = Timer(const Duration(milliseconds: 200), () {
      widget.onDragEnd?.call();
    });
  }

  @override
  void dispose() {
    _throttle?.cancel();
    super.dispose();
  }

  @override
  Widget build(BuildContext context) {
    return LayoutBuilder(
      builder: (context, constraints) {
        final maxW = constraints.maxWidth;
        final maxH = constraints.maxHeight;
        if (maxW < 64 || maxH < 64) return const SizedBox.shrink();

        final w = (maxW * widget.prefs.widthFraction).clamp(80.0, maxW);
        final h = (maxH * widget.prefs.heightFraction).clamp(48.0, maxH * 0.55);
        final left = (widget.prefs.anchorX * maxW - w / 2).clamp(0.0, maxW - w);
        final top = (widget.prefs.anchorY * maxH - h / 2).clamp(0.0, maxH - h);

        final borderColor = widget.locked ? Colors.white24 : AppColors.accentGreen;

        Widget frame = DecoratedBox(
          decoration: BoxDecoration(
            border: Border.all(color: borderColor, width: 2),
            borderRadius: BorderRadius.circular(8),
            color: Colors.black26,
          ),
          child: widget.locked
              ? null
              : const Center(
                  child: Icon(Icons.open_with, color: Colors.white70, size: 20),
                ),
        );

        if (!widget.locked) {
          frame = GestureDetector(
            onPanUpdate: (d) {
              final nx = ((left + w / 2 + d.delta.dx) / maxW).clamp(0.05, 0.95);
              final ny = ((top + h / 2 + d.delta.dy) / maxH).clamp(0.05, 0.95);
              _emit(widget.prefs.copyWith(anchorX: nx, anchorY: ny));
            },
            child: frame,
          );
        }

        return Stack(
          clipBehavior: Clip.none,
          children: [
            Positioned(left: left, top: top, width: w, height: h, child: frame),
            if (!widget.locked)
              Positioned(
                left: left + w - 14,
                top: top + h - 14,
                child: GestureDetector(
                  onPanUpdate: (d) {
                    final nw = (w + d.delta.dx).clamp(80.0, maxW * 0.95);
                    final nh = (h + d.delta.dy).clamp(48.0, maxH * 0.55);
                    _emit(
                      widget.prefs.copyWith(
                        widthFraction: (nw / maxW).clamp(0.25, 0.95),
                        heightFraction: (nh / maxH).clamp(0.12, 0.45),
                      ),
                    );
                  },
                  child: Container(
                    width: 28,
                    height: 28,
                    alignment: Alignment.center,
                    child: const Icon(Icons.open_in_full, color: Colors.white, size: 18),
                  ),
                ),
              ),
          ],
        );
      },
    );
  }
}

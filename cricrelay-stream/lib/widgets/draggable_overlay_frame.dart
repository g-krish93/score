import 'dart:async';

import 'package:flutter/material.dart';

import '../models/overlay_layout_prefs.dart';
import '../theme/app_theme.dart';

/// Draggable + corner-resizable scoreboard frame on the camera preview.
/// Uses local state while dragging so the parent broadcast screen does not rebuild every pixel.
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
  late OverlayLayoutPrefs _local;
  Timer? _syncDebounce;

  @override
  void initState() {
    super.initState();
    _local = widget.prefs;
  }

  @override
  void didUpdateWidget(covariant DraggableOverlayFrame oldWidget) {
    super.didUpdateWidget(oldWidget);
    if (widget.prefs != oldWidget.prefs) {
      _local = widget.prefs;
    }
  }

  void _updateLocal(OverlayLayoutPrefs next, {bool persist = false}) {
    setState(() => _local = next);
    if (persist) {
      widget.onChanged(next);
      widget.onDragEnd?.call();
      return;
    }
    _syncDebounce?.cancel();
    _syncDebounce = Timer(const Duration(milliseconds: 250), () {
      widget.onChanged(next);
      widget.onDragEnd?.call();
    });
  }

  @override
  void dispose() {
    _syncDebounce?.cancel();
    super.dispose();
  }

  @override
  Widget build(BuildContext context) {
    return LayoutBuilder(
      builder: (context, constraints) {
        final maxW = constraints.maxWidth;
        final maxH = constraints.maxHeight;
        if (maxW < 64 || maxH < 64) return const SizedBox.shrink();

        final bounds = _local.frameRect(maxW, maxH);
        final w = bounds.width;
        final h = bounds.height;
        final left = bounds.left;
        final top = bounds.top;

        final borderColor = widget.locked ? AppColors.overlayFrameLocked : AppColors.overlayFrame;

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
            onPanEnd: (_) {
              _syncDebounce?.cancel();
              widget.onChanged(_local);
              widget.onDragEnd?.call();
            },
            onPanUpdate: (d) {
              final nx = ((left + w / 2 + d.delta.dx) / maxW).clamp(0.05, 0.95);
              final newTop = (top + d.delta.dy).clamp(0.0, maxH - h);
              final newBottom = (maxH - newTop - h).clamp(0.0, 48.0);
              _updateLocal(_local.copyWith(anchorX: nx, bottomMargin: newBottom));
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
                  onPanEnd: (_) {
                    _syncDebounce?.cancel();
                    widget.onChanged(_local);
                    widget.onDragEnd?.call();
                  },
                  onPanUpdate: (d) {
                    final nw = (w + d.delta.dx).clamp(80.0, maxW * 0.95);
                    final nh = (h + d.delta.dy).clamp(48.0, maxH * 0.55);
                    _updateLocal(
                      _local.copyWith(
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

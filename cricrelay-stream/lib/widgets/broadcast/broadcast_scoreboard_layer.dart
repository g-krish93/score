import 'dart:typed_data';

import 'package:flutter/material.dart';
import 'package:webview_flutter/webview_flutter.dart';

import '../../models/overlay_layout_prefs.dart';
import '../../widgets/draggable_overlay_frame.dart';
import '../../widgets/scoreboard_strip_webview.dart';

/// Scoreboard on preview — native PNG bitmap (Android) or WebView fallback (iOS / legacy).
class BroadcastScoreboardLayer extends StatelessWidget {
  const BroadcastScoreboardLayer({
    super.key,
    required this.prefs,
    required this.locked,
    required this.useNativeBitmap,
    required this.overlayBytes,
    required this.onChanged,
    required this.onDragEnd,
    this.webController,
  });

  final OverlayLayoutPrefs prefs;
  final bool locked;
  final bool useNativeBitmap;
  final Uint8List? overlayBytes;
  final ValueChanged<OverlayLayoutPrefs> onChanged;
  final VoidCallback onDragEnd;
  final WebViewController? webController;

  @override
  Widget build(BuildContext context) {
    final hasNativeBytes = useNativeBitmap && overlayBytes != null && overlayBytes!.isNotEmpty;
    Widget preview;
    if (hasNativeBytes) {
      preview = IgnorePointer(
        ignoring: !locked,
        child: Image.memory(
          overlayBytes!,
          fit: BoxFit.contain,
          gaplessPlayback: true,
          filterQuality: FilterQuality.medium,
        ),
      );
    } else if (webController != null) {
      preview = IgnorePointer(
        ignoring: !locked,
        child: ScoreboardStripWebView(controller: webController!),
      );
    } else {
      return const SizedBox.shrink();
    }

    return DraggableOverlayFrame(
      prefs: prefs,
      locked: locked,
      onChanged: onChanged,
      onDragEnd: onDragEnd,
      preview: preview,
    );
  }
}

import 'package:flutter/material.dart';

import '../models/overlay_layout_prefs.dart';
import '../theme/app_theme.dart';
import 'ui_kit.dart';

Future<OverlayLayoutPrefs?> showOverlayLayoutSheet({
  required BuildContext context,
  required OverlayLayoutPrefs initial,
}) async {
  return showCrBottomSheet<OverlayLayoutPrefs>(
    context: context,
    child: _OverlayLayoutEditor(initial: initial),
  );
}

class _OverlayLayoutEditor extends StatefulWidget {
  const _OverlayLayoutEditor({required this.initial});

  final OverlayLayoutPrefs initial;

  @override
  State<_OverlayLayoutEditor> createState() => _OverlayLayoutEditorState();
}

class _OverlayLayoutEditorState extends State<_OverlayLayoutEditor> {
  late OverlayLayoutPrefs _p;

  @override
  void initState() {
    super.initState();
    _p = widget.initial;
  }

  @override
  Widget build(BuildContext context) {
    return SingleChildScrollView(
      child: Column(
        mainAxisSize: MainAxisSize.min,
        crossAxisAlignment: CrossAxisAlignment.stretch,
        children: [
          const CrBottomSheetHandle(),
          const CrSheetHeader(
            title: 'Scoreboard overlay',
            subtitle:
                'Drag the scoreboard on the preview to move it. Resize from the corner handle, then lock before Go Live.',
          ),
          Padding(
            padding: const EdgeInsets.symmetric(horizontal: 20),
            child: Column(
              crossAxisAlignment: CrossAxisAlignment.stretch,
              children: [
                Text('On-stream size: ${_p.size}', style: appTextTheme.labelLarge),
                Slider(
                  value: _p.size.toDouble(),
                  min: 1,
                  max: 5,
                  divisions: 4,
                  label: '${_p.size}',
                  onChanged: (v) => setState(() => _p = _p.copyWith(size: v.round())),
                ),
                Text(
                  'Preview strip: ${(_p.heightFraction * 100).round()}%',
                  style: appTextTheme.labelLarge,
                ),
                Slider(
                  value: _p.heightFraction,
                  min: 0.12,
                  max: 0.45,
                  divisions: 33,
                  onChanged: (v) => setState(() => _p = _p.copyWith(heightFraction: v)),
                ),
                const SizedBox(height: AppSpacing.sm),
                Text('Theme', style: appTextTheme.labelLarge),
                const SizedBox(height: AppSpacing.sm),
                Wrap(
                  spacing: 8,
                  children: [
                    for (final t in ['classic', 'neon', 'minimal'])
                      FilterChip(
                        label: Text(t),
                        selected: _p.theme == t,
                        onSelected: (_) => setState(() => _p = _p.copyWith(theme: t)),
                      ),
                  ],
                ),
                Text(
                  'Preview width: ${(_p.widthFraction * 100).round()}%',
                  style: appTextTheme.labelLarge,
                ),
                Slider(
                  value: _p.widthFraction,
                  min: 0.25,
                  max: 0.95,
                  divisions: 14,
                  label: '${(_p.widthFraction * 100).round()}%',
                  onChanged: (v) => setState(() => _p = _p.copyWith(widthFraction: v)),
                ),
                const SizedBox(height: AppSpacing.sm),
                SwitchListTile(
                  contentPadding: EdgeInsets.zero,
                  title: const Text('Steady stream (EIS)'),
                  subtitle: const Text('Digital stabilization for windy conditions — device support varies.'),
                  value: _p.videoStabilization,
                  onChanged: (v) => setState(() => _p = _p.copyWith(videoStabilization: v)),
                ),
                SwitchListTile(
                  contentPadding: EdgeInsets.zero,
                  title: const Text('Keep screen on while live'),
                  subtitle: const Text(
                    'Off saves battery — streaming can continue with screen off.',
                  ),
                  value: _p.keepScreenOn,
                  onChanged: (v) => setState(() => _p = _p.copyWith(keepScreenOn: v)),
                ),
                const SizedBox(height: AppSpacing.md),
                FilledButton(
                  onPressed: () => Navigator.pop(context, _p),
                  child: const Text('Apply'),
                ),
                SizedBox(height: 16 + MediaQuery.of(context).padding.bottom),
              ],
            ),
          ),
        ],
      ),
    );
  }
}

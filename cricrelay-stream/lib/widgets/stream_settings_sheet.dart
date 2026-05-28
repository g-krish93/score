import 'package:flutter/material.dart';
import 'package:shared_preferences/shared_preferences.dart';

import '../models/stream_quality.dart';
import '../theme/app_theme.dart';
import 'ui_kit.dart';

const kStreamQualityPref = 'stream_video_quality_id';

Future<StreamQualityProfile> loadStreamQualityProfile() async {
  final prefs = await SharedPreferences.getInstance();
  return StreamQualityProfile.fromId(prefs.getString(kStreamQualityPref));
}

Future<void> saveStreamQualityProfile(StreamQualityProfile profile) async {
  final prefs = await SharedPreferences.getInstance();
  await prefs.setString(kStreamQualityPref, profile.id);
}

Future<void> showStreamSettingsSheet({
  required BuildContext context,
  required StreamQualityProfile initial,
  required ValueChanged<StreamQualityProfile> onChanged,
}) async {
  await showCrBottomSheet<void>(
    context: context,
    child: _StreamSettingsBody(initial: initial, onChanged: onChanged),
  );
}

class _StreamSettingsBody extends StatefulWidget {
  const _StreamSettingsBody({required this.initial, required this.onChanged});

  final StreamQualityProfile initial;
  final ValueChanged<StreamQualityProfile> onChanged;

  @override
  State<_StreamSettingsBody> createState() => _StreamSettingsBodyState();
}

class _StreamSettingsBodyState extends State<_StreamSettingsBody> {
  late StreamQualityProfile _selected;

  @override
  void initState() {
    super.initState();
    _selected = widget.initial;
  }

  Future<void> _pick(StreamQualityProfile p) async {
    setState(() => _selected = p);
    await saveStreamQualityProfile(p);
    widget.onChanged(p);
    if (mounted) Navigator.pop(context);
  }

  @override
  Widget build(BuildContext context) {
    return Column(
      mainAxisSize: MainAxisSize.min,
      children: [
        const CrBottomSheetHandle(),
        const CrSheetHeader(
          title: 'Stream quality',
          subtitle: 'Match your upload speed. Lower quality reduces dropouts on weak mobile data.',
        ),
        ...StreamQualityProfile.all.map((p) {
          final on = p.id == _selected.id;
          return ListTile(
            leading: Icon(
              on ? Icons.check_circle : Icons.circle_outlined,
              color: on ? AppColors.primary : AppColors.onBackgroundDim,
            ),
            title: Text('${p.label} · ${p.width}×${p.height}'),
            subtitle: Text(p.hint),
            onTap: () => _pick(p),
          );
        }),
        SizedBox(height: 16 + MediaQuery.of(context).padding.bottom),
      ],
    );
  }
}

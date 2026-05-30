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
  bool live = false,
}) async {
  await showCrBottomSheet<void>(
    context: context,
    child: _StreamSettingsBody(initial: initial, onChanged: onChanged, live: live),
  );
}

class _StreamSettingsBody extends StatefulWidget {
  const _StreamSettingsBody({
    required this.initial,
    required this.onChanged,
    this.live = false,
  });

  final StreamQualityProfile initial;
  final ValueChanged<StreamQualityProfile> onChanged;
  final bool live;

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
    if (widget.live) {
      if (mounted) {
        ScaffoldMessenger.of(context).showSnackBar(
          const SnackBar(
            content: Text('Stop or pause the stream to change quality. Quality applies on the next Go Live.'),
          ),
        );
      }
      return;
    }
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
        CrSheetHeader(
          title: 'Stream quality',
          subtitle: widget.live
              ? 'Quality is locked while you are live. End the broadcast to change it for the next stream.'
              : 'Match your upload speed. Lower quality reduces dropouts on weak mobile data.',
        ),
        if (widget.live)
          const Padding(
            padding: EdgeInsets.fromLTRB(20, 0, 20, 8),
            child: CrInfoBanner(
              title: 'Live stream active',
              body: 'Changing resolution mid-stream can crash the encoder. Adjust quality before or after your broadcast.',
              accentColor: AppColors.warning,
            ),
          ),
        ...StreamQualityProfile.all.map((p) {
          final on = p.id == _selected.id;
          return ListTile(
            enabled: !widget.live,
            leading: Icon(
              on ? Icons.check_circle : Icons.circle_outlined,
              color: on ? AppColors.primary : AppColors.onBackgroundDim,
            ),
            title: Text('${p.label} · ${p.width}×${p.height}'),
            subtitle: Text(p.hint),
            onTap: widget.live ? null : () => _pick(p),
          );
        }),
        SizedBox(height: 16 + MediaQuery.of(context).padding.bottom),
      ],
    );
  }
}

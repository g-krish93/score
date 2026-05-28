import 'package:flutter/material.dart';
import 'package:shared_preferences/shared_preferences.dart';

import '../models/stream_quality.dart';

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
  await showModalBottomSheet<void>(
    context: context,
    backgroundColor: const Color(0xFF141b2e),
    shape: const RoundedRectangleBorder(
      borderRadius: BorderRadius.vertical(top: Radius.circular(16)),
    ),
    builder: (ctx) {
      return _StreamSettingsBody(initial: initial, onChanged: onChanged);
    },
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
  }

  @override
  Widget build(BuildContext context) {
    return Padding(
      padding: EdgeInsets.only(
        left: 20,
        right: 20,
        top: 16,
        bottom: 16 + MediaQuery.of(context).padding.bottom,
      ),
      child: Column(
        mainAxisSize: MainAxisSize.min,
        crossAxisAlignment: CrossAxisAlignment.stretch,
        children: [
          const Text(
            'Stream quality',
            style: TextStyle(fontSize: 18, fontWeight: FontWeight.bold),
          ),
          const SizedBox(height: 6),
          const Text(
            'Pick what your upload connection can handle. Lower = fewer dropouts on weak mobile data.',
            style: TextStyle(color: Colors.white70, fontSize: 13),
          ),
          const SizedBox(height: 12),
          ...StreamQualityProfile.all.map((p) {
            final on = p.id == _selected.id;
            return ListTile(
              contentPadding: EdgeInsets.zero,
              leading: Icon(
                on ? Icons.radio_button_checked : Icons.radio_button_off,
                color: on ? const Color(0xFF22D3A8) : Colors.white54,
              ),
              title: Text('${p.label} — ${p.width}×${p.height}'),
              subtitle: Text(p.hint),
              onTap: () => _pick(p),
            );
          }),
        ],
      ),
    );
  }
}

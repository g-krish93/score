import 'package:flutter/material.dart';
import 'package:flutter/services.dart';

import '../services/api.dart';
import '../theme/app_theme.dart';
import 'ui_kit.dart';

Future<void> showStreamManagementSheet({
  required BuildContext context,
  required CricRelayApi api,
  required StreamMatch match,
  VoidCallback? onChanged,
  VoidCallback? onDeleted,
}) async {
  await showCrBottomSheet<void>(
    context: context,
    child: _StreamManagementBody(
      api: api,
      match: match,
      onChanged: onChanged,
      onDeleted: onDeleted,
    ),
  );
}

class _StreamManagementBody extends StatefulWidget {
  const _StreamManagementBody({
    required this.api,
    required this.match,
    this.onChanged,
    this.onDeleted,
  });

  final CricRelayApi api;
  final StreamMatch match;
  final VoidCallback? onChanged;
  final VoidCallback? onDeleted;

  @override
  State<_StreamManagementBody> createState() => _StreamManagementBodyState();
}

class _StreamManagementBodyState extends State<_StreamManagementBody> {
  late final TextEditingController _labelCtrl;
  bool _busy = false;
  String? _error;
  late StreamMatch _match;

  @override
  void initState() {
    super.initState();
    _match = widget.match;
    _labelCtrl = TextEditingController(text: _match.label);
  }

  @override
  void dispose() {
    _labelCtrl.dispose();
    super.dispose();
  }

  Future<void> _run(Future<void> Function() action) async {
    setState(() {
      _busy = true;
      _error = null;
    });
    try {
      await action();
      widget.onChanged?.call();
    } catch (e) {
      setState(() => _error = e.toString().replaceFirst('Exception: ', ''));
    } finally {
      if (mounted) setState(() => _busy = false);
    }
  }

  Future<void> _rename() async {
    final label = _labelCtrl.text.trim();
    if (label.isEmpty) return;
    await _run(() => widget.api.renameStream(_match.slug, label));
    if (mounted) {
      setState(() => _match = StreamMatch(
            slug: _match.slug,
            label: label,
            overlayEmbedUrl: _match.overlayEmbedUrl,
            relaySource: _match.relaySource,
            relayPaused: _match.relayPaused,
            scoringMode: _match.scoringMode,
            scoringActive: _match.scoringActive,
            scoringStale: _match.scoringStale,
            isLive: _match.isLive,
            broadcast: _match.broadcast,
          ));
    }
  }

  Future<void> _toggleRelayPause(bool paused) async {
    await _run(() => widget.api.setRelayPause(_match.slug, paused: paused));
    if (mounted) {
      setState(() => _match = StreamMatch(
            slug: _match.slug,
            label: _match.label,
            overlayEmbedUrl: _match.overlayEmbedUrl,
            relaySource: _match.relaySource,
            relayPaused: paused,
            scoringMode: _match.scoringMode,
            scoringActive: _match.scoringActive,
            scoringStale: _match.scoringStale,
            isLive: _match.isLive,
            broadcast: _match.broadcast,
          ));
    }
  }

  Future<void> _delete() async {
    if (_match.broadcast.isStreaming) {
      setState(() => _error = 'Stop the broadcast before deleting this stream.');
      return;
    }
    final ok = await showCrConfirmDialog(
      context: context,
      title: 'Delete stream?',
      message: 'Remove "${_match.label}" permanently? This cannot be undone.',
      confirmLabel: 'Delete',
      destructive: true,
    );
    if (ok != true || !mounted) return;
    await _run(() => widget.api.deleteStream(_match.slug));
    if (mounted) {
      Navigator.pop(context);
      widget.onDeleted?.call();
    }
  }

  void _copyOverlay() {
    Clipboard.setData(ClipboardData(text: _match.overlayEmbedUrl));
    ScaffoldMessenger.of(context).showSnackBar(
      const SnackBar(content: Text('Overlay URL copied')),
    );
  }

  @override
  Widget build(BuildContext context) {
    final canPauseRelay = _match.scoringMode == 'auto' || _match.scoringMode == 'ble';
    return Padding(
      padding: const EdgeInsets.fromLTRB(AppSpacing.lg, 0, AppSpacing.lg, AppSpacing.lg),
      child: Column(
        mainAxisSize: MainAxisSize.min,
        crossAxisAlignment: CrossAxisAlignment.stretch,
        children: [
          Text('Stream settings', style: appTextTheme.headlineSmall),
          const SizedBox(height: 4),
          Text(_match.slug, style: appTextTheme.bodySmall),
          if (_error != null) ...[
            const SizedBox(height: AppSpacing.sm),
            CrErrorBanner(message: _error!),
          ],
          const SizedBox(height: AppSpacing.md),
          TextField(
            controller: _labelCtrl,
            decoration: const InputDecoration(labelText: 'Display name'),
            enabled: !_busy,
          ),
          const SizedBox(height: AppSpacing.sm),
          FilledButton(
            onPressed: _busy ? null : _rename,
            child: const Text('Save name'),
          ),
          const SizedBox(height: AppSpacing.md),
          if (canPauseRelay) ...[
            ListTile(
              contentPadding: EdgeInsets.zero,
              title: const Text('Auto-sync pause'),
              subtitle: Text(
                _match.relayPaused
                    ? 'Play-Cricket sync is paused — scores won\'t update automatically.'
                    : 'Pause automatic Play-Cricket sync (does not stop RTMP broadcast).',
              ),
              trailing: Switch(
                value: _match.relayPaused,
                onChanged: _busy ? null : (v) => _toggleRelayPause(v),
              ),
            ),
          ],
          OutlinedButton.icon(
            onPressed: _busy ? null : _copyOverlay,
            icon: const Icon(Icons.link_rounded),
            label: const Text('Copy overlay URL'),
          ),
          const SizedBox(height: AppSpacing.sm),
          OutlinedButton.icon(
            onPressed: _busy || _match.broadcast.isStreaming ? null : _delete,
            icon: const Icon(Icons.delete_outline_rounded, color: AppColors.error),
            label: const Text('Delete stream', style: TextStyle(color: AppColors.error)),
          ),
        ],
      ),
    );
  }
}

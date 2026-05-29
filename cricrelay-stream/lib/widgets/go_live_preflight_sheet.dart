import 'package:connectivity_plus/connectivity_plus.dart';
import 'package:flutter/material.dart';

import '../services/rtmp_platform.dart';
import '../theme/app_theme.dart';
import 'ui_kit.dart';

class GoLivePreflightResult {
  const GoLivePreflightResult({
    required this.cameraReady,
    required this.streamKeySet,
    required this.networkOk,
    required this.overlayLocked,
  });

  final bool cameraReady;
  final bool streamKeySet;
  final bool networkOk;
  final bool overlayLocked;

  bool get canGoLive => cameraReady && streamKeySet && networkOk;
}

/// Pre-flight checklist before starting RTMP (connectivity, camera, stream key).
Future<bool> showGoLivePreflightSheet({
  required BuildContext context,
  required bool cameraReady,
  required bool streamKeySet,
  required bool overlayLocked,
}) async {
  final connectivity = Connectivity();
  var networkOk = false;
  try {
    final results = await connectivity.checkConnectivity();
    networkOk = results.any((r) => r != ConnectivityResult.none);
  } catch (_) {
    networkOk = true;
  }

  if (!context.mounted) return false;

  var nativeReady = cameraReady;
  if (!nativeReady) {
    try {
      nativeReady = await RtmpPlatform.isCameraReady;
    } catch (_) {}
  }

  final result = await showModalBottomSheet<bool>(
    context: context,
    isScrollControlled: true,
    backgroundColor: AppColors.surface,
    shape: const RoundedRectangleBorder(
      borderRadius: BorderRadius.vertical(top: Radius.circular(AppSpacing.radiusXl)),
    ),
    builder: (ctx) {
      return _GoLivePreflightBody(
        initial: GoLivePreflightResult(
          cameraReady: nativeReady,
          streamKeySet: streamKeySet,
          networkOk: networkOk,
          overlayLocked: overlayLocked,
        ),
      );
    },
  );
  return result == true;
}

class _GoLivePreflightBody extends StatefulWidget {
  const _GoLivePreflightBody({required this.initial});

  final GoLivePreflightResult initial;

  @override
  State<_GoLivePreflightBody> createState() => _GoLivePreflightBodyState();
}

class _GoLivePreflightBodyState extends State<_GoLivePreflightBody> {
  late bool _cameraReady;
  late bool _streamKeySet;
  late bool _networkOk;
  late bool _overlayLocked;

  @override
  void initState() {
    super.initState();
    _cameraReady = widget.initial.cameraReady;
    _streamKeySet = widget.initial.streamKeySet;
    _networkOk = widget.initial.networkOk;
    _overlayLocked = widget.initial.overlayLocked;
  }

  bool get _canGoLive => _cameraReady && _streamKeySet && _networkOk;

  @override
  Widget build(BuildContext context) {
    return Padding(
      padding: EdgeInsets.fromLTRB(
        AppSpacing.md,
        AppSpacing.md,
        AppSpacing.md,
        AppSpacing.md + MediaQuery.of(context).padding.bottom,
      ),
      child: Column(
        mainAxisSize: MainAxisSize.min,
        crossAxisAlignment: CrossAxisAlignment.stretch,
        children: [
          Center(
            child: Container(
              width: 40,
              height: 4,
              decoration: BoxDecoration(
                color: AppColors.surfaceVariant,
                borderRadius: BorderRadius.circular(2),
              ),
            ),
          ),
          const SizedBox(height: AppSpacing.md),
          Text('Ready to go live?', style: appTextTheme.headlineSmall, textAlign: TextAlign.center),
          const SizedBox(height: AppSpacing.sm),
          Text(
            'Check these before you start broadcasting.',
            style: appTextTheme.bodyMedium,
            textAlign: TextAlign.center,
          ),
          const SizedBox(height: AppSpacing.lg),
          _CheckRow(label: 'Camera preview ready', ok: _cameraReady),
          _CheckRow(label: 'Stream key set', ok: _streamKeySet),
          _CheckRow(label: 'Internet connection', ok: _networkOk),
          _CheckRow(
            label: 'Overlay locked',
            ok: _overlayLocked,
            optional: true,
            hint: _overlayLocked ? null : 'Recommended — lock overlay to avoid accidental taps',
          ),
          const SizedBox(height: AppSpacing.lg),
          FilledButton(
            onPressed: _canGoLive ? () => Navigator.pop(context, true) : null,
            child: const Text('Go Live'),
          ),
          const SizedBox(height: AppSpacing.sm),
          TextButton(
            onPressed: () => Navigator.pop(context, false),
            child: const Text('Cancel'),
          ),
        ],
      ),
    );
  }
}

class _CheckRow extends StatelessWidget {
  const _CheckRow({
    required this.label,
    required this.ok,
    this.optional = false,
    this.hint,
  });

  final String label;
  final bool ok;
  final bool optional;
  final String? hint;

  @override
  Widget build(BuildContext context) {
    return Padding(
      padding: const EdgeInsets.only(bottom: AppSpacing.sm),
      child: Row(
        crossAxisAlignment: CrossAxisAlignment.start,
        children: [
          CrStatusChip(label: ok ? 'OK' : (optional ? 'Tip' : 'Fix'), ok: ok),
          const SizedBox(width: AppSpacing.sm),
          Expanded(
            child: Column(
              crossAxisAlignment: CrossAxisAlignment.start,
              children: [
                Text(label, style: const TextStyle(fontWeight: FontWeight.w600)),
                if (hint != null) ...[
                  const SizedBox(height: 4),
                  Text(hint!, style: appTextTheme.bodySmall),
                ],
              ],
            ),
          ),
        ],
      ),
    );
  }
}

import 'dart:async';

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
    required this.orientationLabel,
    this.orientationChanged = false,
  });

  final bool cameraReady;
  final bool streamKeySet;
  final bool networkOk;
  final bool overlayLocked;
  final String orientationLabel;
  final bool orientationChanged;

  bool get isLandscape => orientationLabel == 'landscape';

  /// Portrait and landscape are both allowed; checks are camera, key, and network.
  bool get canGoLive => cameraReady && streamKeySet && networkOk;
}

Future<bool> _checkNetworkAvailable() async {
  try {
    final results = await Connectivity().checkConnectivity();
    return results.any((r) => r != ConnectivityResult.none);
  } catch (_) {
    return true;
  }
}

Future<bool> _resolveCameraReady({
  required bool cameraReady,
  Future<bool> Function()? probe,
}) async {
  if (cameraReady) return true;
  if (probe != null) {
    try {
      return await probe();
    } catch (_) {}
  }
  try {
    return await RtmpPlatform.isCameraReady;
  } catch (_) {
    return false;
  }
}

/// Pre-flight checklist before starting RTMP (connectivity, camera, stream key).
Future<bool> showGoLivePreflightSheet({
  required BuildContext context,
  required bool cameraReady,
  required bool streamKeySet,
  required bool overlayLocked,
  required String orientationLabel,
  bool orientationChanged = false,
  Future<bool> Function()? resolveCameraReady,
  String Function()? resolveOrientationLabel,
}) async {
  final networkOk = await _checkNetworkAvailable();
  if (!context.mounted) return false;

  final nativeReady = await _resolveCameraReady(
    cameraReady: cameraReady,
    probe: resolveCameraReady,
  );
  if (!context.mounted) return false;

  final result = await showModalBottomSheet<bool>(
    context: context,
    isScrollControlled: true,
    backgroundColor: AppColors.surfaceElevated,
    shape: const RoundedRectangleBorder(
      borderRadius: BorderRadius.vertical(top: Radius.circular(AppSpacing.radiusXl)),
    ),
    builder: (ctx) {
      return GoLivePreflightSheetContent(
        initial: GoLivePreflightResult(
          cameraReady: nativeReady,
          streamKeySet: streamKeySet,
          networkOk: networkOk,
          overlayLocked: overlayLocked,
          orientationLabel: orientationLabel,
          orientationChanged: orientationChanged,
        ),
        resolveCameraReady: resolveCameraReady,
        resolveOrientationLabel: resolveOrientationLabel,
      );
    },
  );
  return result == true;
}

/// Checklist body (public for widget tests).
class GoLivePreflightSheetContent extends StatefulWidget {
  const GoLivePreflightSheetContent({
    super.key,
    required this.initial,
    this.resolveCameraReady,
    this.resolveOrientationLabel,
  });

  final GoLivePreflightResult initial;
  final Future<bool> Function()? resolveCameraReady;
  final String Function()? resolveOrientationLabel;

  @override
  State<GoLivePreflightSheetContent> createState() => _GoLivePreflightSheetContentState();
}

class _GoLivePreflightSheetContentState extends State<GoLivePreflightSheetContent> {
  late bool _cameraReady;
  late bool _streamKeySet;
  late bool _networkOk;
  late bool _overlayLocked;
  late String _orientationLabel;
  late bool _orientationChanged;
  Timer? _refreshTimer;

  @override
  void initState() {
    super.initState();
    _cameraReady = widget.initial.cameraReady;
    _streamKeySet = widget.initial.streamKeySet;
    _networkOk = widget.initial.networkOk;
    _overlayLocked = widget.initial.overlayLocked;
    _orientationLabel = widget.initial.orientationLabel;
    _orientationChanged = widget.initial.orientationChanged;
    _refreshTimer = Timer.periodic(const Duration(seconds: 1), (_) => unawaited(_refreshChecks()));
  }

  @override
  void dispose() {
    _refreshTimer?.cancel();
    super.dispose();
  }

  Future<void> _refreshChecks() async {
    final networkOk = await _checkNetworkAvailable();
    final cameraReady = await _resolveCameraReady(
      cameraReady: false,
      probe: widget.resolveCameraReady,
    );
    final orient = widget.resolveOrientationLabel?.call() ?? _orientationLabel;
    if (!mounted) return;
    if (networkOk != _networkOk ||
        cameraReady != _cameraReady ||
        orient != _orientationLabel) {
      setState(() {
        _networkOk = networkOk;
        _cameraReady = cameraReady;
        _orientationLabel = orient;
      });
    }
  }

  bool get _canGoLive => _cameraReady && _streamKeySet && _networkOk;

  String get _goLiveButtonLabel {
    final mode = _orientationLabel.toUpperCase();
    return 'Go Live in $mode';
  }

  @override
  Widget build(BuildContext context) {
    final maxH = MediaQuery.sizeOf(context).height * 0.92;
    return Padding(
      padding: EdgeInsets.fromLTRB(
        AppSpacing.md,
        AppSpacing.md,
        AppSpacing.md,
        AppSpacing.md + MediaQuery.paddingOf(context).bottom,
      ),
      child: ConstrainedBox(
        constraints: BoxConstraints(maxHeight: maxH),
        child: SingleChildScrollView(
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
                label: 'Landscape recommended for cricket',
                ok: _orientationLabel == 'landscape',
                optional: true,
                hint: _orientationLabel == 'landscape'
                    ? 'Good for wide pitch coverage — orientation locks when you go live'
                    : 'Portrait works too — landscape is recommended for most club streams',
              ),
              _CheckRow(
                label: 'Stream orientation settled',
                ok: !_orientationChanged,
                optional: true,
                hint: _orientationChanged
                    ? 'You rotated since preview started — hold the phone how you want to stream'
                    : null,
              ),
              _CheckRow(
                label: 'Scoreboard position locked',
                ok: _overlayLocked,
                optional: true,
                hint: _overlayLocked
                    ? null
                    : 'Optional — drag scoreboard on preview, then lock to avoid accidental moves',
              ),
              if (_orientationChanged) ...[
                const SizedBox(height: AppSpacing.sm),
                CrInfoBanner(
                  title: 'Set orientation',
                  body:
                      'Hold the phone how viewers should see the stream. '
                      'It will lock when you tap Go Live.',
                  accentColor: AppColors.warning,
                ),
              ],
              const SizedBox(height: AppSpacing.lg),
              FilledButton(
                onPressed: _canGoLive ? () => Navigator.pop(context, true) : null,
                child: Text(_goLiveButtonLabel),
              ),
              const SizedBox(height: AppSpacing.sm),
              TextButton(
                onPressed: () => Navigator.pop(context, false),
                child: const Text('Cancel'),
              ),
            ],
          ),
        ),
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

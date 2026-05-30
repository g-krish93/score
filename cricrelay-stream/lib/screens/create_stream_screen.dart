import 'package:flutter/material.dart';

import '../services/api.dart';
import '../services/app_analytics.dart';
import '../theme/app_theme.dart';
import '../widgets/ui_kit.dart';
import 'broadcast_screen.dart';

class CreateStreamScreen extends StatefulWidget {
  const CreateStreamScreen({super.key, required this.api});

  final CricRelayApi api;

  @override
  State<CreateStreamScreen> createState() => _CreateStreamScreenState();
}

class _CreateStreamScreenState extends State<CreateStreamScreen> {
  int _tab = 0;
  bool _loading = true;
  String? _error;
  FixturesResponse? _fixtures;
  final _labelCtrl = TextEditingController();
  final _matchIdCtrl = TextEditingController();
  final _bleLabelCtrl = TextEditingController();
  final _pcBaseUrlCtrl = TextEditingController();
  bool _showAdvancedCreate = false;

  @override
  void initState() {
    super.initState();
    _load();
  }

  @override
  void dispose() {
    _labelCtrl.dispose();
    _matchIdCtrl.dispose();
    _bleLabelCtrl.dispose();
    _pcBaseUrlCtrl.dispose();
    super.dispose();
  }

  Future<void> _load() async {
    setState(() {
      _loading = true;
      _error = null;
    });
    try {
      _fixtures = await widget.api.listFixtures();
    } catch (e) {
      _error = e.toString().replaceFirst('Exception: ', '');
    } finally {
      if (mounted) setState(() => _loading = false);
    }
  }

  Future<void> _createAndOpen(StreamMatch match) async {
    await AppAnalytics.logEvent('stream_created');
    if (!mounted) return;
    Navigator.of(context).pushReplacement(
      MaterialPageRoute(builder: (_) => BroadcastScreen(api: widget.api, match: match)),
    );
  }

  Future<void> _createFromFixture(FixtureItem f) async {
    setState(() => _loading = true);
    try {
      final m = await widget.api.createPlayCricketStream(
        matchId: f.matchId,
        label: _labelCtrl.text.trim().isEmpty ? f.title : _labelCtrl.text.trim(),
      );
      await _createAndOpen(m);
    } catch (e) {
      if (mounted) setState(() => _error = e.toString().replaceFirst('Exception: ', ''));
    } finally {
      if (mounted) setState(() => _loading = false);
    }
  }

  Future<void> _createManualId() async {
    final mid = _matchIdCtrl.text.trim();
    if (mid.isEmpty) {
      setState(() => _error = 'Enter a Play-Cricket match ID');
      return;
    }
    setState(() => _loading = true);
    try {
      final m = await widget.api.createPlayCricketStreamWithOptions(
        matchId: mid,
        label: _labelCtrl.text.trim(),
        playCricketBaseUrl: _pcBaseUrlCtrl.text.trim(),
      );
      await _createAndOpen(m);
    } catch (e) {
      if (mounted) setState(() => _error = e.toString().replaceFirst('Exception: ', ''));
    } finally {
      if (mounted) setState(() => _loading = false);
    }
  }

  Future<void> _createBle() async {
    final label = _bleLabelCtrl.text.trim();
    if (label.isEmpty) {
      setState(() => _error = 'Enter a stream label');
      return;
    }
    setState(() => _loading = true);
    try {
      final m = await widget.api.createPcsBleStream(label: label);
      await _createAndOpen(m);
    } catch (e) {
      if (mounted) setState(() => _error = e.toString().replaceFirst('Exception: ', ''));
    } finally {
      if (mounted) setState(() => _loading = false);
    }
  }

  @override
  Widget build(BuildContext context) {
    final fx = _fixtures;
    return Scaffold(
      appBar: AppBar(title: const Text('New stream')),
      body: _loading && fx == null
          ? const Center(child: CircularProgressIndicator())
          : ListView(
              padding: const EdgeInsets.all(AppSpacing.md),
              children: [
                SegmentedButton<int>(
                  style: ButtonStyle(
                    visualDensity: VisualDensity.compact,
                  ),
                  segments: const [
                    ButtonSegment(value: 0, label: Text('Play-Cricket'), icon: Icon(Icons.sports_cricket)),
                    ButtonSegment(value: 1, label: Text('PCS BLE'), icon: Icon(Icons.bluetooth)),
                  ],
                  selected: {_tab},
                  onSelectionChanged: (s) => setState(() => _tab = s.first),
                ),
                if (_error != null) ...[
                  const SizedBox(height: AppSpacing.md),
                  CrErrorBanner(message: _error!),
                  if (_fixtures == null) ...[
                    const SizedBox(height: AppSpacing.sm),
                    Align(
                      alignment: Alignment.centerLeft,
                      child: OutlinedButton.icon(
                        onPressed: _loading ? null : _load,
                        icon: const Icon(Icons.refresh),
                        label: const Text('Retry'),
                      ),
                    ),
                  ],
                ],
                if (fx != null)
                  Padding(
                    padding: const EdgeInsets.only(top: AppSpacing.md),
                    child: Text(
                      'Stream slots ${fx.slotsUsed} / ${fx.slotsTotal}',
                      style: appTextTheme.bodySmall,
                    ),
                  ),
                const SizedBox(height: AppSpacing.lg),
                if (_tab == 0) ...[
                  TextField(
                    controller: _labelCtrl,
                    decoration: const InputDecoration(
                      labelText: 'Stream title (optional)',
                      hintText: '1st XI vs Rivals',
                    ),
                  ),
                  const SizedBox(height: AppSpacing.md),
                  TextField(
                    controller: _matchIdCtrl,
                    decoration: const InputDecoration(
                      labelText: 'Play-Cricket match ID',
                      hintText: '7560599',
                    ),
                    keyboardType: TextInputType.number,
                  ),
                  const SizedBox(height: AppSpacing.md),
                  ExpansionTile(
                    title: const Text('Advanced'),
                    initiallyExpanded: _showAdvancedCreate,
                    onExpansionChanged: (v) => setState(() => _showAdvancedCreate = v),
                    children: [
                      Padding(
                        padding: const EdgeInsets.only(bottom: AppSpacing.md),
                        child: TextField(
                          controller: _pcBaseUrlCtrl,
                          decoration: const InputDecoration(
                            labelText: 'Play-Cricket base URL (optional)',
                            hintText: 'https://play-cricket.com/website/results/...',
                          ),
                        ),
                      ),
                    ],
                  ),
                  FilledButton(
                    onPressed: _loading ? null : _createManualId,
                    child: const Text('Create from match ID'),
                  ),
                  const SizedBox(height: AppSpacing.lg),
                  const CrSectionLabel('Club fixtures'),
                  if (fx != null && fx.error != null)
                    Padding(
                      padding: const EdgeInsets.only(bottom: AppSpacing.sm),
                      child: Text(fx.error!, style: const TextStyle(color: AppColors.warning, fontSize: 13)),
                    ),
                  if (fx != null)
                    for (final f in fx.fixtures)
                      Padding(
                        padding: const EdgeInsets.only(bottom: AppSpacing.sm),
                        child: Opacity(
                          opacity: fx.activeMatchIds.contains(f.matchId) || _loading ? 0.45 : 1,
                          child: CrStreamTile(
                            title: f.title.isEmpty ? 'Match ${f.matchId}' : f.title,
                            subtitle: fx.activeMatchIds.contains(f.matchId)
                                ? 'Already linked'
                                : 'ID ${f.matchId}',
                            onTap: fx.activeMatchIds.contains(f.matchId) || _loading
                                ? () {}
                                : () => _createFromFixture(f),
                          ),
                        ),
                      ),
                ] else ...[
                  const CrInfoBanner(
                    title: 'PCS Bluetooth scoring',
                    body: 'Experimental BLE scoring. Choose BLE mode while live on the broadcast screen.',
                    icon: Icons.bluetooth,
                  ),
                  const SizedBox(height: AppSpacing.md),
                  TextField(
                    controller: _bleLabelCtrl,
                    decoration: const InputDecoration(
                      labelText: 'Stream title',
                      hintText: '1st XI vs Rivals',
                    ),
                  ),
                  const SizedBox(height: AppSpacing.md),
                  FilledButton(
                    onPressed: _loading ? null : _createBle,
                    child: const Text('Create BLE stream'),
                  ),
                ],
              ],
            ),
    );
  }
}

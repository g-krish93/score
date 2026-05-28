import 'package:flutter/material.dart';

import '../services/api.dart';
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
      _error = e.toString();
    } finally {
      if (mounted) setState(() => _loading = false);
    }
  }

  Future<void> _createAndOpen(StreamMatch match) async {
    if (!mounted) return;
    Navigator.of(context).pushReplacement(
      MaterialPageRoute(
        builder: (_) => BroadcastScreen(api: widget.api, match: match),
      ),
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
      setState(() => _error = e.toString());
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
      final m = await widget.api.createPlayCricketStream(
        matchId: mid,
        label: _labelCtrl.text.trim(),
      );
      await _createAndOpen(m);
    } catch (e) {
      setState(() => _error = e.toString());
    } finally {
      if (mounted) setState(() => _loading = false);
    }
  }

  Widget _fixtureCard(FixturesResponse fx, FixtureItem f) {
    final linked = fx.activeMatchIds.contains(f.matchId);
    return Card(
      child: ListTile(
        title: Text(f.title.isEmpty ? 'Match ${f.matchId}' : f.title),
        subtitle: Text(linked ? 'Already linked' : 'ID ${f.matchId}'),
        enabled: !linked && !_loading,
        trailing: linked ? const Icon(Icons.check) : const Icon(Icons.add),
        onTap: linked || _loading ? null : () => _createFromFixture(f),
      ),
    );
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
      setState(() => _error = e.toString());
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
              padding: const EdgeInsets.all(16),
              children: [
                SegmentedButton<int>(
                  segments: const [
                    ButtonSegment(value: 0, label: Text('Play-Cricket')),
                    ButtonSegment(value: 1, label: Text('PCS BLE')),
                  ],
                  selected: {_tab},
                  onSelectionChanged: (s) => setState(() => _tab = s.first),
                ),
                if (_error != null) ...[
                  const SizedBox(height: 12),
                  Text(_error!, style: const TextStyle(color: Colors.redAccent)),
                ],
                if (fx != null)
                  Padding(
                    padding: const EdgeInsets.only(top: 8),
                    child: Text(
                      'Streams ${fx.slotsUsed}/${fx.slotsTotal}',
                      style: const TextStyle(color: Colors.white70),
                    ),
                  ),
                const SizedBox(height: 16),
                if (_tab == 0) ...[
                  TextField(
                    controller: _labelCtrl,
                    decoration: const InputDecoration(
                      labelText: 'Stream label (optional)',
                      hintText: 'e.g. 1st XI vs Rivals',
                    ),
                  ),
                  const SizedBox(height: 12),
                  TextField(
                    controller: _matchIdCtrl,
                    decoration: const InputDecoration(
                      labelText: 'Or enter match ID manually',
                      hintText: '7560599',
                    ),
                    keyboardType: TextInputType.number,
                  ),
                  const SizedBox(height: 8),
                  FilledButton(
                    onPressed: _loading ? null : _createManualId,
                    child: const Text('Create from match ID'),
                  ),
                  const SizedBox(height: 20),
                  const Text('Fixtures from your club site',
                      style: TextStyle(fontWeight: FontWeight.bold)),
                  if (fx != null && fx.error != null)
                    Padding(
                      padding: const EdgeInsets.only(top: 8),
                      child: Text(fx.error!, style: const TextStyle(color: Colors.orangeAccent)),
                    ),
                  if (fx != null)
                    for (final f in fx.fixtures) _fixtureCard(fx, f),
                ] else ...[
                  const Text(
                    'PCS Bluetooth scoring (R&D). Use BLE scoring mode while live.',
                    style: TextStyle(color: Colors.white70),
                  ),
                  const SizedBox(height: 12),
                  TextField(
                    controller: _bleLabelCtrl,
                    decoration: const InputDecoration(
                      labelText: 'Stream label',
                      hintText: '1st XI vs Rivals',
                    ),
                  ),
                  const SizedBox(height: 12),
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

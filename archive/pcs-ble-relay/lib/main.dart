import 'dart:async';
import 'dart:convert';

import 'package:flutter/material.dart';
import 'package:flutter_reactive_ble/flutter_reactive_ble.dart';
import 'package:http/http.dart' as http;
import 'package:permission_handler/permission_handler.dart';
import 'package:shared_preferences/shared_preferences.dart';
import 'package:wakelock_plus/wakelock_plus.dart';

void main() {
  WidgetsFlutterBinding.ensureInitialized();
  runApp(const PcsRelayApp());
}

class PcsRelayApp extends StatelessWidget {
  const PcsRelayApp({super.key});

  @override
  Widget build(BuildContext context) {
    return MaterialApp(
      title: 'CricRelay PCS Relay',
      theme: ThemeData(
        colorScheme: ColorScheme.fromSeed(
          seedColor: const Color(0xFF22D3A8),
          brightness: Brightness.dark,
        ),
        useMaterial3: true,
      ),
      home: const RelayHomePage(),
    );
  }
}

class RelayHomePage extends StatefulWidget {
  const RelayHomePage({super.key});

  @override
  State<RelayHomePage> createState() => _RelayHomePageState();
}

class _RelayHomePageState extends State<RelayHomePage> {
  final FlutterReactiveBle _ble = FlutterReactiveBle();
  final List<DiscoveredDevice> _devices = [];
  final List<String> _log = [];
  final List<String> _recentPackets = [];

  StreamSubscription<DiscoveredDevice>? _scanSub;
  StreamSubscription<ConnectionStateUpdate>? _connSub;
  final List<StreamSubscription<List<int>>> _notifySubs = [];

  String _ingestUrl = '';
  String _bearerToken = '';
  String? _serviceUuidFilter;
  String? _connectedId;
  String? _connectedName;
  bool _scanning = false;
  bool _connected = false;
  int _packetCount = 0;
  int _postedCount = 0;
  int _postFailCount = 0;
  String? _lastPacket;
  DateTime? _lastPacketAt;
  String _status = 'Idle';

  @override
  void initState() {
    super.initState();
    _loadSettings();
  }

  @override
  void dispose() {
    _stopScan();
    _disconnect();
    WakelockPlus.disable();
    super.dispose();
  }

  Future<void> _loadSettings() async {
    final prefs = await SharedPreferences.getInstance();
    setState(() {
      _ingestUrl = prefs.getString('ingest_url') ?? '';
      _bearerToken = prefs.getString('bearer_token') ?? '';
      _serviceUuidFilter = prefs.getString('service_uuid');
    });
  }

  Future<void> _saveSettings() async {
    final prefs = await SharedPreferences.getInstance();
    await prefs.setString('ingest_url', _ingestUrl.trim());
    await prefs.setString('bearer_token', _bearerToken.trim());
    if (_serviceUuidFilter != null && _serviceUuidFilter!.trim().isNotEmpty) {
      await prefs.setString('service_uuid', _serviceUuidFilter!.trim());
    } else {
      await prefs.remove('service_uuid');
    }
    if (mounted) {
      ScaffoldMessenger.of(context).showSnackBar(
        const SnackBar(content: Text('Settings saved')),
      );
    }
  }

  void _addLog(String msg) {
    final line = '${DateTime.now().toIso8601String().substring(11, 19)} $msg';
    setState(() {
      _log.insert(0, line);
      if (_log.length > 80) _log.removeLast();
    });
  }

  Future<bool> _ensurePermissions() async {
    final perms = <Permission>[
      Permission.bluetoothScan,
      Permission.bluetoothConnect,
      Permission.locationWhenInUse,
    ];
    final statuses = await perms.request();
    final denied = statuses.entries.where((e) => !e.value.isGranted).toList();
    if (denied.isNotEmpty) {
      _addLog('Missing permissions: ${denied.map((e) => e.key).join(", ")}');
      setState(() => _status = 'Permissions required');
      return false;
    }
    return true;
  }

  Future<void> _startScan() async {
    if (!await _ensurePermissions()) return;
    await _stopScan();
    setState(() {
      _devices.clear();
      _scanning = true;
      _status = 'Scanning…';
    });
    _scanSub = _ble.scanForDevices(withServices: []).listen(
      (device) {
        final exists = _devices.any((d) => d.id == device.id);
        if (!exists) {
          setState(() => _devices.add(device));
        }
      },
      onError: (e) {
        _addLog('Scan error: $e');
        setState(() => _scanning = false);
      },
    );
  }

  Future<void> _stopScan() async {
    await _scanSub?.cancel();
    _scanSub = null;
    if (_scanning) {
      setState(() => _scanning = false);
    }
  }

  Future<void> _connect(DiscoveredDevice device) async {
    await _stopScan();
    await _disconnect();
    setState(() {
      _status = 'Connecting…';
      _connectedId = device.id;
      _connectedName = device.name.isNotEmpty ? device.name : device.id;
    });
    _addLog('Connecting to ${_connectedName}');

    _connSub = _ble.connectToDevice(
      id: device.id,
      connectionTimeout: const Duration(seconds: 20),
    ).listen(
      (update) async {
        if (update.connectionState == DeviceConnectionState.connected) {
          setState(() {
            _connected = true;
            _status = 'Connected';
          });
          await WakelockPlus.enable();
          _addLog('Connected — discovering services');
          await _discoverAndSubscribe(device.id);
        } else if (update.connectionState == DeviceConnectionState.disconnected) {
          setState(() {
            _connected = false;
            _status = 'Disconnected';
          });
          _addLog('Disconnected');
          await WakelockPlus.disable();
        }
      },
      onError: (e) {
        _addLog('Connection error: $e');
        setState(() => _status = 'Connection failed');
      },
    );
  }

  Future<void> _discoverAndSubscribe(String deviceId) async {
    try {
      final services = await _ble.discoverServices(deviceId);
      final filter = _serviceUuidFilter?.trim().toLowerCase();
      var subs = 0;
      for (final service in services) {
        final svcId = service.serviceId.toString().toLowerCase();
        if (filter != null && filter.isNotEmpty && !svcId.contains(filter)) {
          continue;
        }
        for (final char in service.characteristics) {
          if (char.isNotifiable || char.isIndicatable) {
            final qualified = QualifiedCharacteristic(
              serviceId: service.serviceId,
              characteristicId: char.characteristicId,
              deviceId: deviceId,
            );
            final sub = _ble.subscribeToCharacteristic(qualified).listen(
              (data) => _onPacket(data),
              onError: (e) => _addLog('Notify error: $e'),
            );
            _notifySubs.add(sub);
            subs++;
            _addLog('Subscribed ${char.characteristicId}');
          }
        }
      }
      if (subs == 0) {
        _addLog('No notify characteristics — try another device or clear service filter');
      } else {
        _addLog('Listening on $subs characteristic(s)');
        setState(() => _status = 'Relaying ($subs ch)');
      }
    } catch (e) {
      _addLog('Discover failed: $e');
    }
  }

  void _onPacket(List<int> data) {
    if (data.isEmpty) return;
    final line = _decodePacket(data);
    if (line.isEmpty) return;
    setState(() {
      _packetCount++;
      _lastPacket = line;
      _lastPacketAt = DateTime.now();
      _recentPackets.insert(0, line);
      if (_recentPackets.length > 12) _recentPackets.removeLast();
    });
    unawaited(_postToServer(line));
  }

  String _decodePacket(List<int> data) {
    try {
      final s = utf8.decode(data, allowMalformed: true).trim();
      if (s.isNotEmpty && _looksLikePcs(s)) return s;
    } catch (_) {}
    // Some boards send ASCII without strict UTF-8
    final ascii = String.fromCharCodes(data.where((b) => b >= 32 && b < 127));
    if (ascii.length >= 3) return ascii.trim();
    return '';
  }

  bool _looksLikePcs(String s) {
    if (s.length < 3) return false;
    final op = s.substring(0, 3).toUpperCase();
    return RegExp(r'^[A-Z]{3}').hasMatch(op);
  }

  Future<void> _postToServer(String line) async {
    final url = _ingestUrl.trim();
    if (url.isEmpty) return;
    try {
      final headers = <String, String>{
        'Content-Type': 'application/json',
      };
      final tok = _bearerToken.trim();
      if (tok.isNotEmpty) {
        headers['Authorization'] = tok.startsWith('Bearer ') ? tok : 'Bearer $tok';
      }
      final resp = await http
          .post(
            Uri.parse(url),
            headers: headers,
            body: jsonEncode({'line': line}),
          )
          .timeout(const Duration(seconds: 12));
      if (resp.statusCode >= 200 && resp.statusCode < 300) {
        if (mounted) setState(() => _postedCount++);
      } else {
        if (mounted) setState(() => _postFailCount++);
        _addLog('POST ${resp.statusCode}: ${resp.body.length > 80 ? resp.body.substring(0, 80) : resp.body}');
      }
    } catch (e) {
      if (mounted) setState(() => _postFailCount++);
      _addLog('POST failed: $e');
    }
  }

  Future<void> _disconnect() async {
    for (final s in _notifySubs) {
      await s.cancel();
    }
    _notifySubs.clear();
    await _connSub?.cancel();
    _connSub = null;
    if (_connectedId != null) {
      try {
        await _ble.clearGattCache(_connectedId!);
      } catch (_) {}
    }
    setState(() {
      _connected = false;
      _connectedId = null;
      _connectedName = null;
    });
    await WakelockPlus.disable();
  }

  Future<void> _openSettings() async {
    final ingestCtrl = TextEditingController(text: _ingestUrl);
    final tokenCtrl = TextEditingController(text: _bearerToken);
    final svcCtrl = TextEditingController(text: _serviceUuidFilter ?? '');
    await showModalBottomSheet<void>(
      context: context,
      isScrollControlled: true,
      builder: (ctx) {
        return Padding(
          padding: EdgeInsets.only(
            left: 16,
            right: 16,
            top: 16,
            bottom: MediaQuery.of(ctx).viewInsets.bottom + 24,
          ),
          child: Column(
            mainAxisSize: MainAxisSize.min,
            crossAxisAlignment: CrossAxisAlignment.stretch,
            children: [
              const Text('CricRelay settings', style: TextStyle(fontSize: 18, fontWeight: FontWeight.bold)),
              const SizedBox(height: 12),
              TextField(
                controller: ingestCtrl,
                decoration: const InputDecoration(
                  labelText: 'Ingest URL',
                  hintText: 'https://cricrelay.co.uk/relay/pcs-ingest?match=slug',
                ),
                keyboardType: TextInputType.url,
              ),
              const SizedBox(height: 8),
              TextField(
                controller: tokenCtrl,
                decoration: const InputDecoration(
                  labelText: 'Bearer token',
                  hintText: 'From CricRelay dashboard',
                ),
                obscureText: true,
              ),
              const SizedBox(height: 8),
              TextField(
                controller: svcCtrl,
                decoration: const InputDecoration(
                  labelText: 'Service UUID filter (optional)',
                  hintText: 'Leave empty to subscribe to all notify chars',
                ),
              ),
              const SizedBox(height: 16),
              FilledButton(
                onPressed: () {
                  setState(() {
                    _ingestUrl = ingestCtrl.text;
                    _bearerToken = tokenCtrl.text;
                    _serviceUuidFilter = svcCtrl.text.trim().isEmpty ? null : svcCtrl.text.trim();
                  });
                  _saveSettings();
                  Navigator.pop(ctx);
                },
                child: const Text('Save'),
              ),
            ],
          ),
        );
      },
    );
    ingestCtrl.dispose();
    tokenCtrl.dispose();
    svcCtrl.dispose();
  }

  @override
  Widget build(BuildContext context) {
    return Scaffold(
      appBar: AppBar(
        title: const Text('CricRelay PCS Relay'),
        actions: [
          IconButton(icon: const Icon(Icons.settings), onPressed: _openSettings),
        ],
      ),
      body: ListView(
        padding: const EdgeInsets.all(16),
        children: [
          _statusCard(),
          const SizedBox(height: 12),
          _statsCard(),
          if (_recentPackets.isNotEmpty) ...[
            const SizedBox(height: 12),
            _packetsCard(),
          ],
          const SizedBox(height: 12),
          _scanCard(),
          const SizedBox(height: 12),
          _logCard(),
        ],
      ),
    );
  }

  Widget _statusCard() {
    final ingestOk = _ingestUrl.trim().isNotEmpty;
    return Card(
      child: Padding(
        padding: const EdgeInsets.all(14),
        child: Column(
          crossAxisAlignment: CrossAxisAlignment.start,
          children: [
            Text('Status: $_status', style: const TextStyle(fontWeight: FontWeight.w600)),
            const SizedBox(height: 6),
            Text(
              ingestOk ? 'Ingest configured' : 'Set ingest URL in settings (from CricRelay dashboard)',
              style: TextStyle(color: ingestOk ? Colors.greenAccent : Colors.orangeAccent, fontSize: 13),
            ),
            if (_connectedName != null)
              Text('Device: $_connectedName', style: const TextStyle(fontSize: 13)),
          ],
        ),
      ),
    );
  }

  Widget _statsCard() {
    final last = _lastPacketAt != null
        ? '${_lastPacketAt!.hour.toString().padLeft(2, "0")}:${_lastPacketAt!.minute.toString().padLeft(2, "0")}:${_lastPacketAt!.second.toString().padLeft(2, "0")}'
        : '—';
    return Card(
      child: Padding(
        padding: const EdgeInsets.all(14),
        child: Wrap(
          spacing: 16,
          runSpacing: 8,
          children: [
            _stat('BLE packets', '$_packetCount'),
            _stat('Posted OK', '$_postedCount'),
            _stat('POST fails', '$_postFailCount'),
            _stat('Last packet', last),
          ],
        ),
      ),
    );
  }

  Widget _stat(String label, String value) {
    return Column(
      crossAxisAlignment: CrossAxisAlignment.start,
      children: [
        Text(label, style: const TextStyle(fontSize: 11, color: Colors.white70)),
        Text(value, style: const TextStyle(fontSize: 16, fontWeight: FontWeight.bold)),
      ],
    );
  }

  Widget _packetsCard() {
    return Card(
      child: Padding(
        padding: const EdgeInsets.all(14),
        child: Column(
          crossAxisAlignment: CrossAxisAlignment.start,
          children: [
            const Text('Recent packets', style: TextStyle(fontWeight: FontWeight.w600)),
            const SizedBox(height: 8),
            ..._recentPackets.map((p) => Text(p, style: const TextStyle(fontFamily: 'monospace', fontSize: 12))),
          ],
        ),
      ),
    );
  }

  Widget _scanCard() {
    return Card(
      child: Padding(
        padding: const EdgeInsets.all(14),
        child: Column(
          crossAxisAlignment: CrossAxisAlignment.stretch,
          children: [
            Row(
              children: [
                Expanded(
                  child: FilledButton.icon(
                    onPressed: _scanning ? null : _startScan,
                    icon: const Icon(Icons.bluetooth_searching),
                    label: Text(_scanning ? 'Scanning…' : 'Scan BLE'),
                  ),
                ),
                const SizedBox(width: 8),
                if (_scanning)
                  IconButton(onPressed: _stopScan, icon: const Icon(Icons.stop)),
                if (_connected)
                  IconButton(
                    onPressed: _disconnect,
                    icon: const Icon(Icons.link_off),
                    color: Colors.redAccent,
                  ),
              ],
            ),
            const SizedBox(height: 10),
            if (_devices.isEmpty)
              const Text('No devices yet — enable Bluetooth on iPad (PCS) and tap Scan.', style: TextStyle(fontSize: 13))
            else
              ..._devices.map((d) {
                final name = d.name.isNotEmpty ? d.name : '(unnamed)';
                return ListTile(
                  dense: true,
                  title: Text(name),
                  subtitle: Text(d.id, style: const TextStyle(fontSize: 11)),
                  trailing: _connectedId == d.id
                      ? const Icon(Icons.check_circle, color: Colors.greenAccent)
                      : const Icon(Icons.chevron_right),
                  onTap: _connected ? null : () => _connect(d),
                );
              }),
          ],
        ),
      ),
    );
  }

  Widget _logCard() {
    return Card(
      child: Padding(
        padding: const EdgeInsets.all(14),
        child: Column(
          crossAxisAlignment: CrossAxisAlignment.start,
          children: [
            const Text('Connection log', style: TextStyle(fontWeight: FontWeight.w600)),
            const SizedBox(height: 8),
            ..._log.take(15).map((l) => Text(l, style: const TextStyle(fontSize: 11, fontFamily: 'monospace'))),
          ],
        ),
      ),
    );
  }
}

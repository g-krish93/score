import 'package:flutter/material.dart';

import '../services/api.dart';
import 'broadcast_screen.dart';
import 'login_screen.dart';

class StreamsScreen extends StatefulWidget {
  const StreamsScreen({super.key, required this.api});

  final CricRelayApi api;

  @override
  State<StreamsScreen> createState() => _StreamsScreenState();
}

class _StreamsScreenState extends State<StreamsScreen> {
  List<StreamMatch> _streams = [];
  bool _loading = true;
  String? _error;
  bool _youtubeOk = false;
  String _channelTitle = '';

  @override
  void initState() {
    super.initState();
    _refresh();
  }

  Future<void> _refresh() async {
    setState(() {
      _loading = true;
      _error = null;
    });
    try {
      final yt = await widget.api.youtubeStatus();
      final list = await widget.api.listStreams();
      for (var i = 0; i < list.length; i++) {
        final m = list[i];
        if (m.overlayEmbedUrl.startsWith('/')) {
          list[i] = StreamMatch(
            slug: m.slug,
            label: m.label,
            overlayEmbedUrl: '${widget.api.baseUrl}${m.overlayEmbedUrl}',
            relaySource: m.relaySource,
            paused: m.paused,
          );
        }
      }
      setState(() {
        _streams = list;
        _youtubeOk = yt['connected'] == true;
        _channelTitle = (yt['channel_title'] ?? '').toString();
      });
    } catch (e) {
      setState(() => _error = e.toString());
    } finally {
      if (mounted) setState(() => _loading = false);
    }
  }

  Future<void> _logout() async {
    await widget.api.clearSession();
    if (!mounted) return;
    Navigator.of(context).pushReplacement(
      MaterialPageRoute(
        builder: (_) => LoginScreen(api: CricRelayApi(widget.api.baseUrl)),
      ),
    );
  }

  @override
  Widget build(BuildContext context) {
    return Scaffold(
      appBar: AppBar(
        title: const Text('Choose stream'),
        actions: [
          IconButton(onPressed: _refresh, icon: const Icon(Icons.refresh)),
          IconButton(onPressed: _logout, icon: const Icon(Icons.logout)),
        ],
      ),
      body: _loading
          ? const Center(child: CircularProgressIndicator())
          : RefreshIndicator(
              onRefresh: _refresh,
              child: ListView(
                padding: const EdgeInsets.all(16),
                children: [
                  Card(
                    child: Padding(
                      padding: const EdgeInsets.all(14),
                      child: Column(
                        crossAxisAlignment: CrossAxisAlignment.start,
                        children: [
                          const Text('YouTube',
                              style: TextStyle(fontWeight: FontWeight.bold)),
                          const SizedBox(height: 6),
                          Text(
                            _youtubeOk
                                ? 'Connected: $_channelTitle'
                                : 'Not connected — open dashboard → Connect YouTube',
                            style: TextStyle(
                              color: _youtubeOk ? Colors.greenAccent : Colors.orangeAccent,
                            ),
                          ),
                        ],
                      ),
                    ),
                  ),
                  if (_error != null)
                    Padding(
                      padding: const EdgeInsets.only(bottom: 12),
                      child: Text(_error!, style: const TextStyle(color: Colors.redAccent)),
                    ),
                  if (_streams.isEmpty)
                    const Padding(
                      padding: EdgeInsets.all(24),
                      child: Text('No streams. Create one in the CricRelay dashboard.'),
                    ),
                  ..._streams.map((m) {
                    return Card(
                      child: ListTile(
                        title: Text(m.label),
                        subtitle: Text('${m.slug} · ${m.relaySource}'),
                        trailing: const Icon(Icons.videocam),
                        onTap: _youtubeOk
                            ? () {
                                Navigator.of(context).push(
                                  MaterialPageRoute(
                                    builder: (_) => BroadcastScreen(
                                      api: widget.api,
                                      match: m,
                                    ),
                                  ),
                                );
                              }
                            : null,
                      ),
                    );
                  }),
                ],
              ),
            ),
    );
  }
}

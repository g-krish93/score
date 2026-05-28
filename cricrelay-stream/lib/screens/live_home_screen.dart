import 'package:flutter/material.dart';

import '../services/api.dart';
import 'broadcast_screen.dart';
import 'create_stream_screen.dart';
import 'login_screen.dart';
import 'youtube_connect_screen.dart';

/// Club home: streams list, YouTube connect, one-tap go live (Prism-style entry).
class LiveHomeScreen extends StatefulWidget {
  const LiveHomeScreen({super.key, required this.api});

  final CricRelayApi api;

  @override
  State<LiveHomeScreen> createState() => _LiveHomeScreenState();
}

class _LiveHomeScreenState extends State<LiveHomeScreen> {
  List<StreamMatch> _streams = [];
  bool _loading = true;
  String? _error;
  bool _youtubeOk = false;
  bool _youtubeOauthConfigured = true;
  bool _youtubeLiveOk = false;
  String _channelTitle = '';
  String _youtubeLiveMessage = '';

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
          list[i] = StreamMatch.fromJson({
            'slug': m.slug,
            'label': m.label,
            'overlay_embed_url': '${widget.api.baseUrl}${m.overlayEmbedUrl}',
            'relay_source': m.relaySource,
            'paused': m.paused,
          }, widget.api.baseUrl);
        }
      }
      setState(() {
        _streams = list;
        _youtubeOauthConfigured = yt['oauth_configured'] != false;
        _youtubeOk = yt['connected'] == true;
        _youtubeLiveOk = yt['live_streaming_ok'] == true;
        _youtubeLiveMessage = (yt['live_streaming_message'] ?? '').toString();
        _channelTitle = (yt['channel_title'] ?? '').toString();
      });
    } catch (e) {
      setState(() => _error = e.toString());
    } finally {
      if (mounted) setState(() => _loading = false);
    }
  }

  Future<void> _reconnectYoutube() async {
    try {
      await widget.api.youtubeDisconnect();
    } catch (_) {}
    if (!mounted) return;
    await _connectYoutube();
  }

  Future<void> _connectYoutube() async {
    if (!_youtubeOauthConfigured) {
      if (!mounted) return;
      ScaffoldMessenger.of(context).showSnackBar(
        const SnackBar(
          content: Text(
            'YouTube sign-in is not set up on the server yet. '
            'Use Custom RTMP in the broadcast screen (antenna icon), or ask your admin to add '
            'YOUTUBE_CLIENT_ID and YOUTUBE_CLIENT_SECRET on cricrelay.co.uk.',
          ),
          duration: Duration(seconds: 8),
        ),
      );
      return;
    }
    try {
      final url = await widget.api.youtubeAuthorizeUrl();
      if (!mounted) return;
      final ok = await Navigator.of(context).push<bool>(
        MaterialPageRoute(builder: (_) => YoutubeConnectScreen(authorizeUrl: url)),
      );
      if (ok == true) await _refresh();
    } catch (e) {
      if (!mounted) return;
      ScaffoldMessenger.of(context).showSnackBar(
        SnackBar(content: Text(e.toString())),
      );
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

  void _openStream(StreamMatch m) {
    Navigator.of(context).push(
      MaterialPageRoute(
        builder: (_) => BroadcastScreen(api: widget.api, match: m),
      ),
    );
  }

  @override
  Widget build(BuildContext context) {
    return Scaffold(
      appBar: AppBar(
        title: const Text('CricRelay Live'),
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
                        crossAxisAlignment: CrossAxisAlignment.stretch,
                        children: [
                          const Text('YouTube destination',
                              style: TextStyle(fontWeight: FontWeight.bold)),
                          const SizedBox(height: 6),
                          Text(
                            _youtubeOk && _youtubeLiveOk
                                ? 'Connected: $_channelTitle (live streaming OK)'
                                : _youtubeOk
                                    ? 'Connected: $_channelTitle — live access missing'
                                    : (_youtubeOauthConfigured
                                        ? 'Connect your club channel to stream'
                                        : 'YouTube OAuth is not configured on server'),
                            style: TextStyle(
                              color: (_youtubeOk && _youtubeLiveOk)
                                  ? Colors.greenAccent
                                  : Colors.orangeAccent,
                            ),
                          ),
                          if (_youtubeOk && !_youtubeLiveOk && _youtubeLiveMessage.isNotEmpty)
                            Padding(
                              padding: const EdgeInsets.only(top: 8),
                              child: Text(
                                _youtubeLiveMessage,
                                style: const TextStyle(fontSize: 12, color: Colors.orangeAccent),
                              ),
                            ),
                          const SizedBox(height: 10),
                          if (!_youtubeOk)
                            FilledButton.icon(
                              onPressed: _connectYoutube,
                              icon: const Icon(Icons.link),
                              label: const Text('Connect YouTube'),
                            ),
                          if (_youtubeOk && !_youtubeLiveOk)
                            OutlinedButton.icon(
                              onPressed: _reconnectYoutube,
                              icon: const Icon(Icons.refresh),
                              label: const Text('Reconnect for live access'),
                            ),
                        ],
                      ),
                    ),
                  ),
                  const Padding(
                    padding: EdgeInsets.only(top: 8, bottom: 8),
                    child: Text(
                      'Stream + score in one app',
                      style: TextStyle(fontSize: 16, fontWeight: FontWeight.w600),
                    ),
                  ),
                  const Text(
                    'Create a stream here or tap one below → Go Live streams straight to YouTube. '
                    'Scoring: Auto, Manual, or BLE from the broadcast screen.',
                    style: TextStyle(color: Colors.white70),
                  ),
                  if (_error != null)
                    Padding(
                      padding: const EdgeInsets.only(top: 12),
                      child: Text(_error!, style: const TextStyle(color: Colors.redAccent)),
                    ),
                  if (_streams.isEmpty)
                    Padding(
                      padding: const EdgeInsets.all(16),
                      child: FilledButton.icon(
                        onPressed: (_youtubeOk && _youtubeLiveOk)
                            ? () {
                                Navigator.of(context).push(
                                  MaterialPageRoute(
                                    builder: (_) => CreateStreamScreen(api: widget.api),
                                  ),
                                );
                              }
                            : null,
                        icon: const Icon(Icons.add),
                        label: const Text('Create your first stream'),
                      ),
                    ),
                  ..._streams.map((m) {
                    return Card(
                      child: ListTile(
                        leading: CircleAvatar(
                          backgroundColor: const Color(0xFF22D3A8).withOpacity(0.2),
                          child: const Icon(Icons.videocam, color: Color(0xFF22D3A8)),
                        ),
                        title: Text(m.label),
                        subtitle: Text(m.slug),
                        trailing: const Icon(Icons.chevron_right),
                        onTap: () => _openStream(m),
                      ),
                    );
                  }),
                ],
              ),
            ),
      floatingActionButton: (_youtubeOk && _youtubeLiveOk)
          ? FloatingActionButton.extended(
              onPressed: () {
                Navigator.of(context).push(
                  MaterialPageRoute(
                    builder: (_) => CreateStreamScreen(api: widget.api),
                  ),
                );
              },
              icon: const Icon(Icons.add),
              label: const Text('New stream'),
            )
          : null,
    );
  }
}

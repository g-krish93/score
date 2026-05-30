import 'package:flutter/material.dart';
import 'package:package_info_plus/package_info_plus.dart';
import 'package:url_launcher/url_launcher.dart';

import '../services/api.dart';
import '../theme/app_theme.dart';
import '../widgets/app_download_card.dart';
import '../widgets/studio/studio_hero.dart';
import '../widgets/studio/studio_shell.dart';
import '../widgets/ui_kit.dart';
import 'broadcast_screen.dart';
import 'create_stream_screen.dart';
import 'login_screen.dart';

class LiveHomeScreen extends StatefulWidget {
  const LiveHomeScreen({super.key, required this.api});

  final CricRelayApi api;

  @override
  State<LiveHomeScreen> createState() => _LiveHomeScreenState();
}

class _LiveHomeScreenState extends State<LiveHomeScreen> with WidgetsBindingObserver {
  List<StreamMatch> _streams = [];
  bool _loading = true;
  String? _error;
  bool _youtubeOk = false;
  bool _youtubeOauthConfigured = true;
  bool _youtubeLiveOk = false;
  String _channelTitle = '';
  String _youtubeLiveMessage = '';
  bool _twitchOk = false;
  bool _twitchOauthConfigured = true;
  bool _twitchKeyOk = false;
  String _twitchDisplayName = '';
  String _twitchKeyMessage = '';
  bool _showAdvanced = false;

  @override
  void initState() {
    super.initState();
    WidgetsBinding.instance.addObserver(this);
    _refresh();
  }

  @override
  void dispose() {
    WidgetsBinding.instance.removeObserver(this);
    super.dispose();
  }

  @override
  void didChangeAppLifecycleState(AppLifecycleState state) {
    if (state == AppLifecycleState.resumed) _refresh();
  }

  Future<void> _refresh() async {
    setState(() {
      _loading = true;
      _error = null;
    });
    try {
      final yt = await widget.api.youtubeStatus();
      final tw = await widget.api.twitchStatus();
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
            'is_live': m.isLive,
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
        _twitchOauthConfigured = tw['oauth_configured'] != false;
        _twitchOk = tw['connected'] == true;
        _twitchKeyOk = tw['stream_key_ok'] == true;
        _twitchKeyMessage = (tw['stream_key_message'] ?? '').toString();
        _twitchDisplayName = (tw['display_name'] ?? '').toString();
      });
    } catch (e) {
      setState(() => _error = e.toString().replaceFirst('Exception: ', ''));
    } finally {
      if (mounted) setState(() => _loading = false);
    }
  }

  Future<void> _connectTwitch() async {
    if (!_twitchOauthConfigured) {
      _snack(
        'Twitch sign-in is not configured on the server. Use a stream key in the broadcast screen.',
      );
      return;
    }
    try {
      final url = await widget.api.twitchAuthorizeUrl();
      if (url.isEmpty) throw Exception('No authorize URL from server');
      final proceed = await _oauthDialog(
        title: 'Connect Twitch',
        body: 'Sign in with the club Twitch account in your browser, then return to this app.',
      );
      if (proceed != true || !mounted) return;
      if (!await launchUrl(Uri.parse(url), mode: LaunchMode.externalApplication)) {
        throw Exception('Could not open browser');
      }
      _snack('Complete sign-in in the browser, then pull to refresh.');
    } catch (e) {
      _snack(e.toString());
    }
  }

  Future<void> _connectYoutube() async {
    if (!_youtubeOauthConfigured) {
      _snack(
        'YouTube OAuth is not on the server. Volunteers can paste a Studio stream key instead.',
      );
      return;
    }
    try {
      final url = await widget.api.youtubeAuthorizeUrl();
      if (url.isEmpty) throw Exception('No authorize URL from server');
      final proceed = await _oauthDialog(
        title: 'Connect YouTube',
        body:
            'Google sign-in opens in your browser. Allow YouTube access, then return here — the app refreshes automatically.',
      );
      if (proceed != true || !mounted) return;
      if (!await launchUrl(Uri.parse(url), mode: LaunchMode.externalApplication)) {
        throw Exception('Could not open browser');
      }
      _snack('Complete sign-in in the browser, then pull to refresh.');
    } catch (e) {
      _snack(e.toString());
    }
  }

  Future<bool?> _oauthDialog({required String title, required String body}) {
    return showDialog<bool>(
      context: context,
      builder: (ctx) => AlertDialog(
        backgroundColor: AppColors.surface,
        title: Text(title),
        content: Text(body, style: appTextTheme.bodyMedium),
        actions: [
          TextButton(onPressed: () => Navigator.pop(ctx, false), child: const Text('Cancel')),
          FilledButton(onPressed: () => Navigator.pop(ctx, true), child: const Text('Open browser')),
        ],
      ),
    );
  }

  void _snack(String msg) {
    if (!mounted) return;
    ScaffoldMessenger.of(context).showSnackBar(SnackBar(content: Text(msg)));
  }

  Future<void> _logout() async {
    await widget.api.clearSession();
    if (!mounted) return;
    Navigator.of(context).pushReplacement(
      MaterialPageRoute(builder: (_) => LoginScreen(api: CricRelayApi(widget.api.baseUrl))),
    );
  }

  Future<void> _openLegal(String path) async {
    final uri = Uri.parse('${widget.api.baseUrl}$path');
    if (!await launchUrl(uri, mode: LaunchMode.externalApplication)) {
      _snack('Could not open $uri');
    }
  }

  Future<void> _showAbout() async {
    final info = await PackageInfo.fromPlatform();
    if (!mounted) return;
    await showDialog<void>(
      context: context,
      builder: (ctx) => AlertDialog(
        backgroundColor: AppColors.surface,
        title: const Text('About CricRelay Live'),
        content: Column(
          mainAxisSize: MainAxisSize.min,
          crossAxisAlignment: CrossAxisAlignment.start,
          children: [
            Text('Version ${info.version} (${info.buildNumber})', style: appTextTheme.bodyMedium),
            const SizedBox(height: AppSpacing.md),
            TextButton(
              onPressed: () {
                Navigator.pop(ctx);
                _openLegal('/privacy');
              },
              child: const Text('Privacy Policy'),
            ),
            TextButton(
              onPressed: () {
                Navigator.pop(ctx);
                _openLegal('/terms');
              },
              child: const Text('Terms of Service'),
            ),
          ],
        ),
        actions: [
          TextButton(onPressed: () => Navigator.pop(ctx), child: const Text('Close')),
        ],
      ),
    );
  }

  void _openStream(StreamMatch m) {
    Navigator.of(context).push(
      MaterialPageRoute(builder: (_) => BroadcastScreen(api: widget.api, match: m)),
    );
  }

  void _newStream() {
    Navigator.of(context).push(
      MaterialPageRoute(builder: (_) => CreateStreamScreen(api: widget.api)),
    );
  }

  @override
  Widget build(BuildContext context) {
    return Scaffold(
      body: CrStudioBackdrop(
        child: SafeArea(
          child: _loading && _streams.isEmpty
              ? const Center(child: CircularProgressIndicator())
              : RefreshIndicator(
                  onRefresh: _refresh,
                  color: AppColors.accent,
                  child: CustomScrollView(
                    slivers: [
                      SliverAppBar(
                        floating: true,
                        snap: true,
                        title: const Text('CricRelay Live'),
                        actions: [
                          IconButton(
                            onPressed: _refresh,
                            icon: const Icon(Icons.refresh_rounded),
                            tooltip: 'Refresh',
                          ),
                          PopupMenuButton<String>(
                            icon: const Icon(Icons.more_vert_rounded),
                            onSelected: (v) {
                              if (v == 'logout') _logout();
                              if (v == 'advanced') setState(() => _showAdvanced = !_showAdvanced);
                              if (v == 'about') _showAbout();
                            },
                            itemBuilder: (_) => [
                              CheckedPopupMenuItem(
                                value: 'advanced',
                                checked: _showAdvanced,
                                child: const Text('Show club OAuth options'),
                              ),
                              const PopupMenuItem(value: 'about', child: Text('About')),
                              const PopupMenuDivider(),
                              const PopupMenuItem(value: 'logout', child: Text('Sign out')),
                            ],
                          ),
                        ],
                      ),
                      SliverPadding(
                        padding: const EdgeInsets.fromLTRB(AppSpacing.md, 0, AppSpacing.md, 100),
                        sliver: SliverList(
                          delegate: SliverChildListDelegate([
                            const SizedBox(height: AppSpacing.sm),
                            const CrStudioHero(),
                            const SizedBox(height: AppSpacing.md),
                            AppDownloadCard(api: widget.api),
                            const SizedBox(height: AppSpacing.md),
                            const CrInfoBanner(
                              title: 'Volunteer streaming',
                              body:
                                  'Paste the YouTube Studio or Twitch stream key on the broadcast screen (Destination). '
                                  'No Google login needed on match day.',
                              icon: Icons.phonelink_ring_outlined,
                              accentColor: AppColors.accentGreen,
                            ),
                            const SizedBox(height: AppSpacing.lg),
                            const CrSectionLabel('Your streams'),
                            if (_error != null) ...[
                              CrErrorBanner(message: _error!),
                              const SizedBox(height: AppSpacing.md),
                            ],
                            if (_streams.isEmpty && !_loading)
                              Padding(
                                padding: const EdgeInsets.symmetric(vertical: AppSpacing.lg),
                                child: Column(
                                  children: [
                                    Icon(Icons.videocam_off_outlined, size: 52, color: AppColors.onBackgroundDim),
                                    const SizedBox(height: AppSpacing.md),
                                    Text('No streams yet', style: appTextTheme.headlineSmall),
                                    const SizedBox(height: AppSpacing.sm),
                                    Text(
                                      'Create a stream linked to Play-Cricket or PCS BLE scoring.',
                                      style: appTextTheme.bodyMedium,
                                      textAlign: TextAlign.center,
                                    ),
                                    const SizedBox(height: AppSpacing.lg),
                                    FilledButton.icon(
                                      onPressed: _newStream,
                                      icon: const Icon(Icons.add_rounded),
                                      label: const Text('Create stream'),
                                    ),
                                  ],
                                ),
                              ),
                            for (final m in _streams) ...[
                              CrStreamTile(
                                title: m.label,
                                subtitle: m.isLive ? 'Live now · ${m.slug}' : m.slug,
                                isLive: m.isLive,
                                onTap: () => _openStream(m),
                              ),
                              const SizedBox(height: AppSpacing.sm),
                            ],
                            if (_showAdvanced) ...[
                              const SizedBox(height: AppSpacing.lg),
                              const CrSectionLabel('Club accounts (optional)'),
                              _OAuthCard(
                                title: 'YouTube',
                                connected: _youtubeOk,
                                ok: _youtubeLiveOk,
                                status: _youtubeOk
                                    ? (_youtubeLiveOk
                                        ? _channelTitle
                                        : '$_channelTitle — live permission missing')
                                    : (_youtubeOauthConfigured
                                        ? 'Not connected'
                                        : 'OAuth not configured on server'),
                                detail: _youtubeOk && !_youtubeLiveOk ? _youtubeLiveMessage : null,
                                onConnect: _youtubeOk ? null : _connectYoutube,
                                onReconnect: _youtubeOk && !_youtubeLiveOk
                                    ? () async {
                                        try {
                                          await widget.api.youtubeDisconnect();
                                        } catch (_) {}
                                        if (mounted) await _connectYoutube();
                                      }
                                    : null,
                              ),
                              const SizedBox(height: AppSpacing.sm),
                              _OAuthCard(
                                title: 'Twitch',
                                connected: _twitchOk,
                                ok: _twitchKeyOk,
                                status: _twitchOk
                                    ? (_twitchKeyOk
                                        ? _twitchDisplayName
                                        : '$_twitchDisplayName — stream key issue')
                                    : (_twitchOauthConfigured
                                        ? 'Not connected'
                                        : 'OAuth not configured on server'),
                                detail: _twitchOk && !_twitchKeyOk ? _twitchKeyMessage : null,
                                onConnect: (!_twitchOk && _twitchOauthConfigured) ? _connectTwitch : null,
                              ),
                            ],
                          ]),
                        ),
                      ),
                    ],
                  ),
                ),
        ),
      ),
      floatingActionButton: _streams.isNotEmpty
          ? FloatingActionButton.extended(
              onPressed: _newStream,
              icon: const Icon(Icons.add_rounded),
              label: const Text('New stream'),
            )
          : null,
    );
  }
}

class _OAuthCard extends StatelessWidget {
  const _OAuthCard({
    required this.title,
    required this.connected,
    required this.ok,
    required this.status,
    this.detail,
    this.onConnect,
    this.onReconnect,
  });

  final String title;
  final bool connected;
  final bool ok;
  final String status;
  final String? detail;
  final VoidCallback? onConnect;
  final VoidCallback? onReconnect;

  @override
  Widget build(BuildContext context) {
    return Container(
      padding: const EdgeInsets.all(AppSpacing.md),
      decoration: BoxDecoration(
        color: AppColors.surface,
        borderRadius: BorderRadius.circular(AppSpacing.radiusMd),
        border: Border.all(color: AppColors.borderSubtle),
      ),
      child: Column(
        crossAxisAlignment: CrossAxisAlignment.stretch,
        children: [
          Row(
            children: [
              Text(title, style: const TextStyle(fontWeight: FontWeight.w600, fontSize: 15)),
              const Spacer(),
              CrStatusChip(label: connected && ok ? 'Ready' : (connected ? 'Check' : 'Off'), ok: connected && ok),
            ],
          ),
          const SizedBox(height: 8),
          Text(status, style: appTextTheme.bodyMedium),
          if (detail != null && detail!.isNotEmpty) ...[
            const SizedBox(height: 6),
            Text(detail!, style: appTextTheme.bodySmall),
          ],
          if (onConnect != null) ...[
            const SizedBox(height: 12),
            OutlinedButton(onPressed: onConnect, child: Text('Connect $title')),
          ],
          if (onReconnect != null) ...[
            const SizedBox(height: 8),
            TextButton(onPressed: onReconnect, child: const Text('Reconnect')),
          ],
        ],
      ),
    );
  }
}

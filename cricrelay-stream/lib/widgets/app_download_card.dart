import 'package:flutter/material.dart';
import 'package:url_launcher/url_launcher.dart';

import '../services/api.dart';
import '../theme/app_theme.dart';

/// Shown after login: install / update links for Android and iOS builds from the server.
class AppDownloadCard extends StatefulWidget {
  const AppDownloadCard({super.key, required this.api});

  final CricRelayApi api;

  @override
  State<AppDownloadCard> createState() => _AppDownloadCardState();
}

class _AppDownloadCardState extends State<AppDownloadCard> {
  StreamAppBuilds? _builds;
  bool _loading = true;

  @override
  void initState() {
    super.initState();
    _load();
  }

  Future<void> _load() async {
    try {
      final b = await widget.api.getAppBuilds();
      if (mounted) setState(() => _builds = b);
    } catch (_) {
    } finally {
      if (mounted) setState(() => _loading = false);
    }
  }

  Future<void> _open(String? url) async {
    if (url == null || url.isEmpty) return;
    final uri = Uri.parse(url);
    if (!await launchUrl(uri, mode: LaunchMode.externalApplication)) {
      if (mounted) {
        ScaffoldMessenger.of(context).showSnackBar(
          const SnackBar(content: Text('Could not open link')),
        );
      }
    }
  }

  @override
  Widget build(BuildContext context) {
    if (_loading) {
      return const Padding(
        padding: EdgeInsets.only(bottom: AppSpacing.md),
        child: LinearProgressIndicator(minHeight: 2),
      );
    }
    final b = _builds;
    if (b == null || (!b.android.available && !b.ios.available)) {
      return const SizedBox.shrink();
    }
    return Padding(
      padding: const EdgeInsets.only(bottom: AppSpacing.md),
      child: Container(
        padding: const EdgeInsets.all(14),
        decoration: BoxDecoration(
          color: AppColors.surface,
          borderRadius: BorderRadius.circular(AppSpacing.radiusMd),
          border: Border.all(color: AppColors.border),
        ),
        child: Column(
          crossAxisAlignment: CrossAxisAlignment.stretch,
          children: [
            Row(
              children: [
                const Icon(Icons.download_outlined, size: 20, color: AppColors.accent),
                const SizedBox(width: 8),
                Text('Get the app · v${b.version}', style: appTextTheme.titleMedium),
              ],
            ),
            const SizedBox(height: 10),
            if (b.android.available) ...[
              _PlatformRow(
                icon: Icons.android,
                label: b.android.label.isNotEmpty ? b.android.label : 'Android',
                onTap: () => _open(b.android.url),
              ),
              const SizedBox(height: 8),
              Text(
                'If Google Play Protect warns when sideloading, tap Install anyway — '
                'or use the Play Store internal testing link when your club has one.',
                style: appTextTheme.bodySmall,
              ),
            ],
            if (b.ios.available) ...[
              if (b.android.available) const SizedBox(height: 8),
              _PlatformRow(
                icon: Icons.phone_iphone,
                label: b.ios.label.isNotEmpty ? b.ios.label : 'iPhone',
                onTap: () => _open(b.ios.otaInstallUrl ?? b.ios.url),
              ),
              if (b.ios.installNote.isNotEmpty) ...[
                const SizedBox(height: 8),
                Text(b.ios.installNote, style: appTextTheme.bodySmall),
              ],
              if (b.ios.streamingNote.isNotEmpty) ...[
                const SizedBox(height: 6),
                Text(b.ios.streamingNote, style: appTextTheme.bodySmall),
              ],
            ],
          ],
        ),
      ),
    );
  }
}

class _PlatformRow extends StatelessWidget {
  const _PlatformRow({
    required this.icon,
    required this.label,
    required this.onTap,
  });

  final IconData icon;
  final String label;
  final VoidCallback onTap;

  @override
  Widget build(BuildContext context) {
    return Material(
      color: AppColors.surfaceVariant,
      borderRadius: BorderRadius.circular(AppSpacing.radiusSm),
      child: InkWell(
        onTap: onTap,
        borderRadius: BorderRadius.circular(AppSpacing.radiusSm),
        child: Padding(
          padding: const EdgeInsets.symmetric(horizontal: 12, vertical: 10),
          child: Row(
            children: [
              Icon(icon, size: 22),
              const SizedBox(width: 12),
              Expanded(child: Text(label, style: const TextStyle(fontWeight: FontWeight.w600))),
              const Icon(Icons.open_in_new, size: 18, color: AppColors.onBackgroundDim),
            ],
          ),
        ),
      ),
    );
  }
}

import 'package:flutter/material.dart';
import 'package:url_launcher/url_launcher.dart';

import '../services/api.dart';
import '../theme/app_theme.dart';
import '../utils/url_validator.dart';
import '../widgets/studio/studio_shell.dart';
import '../widgets/ui_kit.dart';
import '../navigation/app_entry.dart';

class LoginScreen extends StatefulWidget {
  const LoginScreen({super.key, required this.api});

  final CricRelayApi api;

  @override
  State<LoginScreen> createState() => _LoginScreenState();
}

class _LoginScreenState extends State<LoginScreen> {
  late final TextEditingController _baseCtrl;
  final _emailCtrl = TextEditingController();
  final _passCtrl = TextEditingController();
  final _formKey = GlobalKey<FormState>();
  bool _busy = false;
  bool _obscure = true;
  String? _error;

  @override
  void initState() {
    super.initState();
    final saved = widget.api.baseUrl.trim();
    _baseCtrl = TextEditingController(
      text: saved.isNotEmpty ? saved : 'https://cricrelay.co.uk',
    );
  }

  Future<void> _openHome(CricRelayApi api) async {
    await openHomeOrOnboarding(context, api);
  }

  @override
  void dispose() {
    _baseCtrl.dispose();
    _emailCtrl.dispose();
    _passCtrl.dispose();
    super.dispose();
  }

  Future<void> _login() async {
    if (!_formKey.currentState!.validate()) return;
    setState(() {
      _busy = true;
      _error = null;
    });
    try {
      final base = normalizeApiBaseUrl(_baseCtrl.text);
      if (!isAllowedApiBaseUrl(base)) {
        throw Exception('Use HTTPS for your club server (http only for local testing).');
      }
      final api = CricRelayApi(base);
      await api.login(_emailCtrl.text.trim(), _passCtrl.text);
      if (!mounted) return;
      await _openHome(api);
    } catch (e) {
      setState(() => _error = e.toString().replaceFirst('Exception: ', ''));
    } finally {
      if (mounted) setState(() => _busy = false);
    }
  }

  Future<void> _openLegal(String path) async {
    final base = normalizeApiBaseUrl(_baseCtrl.text);
    final uri = Uri.parse('$base$path');
    if (!await launchUrl(uri, mode: LaunchMode.externalApplication)) {
      if (mounted) {
        ScaffoldMessenger.of(context).showSnackBar(
          SnackBar(content: Text('Could not open $uri')),
        );
      }
    }
  }

  @override
  Widget build(BuildContext context) {
    return Scaffold(
      body: CrStudioBackdrop(
        child: SafeArea(
          child: Center(
            child: SingleChildScrollView(
              padding: const EdgeInsets.symmetric(horizontal: AppSpacing.lg),
              child: ConstrainedBox(
                constraints: const BoxConstraints(maxWidth: 420),
                child: Form(
                  key: _formKey,
                  child: Column(
                    crossAxisAlignment: CrossAxisAlignment.stretch,
                    children: [
                      const SizedBox(height: AppSpacing.xl),
                      Center(
                        child: CrGlassPanel(
                          padding: const EdgeInsets.all(20),
                          borderRadius: AppSpacing.radiusLg,
                          child: const Icon(Icons.sensors_rounded, color: AppColors.primary, size: 44),
                        ),
                      ),
                      const SizedBox(height: AppSpacing.lg),
                      Text('CricRelay Live', style: appTextTheme.headlineLarge, textAlign: TextAlign.center),
                      const SizedBox(height: AppSpacing.sm),
                      Text(
                        'Professional cricket streaming with a live scoreboard burned into your broadcast.',
                        style: appTextTheme.bodyMedium,
                        textAlign: TextAlign.center,
                      ),
                      const SizedBox(height: AppSpacing.xl),
                      TextFormField(
                        controller: _baseCtrl,
                        decoration: const InputDecoration(
                          labelText: 'Club server',
                          hintText: 'https://cricrelay.co.uk',
                          prefixIcon: Icon(Icons.cloud_outlined),
                        ),
                        autocorrect: false,
                        keyboardType: TextInputType.url,
                        validator: (v) {
                          final s = (v ?? '').trim();
                          if (s.isEmpty) return 'Enter your server URL';
                          if (!isAllowedApiBaseUrl(normalizeApiBaseUrl(s))) {
                            return 'HTTPS required (http only for local dev)';
                          }
                          return null;
                        },
                      ),
                      const SizedBox(height: AppSpacing.md),
                      TextFormField(
                        controller: _emailCtrl,
                        decoration: const InputDecoration(
                          labelText: 'Email',
                          prefixIcon: Icon(Icons.email_outlined),
                        ),
                        keyboardType: TextInputType.emailAddress,
                        autocorrect: false,
                        validator: (v) =>
                            (v ?? '').trim().isEmpty ? 'Enter your club email' : null,
                      ),
                      const SizedBox(height: AppSpacing.md),
                      TextFormField(
                        controller: _passCtrl,
                        decoration: InputDecoration(
                          labelText: 'Password',
                          prefixIcon: const Icon(Icons.lock_outline),
                          suffixIcon: IconButton(
                            icon: Icon(_obscure ? Icons.visibility_off : Icons.visibility),
                            onPressed: () => setState(() => _obscure = !_obscure),
                          ),
                        ),
                        obscureText: _obscure,
                        validator: (v) => (v ?? '').isEmpty ? 'Enter your password' : null,
                        onFieldSubmitted: (_) => _busy ? null : _login(),
                      ),
                      if (_error != null) ...[
                        const SizedBox(height: AppSpacing.md),
                        CrErrorBanner(message: _error!),
                      ],
                      const SizedBox(height: AppSpacing.lg),
                      SizedBox(
                        height: 52,
                        child: FilledButton(
                          onPressed: _busy ? null : _login,
                          child: _busy
                              ? const SizedBox(
                                  width: 22,
                                  height: 22,
                                  child: CircularProgressIndicator(strokeWidth: 2, color: Colors.white),
                                )
                              : const Text('Sign in to studio'),
                        ),
                      ),
                      const SizedBox(height: AppSpacing.lg),
                      Text(
                        'New club? Register at cricrelay.co.uk, then sign in with the same email and password.',
                        style: appTextTheme.bodySmall,
                        textAlign: TextAlign.center,
                      ),
                      const SizedBox(height: AppSpacing.md),
                      Wrap(
                        alignment: WrapAlignment.center,
                        crossAxisAlignment: WrapCrossAlignment.center,
                        children: [
                          TextButton(
                            onPressed: _busy ? null : () => _openLegal('/privacy'),
                            child: const Text('Privacy Policy'),
                          ),
                          Text('·', style: appTextTheme.bodySmall),
                          TextButton(
                            onPressed: _busy ? null : () => _openLegal('/terms'),
                            child: const Text('Terms of Service'),
                          ),
                        ],
                      ),
                    ],
                  ),
                ),
              ),
            ),
          ),
        ),
      ),
    );
  }
}

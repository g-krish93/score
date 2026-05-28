import 'package:flutter/material.dart';

import '../services/api.dart';
import '../theme/app_theme.dart';
import '../utils/url_validator.dart';
import '../widgets/ui_kit.dart';
import 'live_home_screen.dart';

class LoginScreen extends StatefulWidget {
  const LoginScreen({super.key, required this.api});

  final CricRelayApi api;

  @override
  State<LoginScreen> createState() => _LoginScreenState();
}

class _LoginScreenState extends State<LoginScreen> {
  final _baseCtrl = TextEditingController(text: 'https://cricrelay.co.uk');
  final _emailCtrl = TextEditingController();
  final _passCtrl = TextEditingController();
  final _formKey = GlobalKey<FormState>();
  bool _busy = false;
  bool _obscure = true;
  String? _error;

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
      Navigator.of(context).pushReplacement(
        MaterialPageRoute(builder: (_) => LiveHomeScreen(api: api)),
      );
    } catch (e) {
      setState(() => _error = e.toString().replaceFirst('Exception: ', ''));
    } finally {
      if (mounted) setState(() => _busy = false);
    }
  }

  @override
  Widget build(BuildContext context) {
    return Scaffold(
      body: SafeArea(
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
                    const SizedBox(height: AppSpacing.lg),
                    Center(
                      child: Container(
                        width: 72,
                        height: 72,
                        decoration: BoxDecoration(
                          color: AppColors.surface,
                          borderRadius: BorderRadius.circular(18),
                          border: Border.all(color: AppColors.border),
                        ),
                        child: const Icon(Icons.sensors, color: AppColors.primary, size: 40),
                      ),
                    ),
                    const SizedBox(height: AppSpacing.lg),
                    Text('CricRelay Live', style: appTextTheme.headlineLarge, textAlign: TextAlign.center),
                    const SizedBox(height: AppSpacing.sm),
                    Text(
                      'Professional cricket streaming with a live scoreboard on your broadcast.',
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
                      height: 48,
                      child: FilledButton(
                        onPressed: _busy ? null : _login,
                        child: _busy
                            ? const SizedBox(
                                width: 22,
                                height: 22,
                                child: CircularProgressIndicator(strokeWidth: 2, color: Colors.white),
                              )
                            : const Text('Sign in'),
                      ),
                    ),
                    const SizedBox(height: AppSpacing.lg),
                    Text(
                      'New club? Register at cricrelay.co.uk, then sign in with the same email and password.',
                      style: appTextTheme.bodySmall,
                      textAlign: TextAlign.center,
                    ),
                  ],
                ),
              ),
            ),
          ),
        ),
      ),
    );
  }
}

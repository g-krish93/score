import 'package:flutter/material.dart';
import 'package:flutter/services.dart';

import 'screens/live_home_screen.dart';
import 'screens/login_screen.dart';
import 'services/api.dart';
import 'theme/app_theme.dart';
import 'widgets/ui_kit.dart';

void main() {
  WidgetsFlutterBinding.ensureInitialized();
  SystemChrome.setPreferredOrientations([
    DeviceOrientation.portraitUp,
    DeviceOrientation.landscapeLeft,
    DeviceOrientation.landscapeRight,
  ]);
  SystemChrome.setSystemUIOverlayStyle(
    const SystemUiOverlayStyle(
      statusBarColor: Colors.transparent,
      statusBarIconBrightness: Brightness.light,
    ),
  );
  runApp(const CricRelayStreamApp());
}

class CricRelayStreamApp extends StatelessWidget {
  const CricRelayStreamApp({super.key});

  @override
  Widget build(BuildContext context) {
    final theme = buildAppTheme();
    return MaterialApp(
      title: 'CricRelay Live',
      debugShowCheckedModeBanner: false,
      theme: theme.copyWith(textTheme: appTextTheme),
      home: const _Bootstrap(),
    );
  }
}

class _Bootstrap extends StatefulWidget {
  const _Bootstrap();

  @override
  State<_Bootstrap> createState() => _BootstrapState();
}

class _BootstrapState extends State<_Bootstrap> {
  Widget? _home;

  @override
  void initState() {
    super.initState();
    _load();
  }

  Future<void> _load() async {
    final api = await CricRelayApi.load();
    if (!mounted) return;
    setState(() {
      _home = api.hasToken
          ? LiveHomeScreen(api: api)
          : LoginScreen(api: api);
    });
  }

  @override
  Widget build(BuildContext context) {
    return _home ?? const CrBootstrapLoading();
  }
}

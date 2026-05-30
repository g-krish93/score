import 'package:flutter/material.dart';
import 'package:webview_flutter/webview_flutter.dart';

class ManualScoringScreen extends StatefulWidget {
  const ManualScoringScreen({super.key, required this.inputUrl});

  final String inputUrl;

  @override
  State<ManualScoringScreen> createState() => _ManualScoringScreenState();
}

class _ManualScoringScreenState extends State<ManualScoringScreen> {
  late final WebViewController _web;

  @override
  void initState() {
    super.initState();
    _web = WebViewController()
      ..setJavaScriptMode(JavaScriptMode.unrestricted)
      ..loadRequest(Uri.parse(widget.inputUrl));
  }

  @override
  Widget build(BuildContext context) {
    return Scaffold(
      appBar: AppBar(title: const Text('Manual scoring')),
      body: WebViewWidget(controller: _web),
    );
  }
}

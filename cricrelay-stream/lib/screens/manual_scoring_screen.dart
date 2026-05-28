import 'package:flutter/material.dart';
import 'package:webview_flutter/webview_flutter.dart';

class ManualScoringScreen extends StatelessWidget {
  const ManualScoringScreen({super.key, required this.inputUrl});

  final String inputUrl;

  @override
  Widget build(BuildContext context) {
    final web = WebViewController()
      ..setJavaScriptMode(JavaScriptMode.unrestricted)
      ..loadRequest(Uri.parse(inputUrl));
    return Scaffold(
      appBar: AppBar(title: const Text('Manual scoring')),
      body: WebViewWidget(controller: web),
    );
  }
}

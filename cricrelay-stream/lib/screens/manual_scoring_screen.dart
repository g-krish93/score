import 'package:flutter/material.dart';
import 'package:webview_flutter/webview_flutter.dart';

class ManualScoringScreen extends StatefulWidget {
  const ManualScoringScreen({
    super.key,
    required this.inputUrl,
    this.matchLabel = '',
  });

  final String inputUrl;
  final String matchLabel;

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
    final title = widget.matchLabel.isNotEmpty
        ? 'Scorer — ${widget.matchLabel}'
        : 'Manual scoring';
    return Scaffold(
      appBar: AppBar(
        title: Text(title),
        bottom: PreferredSize(
          preferredSize: const Size.fromHeight(36),
          child: Padding(
            padding: const EdgeInsets.fromLTRB(16, 0, 16, 8),
            child: Text(
              'Best on a 2nd phone. Scoring on the same phone as the stream is awkward.',
              style: Theme.of(context).textTheme.bodySmall,
            ),
          ),
        ),
      ),
      body: WebViewWidget(controller: _web),
    );
  }
}

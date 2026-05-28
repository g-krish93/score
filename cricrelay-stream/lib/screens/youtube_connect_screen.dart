import 'package:flutter/material.dart';
import 'package:webview_flutter/webview_flutter.dart';

class YoutubeConnectScreen extends StatefulWidget {
  const YoutubeConnectScreen({super.key, required this.authorizeUrl});

  final String authorizeUrl;

  @override
  State<YoutubeConnectScreen> createState() => _YoutubeConnectScreenState();
}

class _YoutubeConnectScreenState extends State<YoutubeConnectScreen> {
  late final WebViewController _web;

  @override
  void initState() {
    super.initState();
    _web = WebViewController()
      ..setJavaScriptMode(JavaScriptMode.unrestricted)
      ..setNavigationDelegate(
        NavigationDelegate(
          onPageFinished: (url) {
            if (url.contains('/dashboard/youtube/callback') &&
                url.contains('error=') == false &&
                mounted) {
              Navigator.of(context).pop(true);
            }
            if (url.contains('YouTube connected') && mounted) {
              Navigator.of(context).pop(true);
            }
          },
        ),
      )
      ..loadRequest(Uri.parse(widget.authorizeUrl));
  }

  @override
  Widget build(BuildContext context) {
    return Scaffold(
      appBar: AppBar(title: const Text('Connect YouTube')),
      body: WebViewWidget(controller: _web),
    );
  }
}

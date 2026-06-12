import 'package:flutter/material.dart';
import 'package:webview_flutter/webview_flutter.dart';

/// Scoreboard embed scaled to fit the preview strip (avoids half-cut overlay text).
class ScoreboardStripWebView extends StatelessWidget {
  const ScoreboardStripWebView({
    super.key,
    required this.controller,
  });

  final WebViewController controller;

  static const _fitScript = r'''
(function() {
  var doc = document.documentElement;
  var body = document.body;
  if (!body) return;
  body.style.overflow = 'hidden';
  body.style.margin = '0';
  doc.style.overflow = 'hidden';
  var w = body.scrollWidth || doc.scrollWidth || 800;
  var target = window.innerWidth || document.documentElement.clientWidth;
  if (w > 0 && target > 0) {
    var scale = target / w;
    body.style.transformOrigin = 'top left';
    body.style.transform = 'scale(' + scale + ')';
    body.style.width = (100 / scale) + '%';
  }
})();
''';

  static WebViewController createController() {
    late final WebViewController controller;
    controller = WebViewController()
      ..setJavaScriptMode(JavaScriptMode.unrestricted)
      ..setBackgroundColor(const Color(0x00000000))
      ..setNavigationDelegate(
        NavigationDelegate(
          onPageFinished: (_) {
            controller.runJavaScript(_fitScript);
          },
        ),
      );
    return controller;
  }

  static Future<void> loadUrl(WebViewController controller, String url) async {
    await controller.loadRequest(Uri.parse(url));
  }

  @override
  Widget build(BuildContext context) {
    return ColoredBox(
      color: Colors.transparent,
      child: WebViewWidget(controller: controller),
    );
  }
}

import 'package:cricrelay_stream/main.dart';
import 'package:flutter_test/flutter_test.dart';

void main() {
  testWidgets('app builds', (WidgetTester tester) async {
    await tester.pumpWidget(const CricRelayStreamApp());
    await tester.pump();
    expect(find.byType(CricRelayStreamApp), findsOneWidget);
  });
}

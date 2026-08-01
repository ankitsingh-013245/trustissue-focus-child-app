import 'package:flutter/material.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:trustissue/screens/select_apps_screen.dart';
import 'package:trustissue/theme/app_theme.dart';

void main() {
  testWidgets('Save commits a valid domain still present in the text field',
      (tester) async {
    Future<Set<String>?>? result;
    await tester.pumpWidget(
      MaterialApp(
        theme: AppTheme.build(),
        home: Scaffold(
          body: Builder(
            builder: (context) => TextButton(
              onPressed: () {
                result = showWebsiteRulesSheet(
                  context,
                  initialDomains: const {},
                );
              },
              child: const Text('Open rules'),
            ),
          ),
        ),
      ),
    );

    await tester.tap(find.text('Open rules'));
    await tester.pumpAndSettle();
    await tester.enterText(
      find.byType(TextField),
      'https://Example.COM/watch?v=1',
    );
    await tester.tap(find.text('Save domains'));
    await tester.pumpAndSettle();

    expect(await result, {'example.com'});
  });

  testWidgets('Invalid pending text keeps the editor open', (tester) async {
    await tester.pumpWidget(
      MaterialApp(
        theme: AppTheme.build(),
        home: Scaffold(
          body: Builder(
            builder: (context) => TextButton(
              onPressed: () => showWebsiteRulesSheet(
                context,
                initialDomains: const {},
              ),
              child: const Text('Open rules'),
            ),
          ),
        ),
      ),
    );

    await tester.tap(find.text('Open rules'));
    await tester.pumpAndSettle();
    await tester.enterText(find.byType(TextField), 'not-a-domain');
    await tester.tap(find.text('Save domains'));
    await tester.pumpAndSettle();

    expect(find.text('Enter a domain like example.com'), findsOneWidget);
    expect(find.text('Save domains'), findsOneWidget);
  });
}

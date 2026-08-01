import 'package:flutter/material.dart';

import 'screens/home_shell.dart';
import 'screens/onboarding_screen.dart';
import 'services/settings_store.dart';
import 'theme/app_theme.dart';
import 'widgets/aurora_background.dart';
import 'widgets/panda_loading_screen.dart';

void main() {
  WidgetsFlutterBinding.ensureInitialized();
  runApp(const TrustIssueApp());
}

class TrustIssueApp extends StatelessWidget {
  const TrustIssueApp({super.key});

  @override
  Widget build(BuildContext context) {
    return MaterialApp(
      debugShowCheckedModeBanner: false,
      title: 'TrustIssue Focus',
      theme: AppTheme.build(),
      home: const _RootGate(),
    );
  }
}

/// Decides between the first-run onboarding flow and the main app based on the
/// persisted onboarding flag.
class _RootGate extends StatefulWidget {
  const _RootGate();

  @override
  State<_RootGate> createState() => _RootGateState();
}

class _RootGateState extends State<_RootGate> {
  final _settings = SettingsStore();
  bool? _onboarded;

  @override
  void initState() {
    super.initState();
    _resolve();
  }

  Future<void> _resolve() async {
    var onboarded = false;
    try {
      await _settings.migrateToLocalOnly();
      onboarded = await _settings.isOnboardingComplete();
    } catch (_) {
      onboarded = false;
    }
    if (!mounted) return;
    setState(() => _onboarded = onboarded);
  }

  @override
  Widget build(BuildContext context) {
    final onboarded = _onboarded;
    if (onboarded == null) {
      return const Scaffold(
        backgroundColor: AppColors.night,
        body: AuroraBackground(
          child: PandaLoadingScreen(),
        ),
      );
    }
    return onboarded ? const HomeShell() : const OnboardingScreen();
  }
}

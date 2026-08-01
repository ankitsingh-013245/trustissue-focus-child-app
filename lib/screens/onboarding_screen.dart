import 'package:flutter/material.dart';

import '../services/native_bridge.dart';
import '../services/settings_store.dart';
import '../theme/app_theme.dart';
import '../widgets/glass_card.dart';
import '../widgets/pdf_reader_picker.dart';
import '../widgets/primary_button.dart';
import 'home_shell.dart';

/// Compact protection setup. Only the two permissions required by compatible
/// blocking are requested here; advanced Accessibility remains opt-in later.
class OnboardingScreen extends StatefulWidget {
  const OnboardingScreen({super.key});

  @override
  State<OnboardingScreen> createState() => _OnboardingScreenState();
}

class _OnboardingScreenState extends State<OnboardingScreen>
    with WidgetsBindingObserver {
  final _native = NativeBridge();
  final _settings = SettingsStore();

  bool _usageAccess = false;
  bool _overlayAccess = false;
  bool _checking = true;
  bool _continuing = false;

  @override
  void initState() {
    super.initState();
    WidgetsBinding.instance.addObserver(this);
    WidgetsBinding.instance.addPostFrameCallback((_) => _refreshAccess());
  }

  @override
  void dispose() {
    WidgetsBinding.instance.removeObserver(this);
    super.dispose();
  }

  @override
  void didChangeAppLifecycleState(AppLifecycleState state) {
    if (state == AppLifecycleState.resumed) {
      _refreshAccess();
    }
  }

  Future<void> _refreshAccess() async {
    final results = await Future.wait<bool>([
      _native.hasUsageAccess(),
      _native.hasOverlayAccess(),
    ]);
    if (!mounted) return;
    setState(() {
      _usageAccess = results[0];
      _overlayAccess = results[1];
      _checking = false;
    });
  }

  Future<void> _handlePrimaryAction() async {
    if (_checking || _continuing) return;
    await _refreshAccess();
    if (!mounted) return;
    if (!_usageAccess) {
      await _native.openUsageAccessSettings();
      return;
    }
    if (!_overlayAccess) {
      await _native.openOverlaySettings();
      return;
    }
    await _continue();
  }

  Future<void> _openUsageSettings() async {
    await _native.openUsageAccessSettings();
  }

  Future<void> _openOverlaySettings() async {
    await _native.openOverlaySettings();
  }

  Future<void> _continue() async {
    if (_continuing) return;
    setState(() => _continuing = true);

    final study = await _settings.loadStudySettings();
    List<InstalledAppInfo> readers = const [];
    try {
      readers = await _native.getPdfReaderApps();
    } catch (_) {
      // The no-PDF option keeps setup usable on devices without a reader.
    }
    if (!mounted) return;

    final choice = await showPdfReaderPicker(
      context: context,
      readers: readers,
      currentPackage: study.defaultPdfReaderPackage,
      dismissible: false,
    );
    if (choice == null) {
      if (mounted) setState(() => _continuing = false);
      return;
    }

    await _settings.saveStudySettings(
      study.copyWith(
        defaultPdfReaderPackage: choice == noPdfReaderChoice ? '' : choice,
        pdfReaderSetupComplete: true,
      ),
    );
    await _settings.setOnboardingComplete(true);
    if (!mounted) return;
    Navigator.of(context).pushAndRemoveUntil(
      MaterialPageRoute(builder: (_) => const HomeShell()),
      (route) => false,
    );
  }

  String get _primaryLabel {
    if (_checking) return 'Checking protection...';
    if (_continuing) return 'Opening...';
    if (!_usageAccess) return 'Allow Usage Access';
    if (!_overlayAccess) return 'Allow Display Over Apps';
    return 'Continue';
  }

  @override
  Widget build(BuildContext context) {
    return Scaffold(
      backgroundColor: AppColors.page,
      body: SafeArea(
        child: LayoutBuilder(
          builder: (context, constraints) => SingleChildScrollView(
            padding: const EdgeInsets.fromLTRB(22, 22, 22, 24),
            child: ConstrainedBox(
              constraints: BoxConstraints(
                minHeight: constraints.maxHeight - 46,
              ),
              child: IntrinsicHeight(
                child: Column(
                  crossAxisAlignment: CrossAxisAlignment.start,
                  children: [
                    Row(
                      children: [
                        Container(
                          width: 44,
                          height: 44,
                          decoration: BoxDecoration(
                            color: AppColors.emerald.withValues(alpha: 0.14),
                            borderRadius: BorderRadius.circular(14),
                            border: Border.all(
                              color: AppColors.emerald.withValues(alpha: 0.35),
                            ),
                          ),
                          child: const Icon(
                            Icons.shield_outlined,
                            color: AppColors.emerald,
                            size: 24,
                          ),
                        ),
                        const SizedBox(width: 12),
                        const Text(
                          'TrustIssue',
                          style: TextStyle(
                            color: AppColors.textPrimary,
                            fontSize: 17,
                            fontWeight: FontWeight.w800,
                          ),
                        ),
                        const Spacer(),
                        const Text(
                          'SETUP',
                          style: TextStyle(
                            color: AppColors.textMuted,
                            fontSize: 10,
                            fontWeight: FontWeight.w800,
                            letterSpacing: 1.4,
                          ),
                        ),
                      ],
                    ),
                    const SizedBox(height: 38),
                    Text(
                      'Set up reliable\nblocking.',
                      style: Theme.of(context).textTheme.headlineMedium,
                    ),
                    const SizedBox(height: 12),
                    const Text(
                      'Allow the two Android permissions used to notice the '
                      'foreground app and place the block screen above it.',
                      style: TextStyle(
                        color: AppColors.textSecondary,
                        fontSize: 14,
                        height: 1.45,
                      ),
                    ),
                    const SizedBox(height: 26),
                    SolidCard(
                      padding: const EdgeInsets.all(14),
                      child: Column(
                        children: [
                          _PermissionRow(
                            icon: Icons.query_stats_rounded,
                            title: 'Usage Access',
                            detail: 'Detects app changes, never screen content',
                            granted: _usageAccess,
                            onTap: _usageAccess ? null : _openUsageSettings,
                          ),
                          const Divider(
                            height: 25,
                            color: AppColors.surfaceDivider,
                          ),
                          _PermissionRow(
                            icon: Icons.picture_in_picture_alt_rounded,
                            title: 'Display Over Apps',
                            detail: 'Shows the block gate and floating timer',
                            granted: _overlayAccess,
                            onTap: _overlayAccess ? null : _openOverlaySettings,
                          ),
                        ],
                      ),
                    ),
                    const SizedBox(height: 14),
                    const Row(
                      crossAxisAlignment: CrossAxisAlignment.start,
                      children: [
                        Icon(
                          Icons.lock_outline_rounded,
                          color: AppColors.textMuted,
                          size: 16,
                        ),
                        SizedBox(width: 8),
                        Expanded(
                          child: Text(
                            'Only these required permissions are requested '
                            'during setup. Advanced access stays optional.',
                            style: TextStyle(
                              color: AppColors.textMuted,
                              fontSize: 12,
                              height: 1.4,
                            ),
                          ),
                        ),
                      ],
                    ),
                    const Spacer(),
                    const SizedBox(height: 28),
                    PrimaryButton(
                      label: _primaryLabel,
                      icon: _usageAccess && _overlayAccess
                          ? Icons.arrow_forward_rounded
                          : Icons.open_in_new_rounded,
                      onPressed: _checking || _continuing
                          ? null
                          : _handlePrimaryAction,
                    ),
                    const SizedBox(height: 11),
                    Center(
                      child: Text(
                        '${(_usageAccess ? 1 : 0) + (_overlayAccess ? 1 : 0)} of 2 ready',
                        style: const TextStyle(
                          color: AppColors.textMuted,
                          fontSize: 12,
                          fontWeight: FontWeight.w600,
                        ),
                      ),
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

class _PermissionRow extends StatelessWidget {
  const _PermissionRow({
    required this.icon,
    required this.title,
    required this.detail,
    required this.granted,
    required this.onTap,
  });

  final IconData icon;
  final String title;
  final String detail;
  final bool granted;
  final VoidCallback? onTap;

  @override
  Widget build(BuildContext context) {
    return InkWell(
      borderRadius: BorderRadius.circular(AppRadius.sm),
      onTap: onTap,
      child: Padding(
        padding: const EdgeInsets.symmetric(vertical: 3),
        child: Row(
          children: [
            Container(
              width: 40,
              height: 40,
              alignment: Alignment.center,
              decoration: BoxDecoration(
                color: granted
                    ? AppColors.emerald.withValues(alpha: 0.12)
                    : AppColors.surfaceRaised,
                borderRadius: BorderRadius.circular(12),
              ),
              child: Icon(
                icon,
                color: granted ? AppColors.emerald : AppColors.textSecondary,
                size: 21,
              ),
            ),
            const SizedBox(width: 12),
            Expanded(
              child: Column(
                crossAxisAlignment: CrossAxisAlignment.start,
                children: [
                  Text(
                    title,
                    style: const TextStyle(
                      color: AppColors.textPrimary,
                      fontSize: 14,
                      fontWeight: FontWeight.w700,
                    ),
                  ),
                  const SizedBox(height: 2),
                  Text(
                    detail,
                    style: const TextStyle(
                      color: AppColors.textMuted,
                      fontSize: 11.5,
                    ),
                  ),
                ],
              ),
            ),
            const SizedBox(width: 8),
            Container(
              padding: const EdgeInsets.symmetric(horizontal: 9, vertical: 5),
              decoration: BoxDecoration(
                color: granted
                    ? AppColors.emerald.withValues(alpha: 0.12)
                    : AppColors.surfaceRaised,
                borderRadius: BorderRadius.circular(AppRadius.pill),
              ),
              child: Row(
                mainAxisSize: MainAxisSize.min,
                children: [
                  Icon(
                    granted ? Icons.check_rounded : Icons.arrow_forward_rounded,
                    color:
                        granted ? AppColors.emerald : AppColors.textSecondary,
                    size: 15,
                  ),
                  const SizedBox(width: 4),
                  Text(
                    granted ? 'Ready' : 'Allow',
                    style: TextStyle(
                      color:
                          granted ? AppColors.emerald : AppColors.textSecondary,
                      fontSize: 11,
                      fontWeight: FontWeight.w800,
                    ),
                  ),
                ],
              ),
            ),
          ],
        ),
      ),
    );
  }
}

import 'package:flutter/material.dart';

import '../services/native_bridge.dart';
import '../services/settings_store.dart';
import '../theme/app_theme.dart';
import '../widgets/app_icon_tile.dart';
import '../widgets/glass_card.dart';
import '../widgets/pdf_reader_picker.dart';

class SettingsScreen extends StatefulWidget {
  const SettingsScreen({
    super.key,
    this.embedded = false,
    this.active = true,
  });

  final bool embedded;
  final bool active;

  @override
  State<SettingsScreen> createState() => _SettingsScreenState();
}

class _SettingsScreenState extends State<SettingsScreen>
    with WidgetsBindingObserver {
  final _native = NativeBridge();
  final _settings = SettingsStore();

  bool _accessibility = false;
  bool _usageAccess = false;
  bool _overlayAccess = false;
  bool _batteryUnrestricted = false;
  bool _dndAccess = false;
  bool _exportingLog = false;
  bool _clearingData = false;
  bool _loadingPdfReaders = false;
  bool _refreshing = false;
  StudySettings? _study;
  List<InstalledAppInfo> _pdfReaders = const [];

  @override
  void initState() {
    super.initState();
    WidgetsBinding.instance.addObserver(this);
    if (_shouldRefresh) _refresh();
  }

  @override
  void didUpdateWidget(covariant SettingsScreen oldWidget) {
    super.didUpdateWidget(oldWidget);
    if (!oldWidget.active && widget.active && _shouldRefresh) {
      _refresh();
    }
  }

  @override
  void dispose() {
    WidgetsBinding.instance.removeObserver(this);
    super.dispose();
  }

  @override
  void didChangeAppLifecycleState(AppLifecycleState state) {
    if (state == AppLifecycleState.resumed && _shouldRefresh) _refresh();
  }

  bool get _shouldRefresh => !widget.embedded || widget.active;

  Future<void> _refresh() async {
    if (_refreshing || !_shouldRefresh) return;
    _refreshing = true;
    Future<T> keepCurrent<T>(Future<T> request, T current) async {
      try {
        return await request;
      } catch (_) {
        return current;
      }
    }

    try {
      final results = await Future.wait<dynamic>([
        keepCurrent(_native.hasAccessibilityAccess(), _accessibility),
        keepCurrent(_native.hasUsageAccess(), _usageAccess),
        keepCurrent(_native.hasOverlayAccess(), _overlayAccess),
        keepCurrent(
          _native.hasBatteryOptimizationExemption(),
          _batteryUnrestricted,
        ),
        keepCurrent(_native.hasDndPolicyAccess(), _dndAccess),
        keepCurrent(_settings.loadStudySettings(), _study),
        keepCurrent(_native.getPdfReaderApps(), _pdfReaders),
      ]);
      if (!mounted) return;
      setState(() {
        _accessibility = results[0] as bool;
        _usageAccess = results[1] as bool;
        _overlayAccess = results[2] as bool;
        _batteryUnrestricted = results[3] as bool;
        _dndAccess = results[4] as bool;
        _study = results[5] as StudySettings?;
        _pdfReaders = results[6] as List<InstalledAppInfo>;
      });
    } finally {
      _refreshing = false;
    }
  }

  InstalledAppInfo? get _selectedPdfReader {
    final packageName = _study?.defaultPdfReaderPackage ?? '';
    if (packageName.isEmpty) return null;
    for (final reader in _pdfReaders) {
      if (reader.packageName == packageName) return reader;
    }
    return null;
  }

  Future<void> _choosePdfReader() async {
    if (_loadingPdfReaders) return;
    final current = await _settings.loadStudySettings();
    if (!mounted) return;
    setState(() => _study = current);
    if (current.enabled) {
      _showSnack('End the active focus session before changing this app.');
      return;
    }

    setState(() => _loadingPdfReaders = true);
    List<InstalledAppInfo> readers;
    try {
      readers = await _native.getPdfReaderApps();
    } catch (_) {
      if (mounted) setState(() => _loadingPdfReaders = false);
      _showSnack('Could not load PDF reader apps. Please try again.');
      return;
    }
    if (!mounted) return;
    setState(() {
      _pdfReaders = readers;
      _loadingPdfReaders = false;
    });

    final choice = await showPdfReaderPicker(
      context: context,
      readers: readers,
      currentPackage: current.defaultPdfReaderPackage,
    );
    if (choice == null) return;

    // The embedded Settings tab stays mounted while other tabs can change
    // Focus and Web Protection. Reload before writing so a PDF-only edit never
    // restores stale session or protection flags.
    final latest = await _settings.loadStudySettings();
    if (!mounted) return;
    if (latest.enabled) {
      setState(() => _study = latest);
      _showSnack('End the active focus session before changing this app.');
      return;
    }
    final updated = latest.copyWith(
      defaultPdfReaderPackage: choice == noPdfReaderChoice ? '' : choice,
      pdfReaderSetupComplete: true,
    );
    // This screen owns only the PDF preference. A field-scoped write cannot
    // overwrite newer Focus or Website Protection values from another tab.
    await _settings.savePdfReaderSelection(updated.defaultPdfReaderPackage);
    if (!mounted) return;
    setState(() => _study = updated);
    _showSnack('PDF reader updated.');
  }

  Future<void> _reviewAccessibility() async {
    final accepted = await showDialog<bool>(
      context: context,
      builder: (context) => AlertDialog(
        backgroundColor: AppColors.card,
        surfaceTintColor: Colors.transparent,
        title: const Text('Advanced Protection'),
        content: const Text(
          'Accessibility is optional for every Focus mode. It can make app '
          'change detection faster on some phones and assist advanced '
          'temporary handoffs when a study app opens a PDF reader or Gallery. '
          'Only features carrying the small Accessibility badge need it. '
          'If YouTube Shorts or Instagram Reels Blocking is separately enabled, '
          'visible accessibility labels and view identifiers from that app are '
          'checked in memory to identify short-form content and a swipe. '
          'TrustIssue does not capture screenshots, read notifications, passwords '
          'or messages, or store or send those labels.',
        ),
        actions: [
          TextButton(
            onPressed: () => Navigator.of(context).pop(false),
            child: const Text('Cancel'),
          ),
          FilledButton(
            onPressed: () => Navigator.of(context).pop(true),
            child: const Text('Open Android Settings'),
          ),
        ],
      ),
    );
    if (accepted == true) await _native.openAccessibilitySettings();
  }

  Future<void> _reviewUsageAccess() async {
    final accepted = await showDialog<bool>(
      context: context,
      builder: (context) => AlertDialog(
        backgroundColor: AppColors.card,
        surfaceTintColor: Colors.transparent,
        title: const Text('Usage Access'),
        content: const Text(
          'Focus and Daily App Limits use package names and change times to notice '
          'which app is in front. It does not read anything shown inside the '
          'app.',
        ),
        actions: [
          TextButton(
            onPressed: () => Navigator.of(context).pop(false),
            child: const Text('Cancel'),
          ),
          FilledButton(
            onPressed: () => Navigator.of(context).pop(true),
            child: const Text('Open Android Settings'),
          ),
        ],
      ),
    );
    if (accepted == true) await _native.openUsageAccessSettings();
  }

  Future<void> _reviewOverlayAccess() async {
    final accepted = await showDialog<bool>(
      context: context,
      builder: (context) => AlertDialog(
        backgroundColor: AppColors.card,
        surfaceTintColor: Colors.transparent,
        title: const Text('Display Over Apps'),
        content: const Text(
          'Normal Focus uses this only while a session is active to place the '
          'block screen and floating timer above selected apps.',
        ),
        actions: [
          TextButton(
            onPressed: () => Navigator.of(context).pop(false),
            child: const Text('Cancel'),
          ),
          FilledButton(
            onPressed: () => Navigator.of(context).pop(true),
            child: const Text('Open Android Settings'),
          ),
        ],
      ),
    );
    if (accepted == true) await _native.openOverlaySettings();
  }

  Future<void> _reviewBatterySettings() async {
    final accepted = await showDialog<bool>(
      context: context,
      builder: (context) => AlertDialog(
        backgroundColor: AppColors.card,
        surfaceTintColor: Colors.transparent,
        title: const Text('Background Reliability'),
        content: const Text(
          'The active Focus notification already keeps normal blocking alive. '
          'On phones with aggressive battery saving, you can additionally '
          'allow TrustIssue to run without battery optimization. This is '
          'recommended but not required.',
        ),
        actions: [
          TextButton(
            onPressed: () => Navigator.of(context).pop(false),
            child: const Text('Not now'),
          ),
          FilledButton(
            onPressed: () => Navigator.of(context).pop(true),
            child: const Text('Open Battery Settings'),
          ),
        ],
      ),
    );
    if (accepted == true) {
      await _native.openBatteryOptimizationSettings();
    }
  }

  Future<void> _exportDebugLog() async {
    if (_exportingLog) return;
    setState(() => _exportingLog = true);
    try {
      final path = await _native.exportDebugLog();
      _showSnack('Saved redacted diagnostic log to $path');
    } catch (_) {
      _showSnack('Could not save the diagnostic log.');
    } finally {
      if (mounted) setState(() => _exportingLog = false);
    }
  }

  Future<void> _clearAnalytics() async {
    final confirmed = await showDialog<bool>(
      context: context,
      builder: (context) => AlertDialog(
        backgroundColor: AppColors.card,
        surfaceTintColor: Colors.transparent,
        title: const Text('Delete local reports?'),
        content: const Text(
          'This permanently deletes focus history and diagnostic activity '
          'stored on this phone. Your selected apps and active focus session '
          'will not be changed.',
        ),
        actions: [
          TextButton(
            onPressed: () => Navigator.of(context).pop(false),
            child: const Text('Cancel'),
          ),
          FilledButton(
            onPressed: () => Navigator.of(context).pop(true),
            style: FilledButton.styleFrom(backgroundColor: AppColors.danger),
            child: const Text('Delete reports'),
          ),
        ],
      ),
    );
    if (confirmed != true) return;
    setState(() => _clearingData = true);
    try {
      await _native.clearLocalAnalytics();
      _showSnack('Local reports deleted.');
    } catch (_) {
      _showSnack('Local reports could not be deleted. Please try again.');
    } finally {
      if (mounted) setState(() => _clearingData = false);
    }
  }

  void _showSnack(String message) {
    if (!mounted) return;
    ScaffoldMessenger.of(context)
      ..clearSnackBars()
      ..showSnackBar(
        SnackBar(
          content: Text(message),
          behavior: SnackBarBehavior.floating,
          backgroundColor: AppColors.card,
        ),
      );
  }

  @override
  Widget build(BuildContext context) {
    return Scaffold(
      backgroundColor: Colors.black,
      body: ColoredBox(
        color: Colors.black,
        child: SafeArea(
          child: Column(
            crossAxisAlignment: CrossAxisAlignment.start,
            children: [
              Row(
                children: [
                  if (!widget.embedded)
                    IconButton(
                      onPressed: () => Navigator.of(context).maybePop(),
                      icon: const Icon(Icons.arrow_back_rounded),
                    )
                  else
                    const SizedBox(width: 16),
                  Text('Settings',
                      style: widget.embedded
                          ? Theme.of(context).textTheme.headlineSmall
                          : Theme.of(context).textTheme.titleLarge),
                ],
              ),
              Expanded(
                child: ListView(
                  padding: EdgeInsets.fromLTRB(
                    16,
                    4,
                    16,
                    widget.embedded ? 126 : 28,
                  ),
                  children: [
                    Text('Study tools',
                        style: Theme.of(context).textTheme.titleMedium),
                    const SizedBox(height: 8),
                    _PdfReaderSettingsRow(
                      reader: _selectedPdfReader,
                      hasSavedReader:
                          (_study?.defaultPdfReaderPackage ?? '').isNotEmpty,
                      loading: _loadingPdfReaders,
                      onTap: _loadingPdfReaders ? null : _choosePdfReader,
                    ),
                    const SizedBox(height: 18),
                    Text('Protection',
                        style: Theme.of(context).textTheme.titleMedium),
                    const SizedBox(height: 8),
                    Column(
                      children: [
                        _SettingsRow(
                          icon: Icons.query_stats_rounded,
                          title: 'Usage Access',
                          subtitle: _usageAccess
                              ? 'Foreground detection is ready'
                              : 'Required for app blocking',
                          trailing: _statusIcon(_usageAccess),
                          onTap: _reviewUsageAccess,
                        ),
                        const _SettingsDivider(),
                        _SettingsRow(
                          icon: Icons.picture_in_picture_alt_outlined,
                          title: 'Display Over Apps',
                          subtitle: _overlayAccess
                              ? 'Block screen is ready'
                              : 'Required for compatible blocking',
                          trailing: _statusIcon(_overlayAccess),
                          onTap: _reviewOverlayAccess,
                        ),
                        const _SettingsDivider(),
                        _SettingsRow(
                          icon: Icons.accessibility_new_outlined,
                          title: 'Advanced Protection',
                          subtitle: _accessibility
                              ? 'Fast detection is active'
                              : 'Optional faster detection',
                          trailing: _statusIcon(_accessibility),
                          onTap: _reviewAccessibility,
                        ),
                        const _SettingsDivider(),
                        _SettingsRow(
                          icon: Icons.do_not_disturb_on_outlined,
                          title: 'Strict DND',
                          subtitle: _dndAccess
                              ? 'Ready for Strict sessions'
                              : 'Needed only by Strict modes',
                          trailing: _statusIcon(_dndAccess),
                          onTap: () => _native.openDndPolicyAccessSettings(),
                        ),
                        const _SettingsDivider(),
                        _SettingsRow(
                          icon: Icons.battery_saver_outlined,
                          title: 'Background Reliability',
                          subtitle: _batteryUnrestricted
                              ? 'Battery restriction removed'
                              : 'Recommended for long sessions',
                          trailing: _statusIcon(_batteryUnrestricted),
                          onTap: _reviewBatterySettings,
                        ),
                      ],
                    ),
                    const SizedBox(height: 18),
                    Text('Privacy',
                        style: Theme.of(context).textTheme.titleMedium),
                    const SizedBox(height: 8),
                    const _SettingsNotice(
                      icon: Icons.lock_outline_rounded,
                      title: 'Local-only by design',
                      body:
                          'No account, parent connection, notification content, '
                          'cloud sync or advertising profile. Focus rules and '
                          'reports remain on this phone.',
                    ),
                    const SizedBox(height: 10),
                    Column(
                      children: [
                        _SettingsRow(
                          icon: Icons.policy_outlined,
                          title: 'Privacy details',
                          subtitle: 'What TrustIssue observes and stores',
                          trailing: const Icon(
                            Icons.chevron_right,
                            color: AppColors.textMuted,
                          ),
                          onTap: () => Navigator.of(context).push(
                            MaterialPageRoute(
                              builder: (_) => const _PrivacyDetailsScreen(),
                            ),
                          ),
                        ),
                        const _SettingsDivider(),
                        _SettingsRow(
                          icon: Icons.delete_outline_rounded,
                          title: _clearingData
                              ? 'Deleting...'
                              : 'Delete local reports',
                          subtitle: 'Remove focus history only',
                          trailing: _clearingData
                              ? const SizedBox(
                                  width: 20,
                                  height: 20,
                                  child: CircularProgressIndicator(
                                    strokeWidth: 2,
                                  ),
                                )
                              : const Icon(
                                  Icons.chevron_right,
                                  color: AppColors.textMuted,
                                ),
                          onTap: _clearingData ? null : _clearAnalytics,
                        ),
                      ],
                    ),
                    const SizedBox(height: 18),
                    Text('Diagnostics',
                        style: Theme.of(context).textTheme.titleMedium),
                    const SizedBox(height: 8),
                    Column(
                      children: [
                        _SettingsRow(
                          icon: Icons.description_outlined,
                          title: _exportingLog
                              ? 'Saving...'
                              : 'Save diagnostic log',
                          subtitle: 'Redacted protection events only',
                          trailing: _exportingLog
                              ? const SizedBox(
                                  width: 20,
                                  height: 20,
                                  child: CircularProgressIndicator(
                                    strokeWidth: 2,
                                  ),
                                )
                              : const Icon(
                                  Icons.file_download_outlined,
                                  color: AppColors.textSecondary,
                                ),
                          onTap: _exportingLog ? null : _exportDebugLog,
                        ),
                        const _SettingsDivider(),
                        _SettingsRow(
                          icon: Icons.cleaning_services_outlined,
                          title: 'Clear diagnostic log',
                          subtitle: 'Delete saved diagnostic events',
                          trailing: const Icon(
                            Icons.delete_outline_rounded,
                            color: AppColors.textMuted,
                          ),
                          onTap: () async {
                            await _native.clearDebugLog();
                            _showSnack('Diagnostic log cleared.');
                          },
                        ),
                      ],
                    ),
                  ],
                ),
              ),
            ],
          ),
        ),
      ),
    );
  }
}

class _PdfReaderSettingsRow extends StatelessWidget {
  const _PdfReaderSettingsRow({
    required this.reader,
    required this.hasSavedReader,
    required this.loading,
    required this.onTap,
  });

  final InstalledAppInfo? reader;
  final bool hasSavedReader;
  final bool loading;
  final VoidCallback? onTap;

  @override
  Widget build(BuildContext context) {
    final app = reader;
    final appName = app?.appName.trim();
    final subtitle = loading
        ? 'Loading installed readers...'
        : appName != null && appName.isNotEmpty
            ? appName
            : hasSavedReader
                ? 'Selected PDF reader'
                : 'No default PDF reader';

    return Material(
      color: Colors.transparent,
      child: InkWell(
        onTap: onTap,
        child: Padding(
          padding: const EdgeInsets.symmetric(vertical: 8),
          child: Row(
            children: [
              if (app != null)
                AppIconTile(
                  appName:
                      appName?.isNotEmpty == true ? appName! : 'PDF reader',
                  packageName: app.packageName,
                  size: 32,
                  radius: AppRadius.sm,
                )
              else
                const SizedBox(
                  width: 32,
                  child: Icon(
                    Icons.picture_as_pdf_outlined,
                    color: AppColors.textSecondary,
                    size: 21,
                  ),
                ),
              const SizedBox(width: 10),
              Expanded(
                child: Column(
                  crossAxisAlignment: CrossAxisAlignment.start,
                  children: [
                    Text(
                      'PDF reader',
                      style: Theme.of(context).textTheme.titleSmall,
                    ),
                    const SizedBox(height: 2),
                    Text(
                      subtitle,
                      maxLines: 1,
                      overflow: TextOverflow.ellipsis,
                      style: Theme.of(context).textTheme.bodySmall,
                    ),
                  ],
                ),
              ),
              const SizedBox(width: 10),
              if (loading)
                const SizedBox(
                  width: 18,
                  height: 18,
                  child: CircularProgressIndicator(
                    color: AppColors.textPrimary,
                    strokeWidth: 2,
                  ),
                )
              else
                const Icon(
                  Icons.chevron_right_rounded,
                  color: AppColors.textMuted,
                  size: 20,
                ),
            ],
          ),
        ),
      ),
    );
  }
}

class _PrivacyDetailsScreen extends StatelessWidget {
  const _PrivacyDetailsScreen();

  @override
  Widget build(BuildContext context) {
    return Scaffold(
      backgroundColor: Colors.black,
      body: ColoredBox(
        color: Colors.black,
        child: SafeArea(
          child: Column(
            children: [
              Row(
                children: [
                  IconButton(
                    onPressed: () => Navigator.of(context).maybePop(),
                    icon: const Icon(Icons.arrow_back_rounded),
                  ),
                  Text(
                    'Privacy details',
                    style: Theme.of(context).textTheme.titleLarge,
                  ),
                ],
              ),
              Expanded(
                child: ListView(
                  padding: const EdgeInsets.fromLTRB(16, 8, 16, 28),
                  children: const [
                    _PrivacySection(
                      title: 'Compatible protection',
                      body:
                          'During user-configured Focus or Daily App Limits, Usage Access '
                          'provides foreground app package changes. Display '
                          'Over Apps shows the block screen and floating timer. '
                          'TrustIssue does not read screen content, passwords, '
                          'messages or notifications.',
                    ),
                    SizedBox(height: 12),
                    _PrivacySection(
                      title: 'Advanced Accessibility',
                      body:
                          'When optionally enabled by you for faster detection, '
                          'the service observes only foreground package '
                          'changes for Focus. When YouTube Shorts or Instagram '
                          'Reels Blocking is also separately enabled, visible '
                          'accessibility labels and view identifiers from that app '
                          'are inspected in memory only to identify short-form '
                          'content and a swipe to the next item. '
                          'Those labels are not stored or sent. Screenshots, '
                          'notifications, messages, passwords and form values '
                          'are not retrieved. When a '
                          'blocked app is opened, the service sends Home and '
                          'a block screen and a short audio-focus fallback. '
                          'During App Study it also shows a local floating '
                          'timer and icon controls over the chosen study app. '
                          'Package transitions are used to verify contextual '
                          'system helpers and offer short, session-only study '
                          'tool handoffs. Outside the separately enabled Shorts '
                          'and Reels blockers, document and screen content is not '
                          'inspected.',
                    ),
                    SizedBox(height: 12),
                    _PrivacySection(
                      title: 'Focus and Global Web Protection',
                      body:
                          'For Focus-only protection, allowed browsers join an '
                          'Android local VPN and protection stops when Focus '
                          'ends. In Global mode, all installed browsers join and '
                          'the VPN stays active outside Focus until you switch '
                          'Global protection off. DNS requests use encrypted DNS '
                          'and website hostnames are checked against local rules '
                          "and Cloudflare's family resolver, with CleanBrowsing "
                          'Family used only if the primary resolver fails. Normal '
                          'web traffic stays in the browser; page content, '
                          'passwords, search text and browser history are not read '
                          'or stored.',
                    ),
                    SizedBox(height: 12),
                    _PrivacySection(
                      title: 'Local data',
                      body:
                          'Selected rules, app limits, session timing, blocked-attempt '
                          'counts, breaks and daily summaries are stored in '
                          'private app storage on this phone.',
                    ),
                    SizedBox(height: 12),
                    _PrivacySection(
                      title: 'No focus-activity sharing',
                      body: 'The app has no TrustIssue account, parent link, '
                          'monitoring backend, cloud sync or advertising '
                          'profile. It does not upload focus activity. When Web '
                          'Protection is active, DNS hostnames are sent only to '
                          "Cloudflare's family resolver or the failure-only "
                          'CleanBrowsing fallback described above.',
                    ),
                    SizedBox(height: 12),
                    _PrivacySection(
                      title: 'Your controls',
                      body: 'You can revoke Usage Access, Display Over Apps, '
                          'Accessibility or VPN consent in Android Settings, '
                          'delete local reports in this app, clear diagnostics '
                          'or uninstall the app.',
                    ),
                  ],
                ),
              ),
            ],
          ),
        ),
      ),
    );
  }
}

class _PrivacySection extends StatelessWidget {
  const _PrivacySection({required this.title, required this.body});

  final String title;
  final String body;

  @override
  Widget build(BuildContext context) {
    return SolidCard(
      padding: const EdgeInsets.all(14),
      child: Column(
        crossAxisAlignment: CrossAxisAlignment.start,
        children: [
          Text(title, style: Theme.of(context).textTheme.titleMedium),
          const SizedBox(height: 8),
          Text(body, style: Theme.of(context).textTheme.bodySmall),
        ],
      ),
    );
  }
}

class _SettingsNotice extends StatelessWidget {
  const _SettingsNotice({
    required this.icon,
    required this.title,
    required this.body,
  });

  final IconData icon;
  final String title;
  final String body;

  @override
  Widget build(BuildContext context) {
    return Padding(
      padding: const EdgeInsets.symmetric(vertical: 6),
      child: Row(
        crossAxisAlignment: CrossAxisAlignment.start,
        children: [
          SizedBox(
            width: 32,
            child: Icon(
              icon,
              color: AppColors.textSecondary,
              size: 20,
            ),
          ),
          const SizedBox(width: 10),
          Expanded(
            child: Column(
              crossAxisAlignment: CrossAxisAlignment.start,
              children: [
                Text(
                  title,
                  style: Theme.of(context).textTheme.titleSmall,
                ),
                const SizedBox(height: 3),
                Text(
                  body,
                  style: Theme.of(context).textTheme.bodySmall,
                ),
              ],
            ),
          ),
        ],
      ),
    );
  }
}

class _SettingsRow extends StatelessWidget {
  const _SettingsRow({
    required this.icon,
    required this.title,
    required this.subtitle,
    required this.trailing,
    required this.onTap,
  });

  final IconData icon;
  final String title;
  final String subtitle;
  final Widget trailing;
  final VoidCallback? onTap;

  @override
  Widget build(BuildContext context) {
    return Material(
      color: Colors.transparent,
      child: InkWell(
        onTap: onTap,
        child: Padding(
          padding: const EdgeInsets.symmetric(vertical: 8),
          child: Row(
            children: [
              SizedBox(
                width: 32,
                child: Icon(
                  icon,
                  color: AppColors.textSecondary,
                  size: 20,
                ),
              ),
              const SizedBox(width: 10),
              Expanded(
                child: Column(
                  crossAxisAlignment: CrossAxisAlignment.start,
                  children: [
                    Text(
                      title,
                      maxLines: 1,
                      overflow: TextOverflow.ellipsis,
                      style: Theme.of(context).textTheme.titleSmall,
                    ),
                    const SizedBox(height: 2),
                    Text(
                      subtitle,
                      maxLines: 1,
                      overflow: TextOverflow.ellipsis,
                      style: Theme.of(context).textTheme.bodySmall,
                    ),
                  ],
                ),
              ),
              const SizedBox(width: 10),
              trailing,
            ],
          ),
        ),
      ),
    );
  }
}

class _SettingsDivider extends StatelessWidget {
  const _SettingsDivider();

  @override
  Widget build(BuildContext context) {
    return const Divider(
      height: 1,
      thickness: 1,
      indent: 42,
      color: AppColors.surfaceDivider,
    );
  }
}

Widget _statusIcon(bool ready) {
  return Icon(
    ready ? Icons.check_circle_outline_rounded : Icons.chevron_right_rounded,
    color: ready ? AppColors.textPrimary : AppColors.textMuted,
    size: 20,
  );
}

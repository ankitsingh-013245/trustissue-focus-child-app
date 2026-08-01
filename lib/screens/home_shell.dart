import 'dart:async';

import 'package:flutter/material.dart';

import '../services/native_bridge.dart';
import '../services/settings_store.dart';
import '../theme/app_theme.dart';
import '../widgets/aurora_background.dart';
import '../widgets/panda_loading_screen.dart';
import '../widgets/pdf_reader_picker.dart';
import 'analytics_screen.dart';
import 'blocks_screen.dart';
import 'focus_home_screen.dart';
import 'select_apps_screen.dart';
import 'settings_screen.dart';
import 'strict_preparation_screen.dart';

class HomeShell extends StatefulWidget {
  const HomeShell({super.key});

  @override
  State<HomeShell> createState() => _HomeShellState();
}

class _HomeShellState extends State<HomeShell> with WidgetsBindingObserver {
  final _native = NativeBridge();
  final _settings = SettingsStore();
  Future<void> _settingsWrite = Future<void>.value();

  Timer? _activeTicker;
  bool _refreshingSessionClock = false;
  late final ValueNotifier<FocusClockState> _liveClock;
  final Stopwatch _clockElapsed = Stopwatch();
  int _clockTicksSinceReconcile = 0;
  bool _clockExpiryRefreshPending = false;
  int _tab = 0;
  bool _loading = true;
  bool _busy = false;
  bool _appsLoading = false;
  bool _analyticsLoading = false;
  bool _usageAccess = false;
  bool _overlayAccess = false;
  bool _accessibilityAccess = false;
  String? _pendingPermissionFlow;
  String _message = '';

  List<InstalledAppInfo> _apps = const [];
  List<InstalledAppInfo> _pdfReaders = const [];
  List<String> _alwaysAllowedPackages = const [];
  List<String> _browserPackages = const [];
  List<SelfControlDayMetrics> _analytics = const [];
  UsageSummary _usageSummary = const UsageSummary(
    totalMs: 0,
    byPackage: <String, int>{},
  );

  StudySettings _study = const StudySettings(
    enabled: false,
    policy: StudySettings.allowlist,
    trackingMode: StudySettings.bookStudy,
    durationMinutes: 25,
    allowedPackages: <String>{},
    blockedPackages: <String>{},
    strictModeEnabled: false,
    lockedStrictModeEnabled: false,
    customBlockedDomains: <String>{},
  );
  FocusBreakState _breakState = const FocusBreakState(
    active: false,
    remainingMs: 0,
  );
  FocusSessionState _sessionState = const FocusSessionState(
    active: false,
    targetMs: 0,
    remainingMs: 0,
    focusedMs: 0,
    breaksLeft: 0,
  );
  WebProtectionState _webProtectionState = const WebProtectionState(
    required: false,
    state: 'not_required',
    permissionGranted: false,
    externalVpnActive: false,
    externalVpnCompatibility: false,
    globalEnabled: false,
    message: '',
  );
  bool get _sessionActive => _study.enabled || _sessionState.active;
  bool get _focusLocked => _sessionActive || _breakState.active;
  bool get _webProtectionRequired {
    final browsers = _browserPackages.toSet();
    if (browsers.isEmpty) return false;
    if (_study.policy == StudySettings.blocklist) {
      return browsers.any(
        (package) => !_study.blockedPackages.contains(package),
      );
    }
    return browsers.any(_study.allowedPackages.contains);
  }

  int get _todayFocusMs {
    if (_analytics.isEmpty) return 0;
    final days = _analytics.toList()..sort((a, b) => a.date.compareTo(b.date));
    return days.last.studyDurationMs;
  }

  @override
  void initState() {
    super.initState();
    _liveClock = ValueNotifier<FocusClockState>(
      FocusClockState(
        breakState: _breakState,
        sessionState: _sessionState,
      ),
    );
    WidgetsBinding.instance.addObserver(this);
    _load();
  }

  @override
  void dispose() {
    _activeTicker?.cancel();
    _clockElapsed.stop();
    _liveClock.dispose();
    WidgetsBinding.instance.removeObserver(this);
    super.dispose();
  }

  @override
  void didChangeAppLifecycleState(AppLifecycleState state) {
    if (state == AppLifecycleState.resumed) {
      unawaited(_resumeFromSystemSettings());
    } else if (state == AppLifecycleState.paused) {
      _activeTicker?.cancel();
      _activeTicker = null;
    }
  }

  Future<void> _resumeFromSystemSettings() async {
    await _refreshLive();
    final pending = _pendingPermissionFlow;
    if (pending == null || !mounted) return;
    _pendingPermissionFlow = null;
    final parts = pending.split(':');
    final purpose = parts.first;
    final permission = parts.length > 1 ? parts[1] : '';
    final granted = switch (permission) {
      'usage' => _usageAccess,
      'overlay' => _overlayAccess,
      'dnd' => await _native.hasDndPolicyAccess(),
      'accessibility' => await _native.hasAccessibilityAccess(),
      _ => false,
    };
    if (!mounted) return;
    if (granted) {
      if (purpose == 'shorts') {
        await _setYoutubeShortsBlocking(true);
      } else if (purpose == 'reels') {
        await _setInstagramReelsBlocking(true);
      } else if (purpose == 'limits') {
        await _ensureAppLimitProtectionAccess();
      } else {
        await _startFocus();
      }
    } else {
      _setMessage(
        purpose == 'limits'
            ? 'That access was not enabled. The limit is saved but protection is paused.'
            : purpose == 'shorts'
                ? 'Accessibility was not enabled. YouTube Shorts Blocking remains off.'
                : purpose == 'reels'
                    ? 'Accessibility was not enabled. Instagram Reels Blocking remains off.'
                    : 'That access was not enabled. Focus was not started.',
      );
    }
  }

  Future<void> _load() async {
    try {
      await _settings.migrateToLocalOnly();
      await _native.purgeLegacyPrivateData();
      final results = await Future.wait<dynamic>([
        _native.hasUsageAccess(),
        _native.hasOverlayAccess(),
        _native.loadFocusBreakState(),
        _native.loadFocusSessionState(),
        _settings.loadStudySettings(),
        _native.getBrowserPackages(),
        _native.loadWebProtectionState(),
        _native.hasAccessibilityAccess(),
      ]);
      if (!mounted) return;
      setState(() {
        _usageAccess = results[0] as bool;
        _overlayAccess = results[1] as bool;
        _breakState = results[2] as FocusBreakState;
        _sessionState = results[3] as FocusSessionState;
        _study = results[4] as StudySettings;
        _browserPackages = results[5] as List<String>;
        _webProtectionState = results[6] as WebProtectionState;
        _accessibilityAccess = results[7] as bool;
        _loading = false;
      });
      _resetClockAnchor();
      _syncActiveTicker();
      unawaited(_loadPdfReaders());
      unawaited(_loadInstalledApps());
      unawaited(_loadUsage());
      WidgetsBinding.instance.addPostFrameCallback((_) {
        if (mounted) unawaited(_loadAnalytics());
      });
    } catch (error) {
      if (!mounted) return;
      setState(() {
        _message = 'Could not load focus state. Please reopen the app.';
        _loading = false;
      });
      _showMessage();
    }
  }

  Future<void> _refreshLive({bool whileBusy = false}) async {
    if (!mounted || _loading || (_busy && !whileBusy)) return;
    try {
      final results = await Future.wait<dynamic>([
        _native.hasUsageAccess(),
        _native.hasOverlayAccess(),
        _native.loadFocusBreakState(),
        _native.loadFocusSessionState(),
        _settings.loadStudySettings(),
        _native.getBrowserPackages(),
        _native.loadWebProtectionState(),
        _native.hasAccessibilityAccess(),
      ]);
      if (!mounted) return;
      setState(() {
        _usageAccess = results[0] as bool;
        _overlayAccess = results[1] as bool;
        _breakState = results[2] as FocusBreakState;
        _sessionState = results[3] as FocusSessionState;
        _study = results[4] as StudySettings;
        _browserPackages = results[5] as List<String>;
        _webProtectionState = results[6] as WebProtectionState;
        _accessibilityAccess = results[7] as bool;
      });
      _resetClockAnchor();
      _syncActiveTicker();
      unawaited(_loadUsage());
    } catch (_) {
      // A lifecycle refresh is best effort; the current cached state stays usable.
    }
  }

  Future<void> _reconcileSessionClock() async {
    if (!mounted || (!_sessionState.active && !_breakState.active)) {
      _syncActiveTicker();
      return;
    }
    if (_refreshingSessionClock) return;
    _refreshingSessionClock = true;
    try {
      final wasSessionActive = _sessionState.active;
      final results = await Future.wait<dynamic>([
        _native.loadFocusBreakState(),
        _native.loadFocusSessionState(),
      ]);
      if (!mounted) return;
      setState(() {
        _breakState = results[0] as FocusBreakState;
        _sessionState = results[1] as FocusSessionState;
      });
      _resetClockAnchor();
      _syncActiveTicker();
      if (wasSessionActive && !_sessionState.active) {
        await _refreshLive();
        unawaited(_loadAnalytics());
      }
    } catch (_) {
      // The next reconciliation retries the authoritative native state.
      _clockExpiryRefreshPending = false;
    } finally {
      _refreshingSessionClock = false;
    }
  }

  void _resetClockAnchor() {
    _clockElapsed
      ..stop()
      ..reset();
    if (_breakState.active ||
        (_sessionState.active && _sessionState.activelyCounting)) {
      _clockElapsed.start();
    }
    _clockTicksSinceReconcile = 0;
    _clockExpiryRefreshPending = false;
    _liveClock.value = FocusClockState(
      breakState: _breakState,
      sessionState: _sessionState,
    );
  }

  FocusClockState _deriveLiveClock() {
    final elapsedMs = _clockElapsed.elapsedMilliseconds;
    final breakRemaining =
        (_breakState.remainingMs - elapsedMs).clamp(0, 1 << 62).toInt();
    var sessionRemaining = _sessionState.remainingMs;
    var sessionFocused = _sessionState.focusedMs;
    if (_sessionState.active && _sessionState.activelyCounting) {
      sessionRemaining =
          (_sessionState.remainingMs - elapsedMs).clamp(0, 1 << 62).toInt();
      sessionFocused = (_sessionState.focusedMs + elapsedMs)
          .clamp(0, _sessionState.targetMs)
          .toInt();
    }
    return FocusClockState(
      breakState: FocusBreakState(
        active: _breakState.active,
        remainingMs: breakRemaining,
      ),
      sessionState: FocusSessionState(
        active: _sessionState.active,
        targetMs: _sessionState.targetMs,
        remainingMs: sessionRemaining,
        focusedMs: sessionFocused,
        breaksLeft: _sessionState.breaksLeft,
        sessionType: _sessionState.sessionType,
        trackingMode: _sessionState.trackingMode,
        activelyCounting: _sessionState.activelyCounting,
      ),
    );
  }

  void _tickLiveClock() {
    if (!mounted || _tab != 0) return;
    final live = _deriveLiveClock();
    _liveClock.value = live;
    _clockTicksSinceReconcile += 1;
    final expired = (_breakState.active && live.breakState.remainingMs <= 0) ||
        (_sessionState.active &&
            _sessionState.activelyCounting &&
            live.sessionState.remainingMs <= 0);
    if (expired && !_clockExpiryRefreshPending) {
      _clockExpiryRefreshPending = true;
      unawaited(_reconcileSessionClock());
    } else if (_clockTicksSinceReconcile >= 30) {
      unawaited(_reconcileSessionClock());
    }
  }

  void _syncActiveTicker() {
    final shouldTick = _tab == 0 &&
        (_breakState.active ||
            (_sessionState.active && _sessionState.activelyCounting));
    if (shouldTick && _activeTicker == null) {
      _activeTicker = Timer.periodic(
        const Duration(seconds: 1),
        (_) => _tickLiveClock(),
      );
    } else if (!shouldTick && _activeTicker != null) {
      _activeTicker?.cancel();
      _activeTicker = null;
    }
  }

  Future<void> _saveStudy(StudySettings settings) async {
    if (mounted) setState(() => _study = settings);
    _settingsWrite = _settingsWrite.then(
      (_) => _settings.saveStudySettings(settings),
      onError: (_) => _settings.saveStudySettings(settings),
    );
    await _settingsWrite;
  }

  Future<void> _setPolicy(String policy) async {
    if (_focusLocked) return;
    if (_study.isAppStudy && policy != StudySettings.allowlist) return;
    await _saveStudy(_study.copyWith(policy: policy));
  }

  Future<void> _setTrackingMode(String trackingMode) async {
    if (_focusLocked) return;
    final appStudy = trackingMode == StudySettings.appStudy;
    await _saveStudy(
      _study.copyWith(
        trackingMode:
            appStudy ? StudySettings.appStudy : StudySettings.bookStudy,
        policy: appStudy ? StudySettings.allowlist : _study.policy,
      ),
    );
  }

  Future<void> _setDuration(int minutes) async {
    if (_focusLocked) return;
    final maximum = _study.maximumDurationMinutes;
    await _saveStudy(
      _study.copyWith(durationMinutes: minutes.clamp(1, maximum).toInt()),
    );
  }

  Future<void> _setSessionType(String sessionType) async {
    if (_focusLocked) return;
    final stopwatch = sessionType == StudySettings.stopwatch;
    await _saveStudy(
      _study.copyWith(
        sessionType: stopwatch ? StudySettings.stopwatch : StudySettings.timer,
        lockedStrictModeEnabled:
            stopwatch ? false : _study.lockedStrictModeEnabled,
      ),
    );
  }

  Future<void> _setProtectionLevel(String level) async {
    if (_focusLocked) return;
    final locked = level == 'locked' && !_study.isStopwatch;
    final strict = level == 'strict' || locked;
    final maximum = locked
        ? StudySettings.maxLockedStrictMinutes
        : strict
            ? StudySettings.maxStrictMinutes
            : StudySettings.maxNormalMinutes;
    await _saveStudy(
      _study.copyWith(
        strictModeEnabled: strict,
        lockedStrictModeEnabled: locked,
        durationMinutes: _study.durationMinutes.clamp(1, maximum).toInt(),
      ),
    );
  }

  Future<void> _loadInstalledApps() async {
    if (_apps.isNotEmpty || _appsLoading) return;
    _appsLoading = true;
    try {
      final results = await Future.wait<dynamic>([
        _native.getInstalledApps(),
        _native.getAlwaysAllowedPackages(),
      ]);
      if (!mounted) return;
      setState(() {
        _apps = results[0] as List<InstalledAppInfo>;
        _alwaysAllowedPackages = results[1] as List<String>;
      });
    } catch (_) {
      _setMessage('Could not load installed apps. Please try again.');
    } finally {
      _appsLoading = false;
    }
  }

  Future<bool> _loadPdfReaders({bool force = false}) async {
    if (_pdfReaders.isNotEmpty && !force) return true;
    try {
      final readers = await _native.getPdfReaderApps();
      if (mounted) setState(() => _pdfReaders = readers);
      return true;
    } catch (_) {
      if (force) {
        _setMessage('Could not verify PDF readers. Please try again.');
      }
      return false;
    }
  }

  Future<bool> _chooseDefaultPdfReader({
    bool requiredBeforeStart = false,
  }) async {
    if (_focusLocked) {
      _setMessage('End the active session before changing the PDF reader.');
      return false;
    }
    final readersLoaded = await _loadPdfReaders(force: true);
    if (!readersLoaded) return false;
    if (!mounted) return false;
    final installedPackages = _pdfReaders.map((app) => app.packageName).toSet();
    final currentIsValid = _study.defaultPdfReaderPackage.isEmpty ||
        installedPackages.contains(_study.defaultPdfReaderPackage);
    if (requiredBeforeStart &&
        _study.pdfReaderSetupComplete &&
        currentIsValid) {
      return true;
    }

    final selected = await showPdfReaderPicker(
      context: context,
      readers: _pdfReaders,
      currentPackage: _study.defaultPdfReaderPackage,
      dismissible: !requiredBeforeStart,
    );
    if (selected == null) return false;
    await _saveStudy(
      _study.copyWith(
        defaultPdfReaderPackage: selected == noPdfReaderChoice ? '' : selected,
        pdfReaderSetupComplete: true,
      ),
    );
    return true;
  }

  Future<int?> _openAppPicker() async {
    if (_focusLocked) {
      _setMessage('End the active session before editing focus apps.');
      return null;
    }
    await _loadInstalledApps();
    if (!mounted) return null;
    if (_apps.isEmpty) {
      _setMessage('No launchable apps were found.');
      return null;
    }

    final allowMode = _study.policy == StudySettings.allowlist;
    final current = allowMode ? _study.allowedPackages : _study.blockedPackages;
    final selected = await Navigator.of(context).push<AppSelectionResult>(
      MaterialPageRoute(
        builder: (_) => SelectAppsScreen(
          apps: _apps,
          selected: current,
          allowMode: allowMode,
          alwaysAllowedPackages: _alwaysAllowedPackages,
          browserPackages: _browserPackages,
          customBlockedDomains: _study.customBlockedDomains,
          onCustomDomainsChanged: (domains) => _persistWebDomains(
            focusDomains: domains,
            successMessage: 'Focus domains saved.',
          ),
        ),
      ),
    );
    if (selected == null) return null;
    await _saveStudy(
      allowMode
          ? _study.copyWith(
              allowedPackages: selected.selectedPackages,
              customBlockedDomains: selected.customBlockedDomains,
            )
          : _study.copyWith(
              blockedPackages: selected.selectedPackages,
              customBlockedDomains: selected.customBlockedDomains,
            ),
    );
    return selected.selectedPackages.length;
  }

  Future<bool> _showCompatibleUsageDisclosure() async {
    return await showDialog<bool>(
          context: context,
          barrierDismissible: false,
          builder: (context) => AlertDialog(
            backgroundColor: AppColors.card,
            surfaceTintColor: Colors.transparent,
            icon: const Icon(
              Icons.query_stats_rounded,
              color: AppColors.emerald,
            ),
            title: const Text('Allow Usage Access'),
            content: const Text(
              'TrustIssue uses Android Usage Access for Focus and Daily App Limits to '
              'notice which app is in front. It reads app package names and change '
              'times only - not screen text, passwords, messages, or notifications.',
            ),
            actions: [
              TextButton(
                onPressed: () => Navigator.of(context).pop(false),
                child: const Text('Not now'),
              ),
              FilledButton(
                onPressed: () => Navigator.of(context).pop(true),
                child: const Text('Continue to Settings'),
              ),
            ],
          ),
        ) ??
        false;
  }

  Future<bool> _showOverlayDisclosure() async {
    return await showDialog<bool>(
          context: context,
          barrierDismissible: false,
          builder: (context) => AlertDialog(
            backgroundColor: AppColors.card,
            surfaceTintColor: Colors.transparent,
            icon: const Icon(
              Icons.picture_in_picture_alt_rounded,
              color: AppColors.lime,
            ),
            title: const Text('Allow Display Over Apps'),
            content: const Text(
              'This lets TrustIssue place the Focus Block screen and floating '
              'timer above a selected app. It is used only while Focus or a '
              'Daily App Limit is active. Accessibility is not required.',
            ),
            actions: [
              TextButton(
                onPressed: () => Navigator.of(context).pop(false),
                child: const Text('Not now'),
              ),
              FilledButton(
                onPressed: () => Navigator.of(context).pop(true),
                child: const Text('Continue to Settings'),
              ),
            ],
          ),
        ) ??
        false;
  }

  Future<bool> _showYoutubeShortsDisclosure() async {
    return await showDialog<bool>(
          context: context,
          barrierDismissible: false,
          builder: (context) => AlertDialog(
            backgroundColor: AppColors.card,
            surfaceTintColor: Colors.transparent,
            icon: const Icon(
              Icons.smart_display_rounded,
              color: AppColors.lime,
            ),
            title: const Text('Allow YouTube Shorts Blocking'),
            content: const Text(
              'When this feature is on and YouTube is visible, TrustIssue uses '
              'Android Accessibility to read YouTube accessibility labels and '
              'view identifiers in memory. This is used only to identify the '
              'Shorts player and notice a swipe to the next Short.\n\n'
              'TrustIssue does not capture screenshots, type or click for you, '
              'read passwords, messages or notifications, or store or send '
              'YouTube labels, titles or creator names.\n\n'
              'On the block screen, “Watch only this” allows the current Short. '
              'Swiping to another Short brings the block screen back.',
            ),
            actions: [
              TextButton(
                onPressed: () => Navigator.of(context).pop(false),
                child: const Text('Not now'),
              ),
              FilledButton(
                onPressed: () => Navigator.of(context).pop(true),
                child: const Text('I agree & open Settings'),
              ),
            ],
          ),
        ) ??
        false;
  }

  Future<bool> _showInstagramReelsDisclosure() async {
    return await showDialog<bool>(
          context: context,
          barrierDismissible: false,
          builder: (context) => AlertDialog(
            backgroundColor: AppColors.card,
            surfaceTintColor: Colors.transparent,
            icon: const Icon(
              Icons.video_collection_rounded,
              color: AppColors.lime,
            ),
            title: const Text('Allow Instagram Reels Blocking'),
            content: const Text(
              'When this feature is on and Instagram is visible, TrustIssue '
              'uses Android Accessibility to read Instagram accessibility '
              'labels and view identifiers in memory. This is used only to '
              'identify the Reels viewer and notice a swipe to the next Reel.\n\n'
              'TrustIssue does not capture screenshots, type or click for you, '
              'read passwords, messages or notifications, or store or send '
              'Instagram labels, captions or account names.\n\n'
              'On the block screen, “Watch only this” allows the current Reel. '
              'Swiping to another Reel brings the block screen back.',
            ),
            actions: [
              TextButton(
                onPressed: () => Navigator.of(context).pop(false),
                child: const Text('Not now'),
              ),
              FilledButton(
                onPressed: () => Navigator.of(context).pop(true),
                child: const Text('I agree & open Settings'),
              ),
            ],
          ),
        ) ??
        false;
  }

  Future<bool> _showWebProtectionDisclosure({
    required bool globalProtection,
  }) async {
    final modeDetails = globalProtection
        ? 'In Global mode, all installed browsers join the local VPN. It stays '
            'active outside Focus until you switch Global Website Protection '
            'off. During Focus, the active Focus rules are added.'
        : 'During Focus, browsers allowed by the active Focus rules join the '
            'local VPN. When Focus ends, this Focus-only protection stops. If '
            'Global Website Protection is also enabled, the VPN remains active '
            'until you switch Global protection off.';
    return await showDialog<bool>(
          context: context,
          barrierDismissible: false,
          builder: (context) => AlertDialog(
            backgroundColor: AppColors.card,
            surfaceTintColor: Colors.transparent,
            icon: const Icon(
              Icons.language_rounded,
              color: AppColors.lime,
            ),
            title: Text(
              globalProtection
                  ? 'Allow Global Website Protection'
                  : 'Allow Focus Website Protection',
            ),
            content: Text(
              'TrustIssue creates a local Android VPN for browser protection. '
              '$modeDetails Website hostnames are checked against adult, '
              'blocked-app, bypass and custom rules.\n\n'
              'Browser DNS requests use Cloudflare Family over encrypted DNS, '
              'with CleanBrowsing Family as a failure-only fallback. '
              'TrustIssue does not decrypt web traffic or read page content, '
              'passwords, search text or browser history.',
            ),
            actions: [
              TextButton(
                onPressed: () => Navigator.of(context).pop(false),
                child: const Text('Not now'),
              ),
              FilledButton(
                onPressed: () => Navigator.of(context).pop(true),
                child: const Text('Continue'),
              ),
            ],
          ),
        ) ??
        false;
  }

  Future<String?> _showVpnConflictChoice({bool globalProtection = false}) {
    return showDialog<String>(
      context: context,
      barrierDismissible: false,
      builder: (dialogContext) => AlertDialog(
        backgroundColor: AppColors.card,
        surfaceTintColor: Colors.transparent,
        icon: const Icon(
          Icons.vpn_key_rounded,
          color: AppColors.emerald,
        ),
        title: const Text('Another VPN is active'),
        content: Text(
          globalProtection
              ? 'Android allows one VPN at a time. Global Website Protection '
                  'can be enabled only after switching from the current VPN to '
                  'TrustIssue.\n\nYour current VPN will never be disconnected '
                  'silently.'
              : 'Android allows one VPN at a time. You can keep your current '
                  'VPN and continue Focus without TrustIssue website filtering, '
                  'or switch to TrustIssue Web Protection for this session.\n\n'
                  'Your current VPN will never be disconnected silently.',
        ),
        actions: [
          TextButton(
            onPressed: () => Navigator.of(dialogContext).pop(),
            child: const Text('Cancel'),
          ),
          TextButton(
            onPressed: () => Navigator.of(dialogContext).pop('keep_current'),
            child: const Text('Keep Current VPN'),
          ),
          FilledButton(
            onPressed: () => Navigator.of(dialogContext).pop('use_trustissue'),
            child: const Text('Use TrustIssue'),
          ),
        ],
      ),
    );
  }

  Future<bool> _showDndDisclosure() async {
    return await showDialog<bool>(
          context: context,
          barrierDismissible: false,
          builder: (context) => AlertDialog(
            backgroundColor: AppColors.card,
            surfaceTintColor: Colors.transparent,
            icon: const Icon(
              Icons.do_not_disturb_on_rounded,
              color: AppColors.emerald,
            ),
            title: const Text('Allow Strict Focus DND'),
            content: const Text(
              'While Strict Focus is active, TrustIssue uses Android Do Not '
              'Disturb in Alarms-only mode so calls and app notifications do '
              'not interrupt you. Call screens, banners and other notification '
              'visuals stay hidden during the session. DND is released '
              'automatically when Strict ends, and existing notifications can '
              'appear again.\n\nAndroid requires this one-time access. '
              'TrustIssue does not read or delete notifications.',
            ),
            actions: [
              TextButton(
                onPressed: () => Navigator.of(context).pop(false),
                child: const Text('Not now'),
              ),
              FilledButton(
                onPressed: () => Navigator.of(context).pop(true),
                child: const Text('Continue to Settings'),
              ),
            ],
          ),
        ) ??
        false;
  }

  Future<void> _setGlobalWebProtection(bool enabled) async {
    if (_busy) return;
    setState(() => _busy = true);
    try {
      if (enabled) {
        final disclosureAccepted =
            await _settings.isWebProtectionDisclosureAccepted();
        if (!mounted) return;
        if (!disclosureAccepted) {
          final accepted = await _showWebProtectionDisclosure(
            globalProtection: true,
          );
          if (!accepted || !mounted) return;
        }

        var prepared = await _native.prepareWebProtection(
          forceRequired: true,
        );
        if (!mounted) return;
        if (!prepared.success && prepared.code == 'vpn_conflict') {
          final choice = await _showVpnConflictChoice(
            globalProtection: true,
          );
          if (!mounted || choice != 'use_trustissue') {
            _setMessage(
              'Your current VPN was kept. Global Website Protection remains off.',
            );
            return;
          }
          prepared = await _native.prepareWebProtection(
            replaceExistingVpn: true,
            forceRequired: true,
          );
          if (!mounted) return;
        }
        if (!prepared.success) {
          _setMessage(prepared.message);
          return;
        }

        final updated = _study.copyWith(globalWebProtectionEnabled: true);
        await _saveStudy(updated);
        final started = await _native.syncWebProtection();
        if (!started) {
          await _saveStudy(
            updated.copyWith(globalWebProtectionEnabled: false),
          );
          _setMessage('Website Protection could not start.');
          return;
        }
        if (!disclosureAccepted) {
          await _settings.setWebProtectionDisclosureAccepted(true);
        }
        _setMessage('Global Website Protection is active.');
      } else {
        await _saveStudy(
          _study.copyWith(globalWebProtectionEnabled: false),
        );
        final stopped = await _native.syncWebProtection();
        _setMessage(
          stopped
              ? 'Global Website Protection is off.'
              : 'Website Protection did not fully disconnect. Open TrustIssue again before using the browser.',
        );
      }
    } finally {
      if (mounted) {
        setState(() => _busy = false);
        unawaited(_refreshLive());
      }
    }
  }

  Future<void> _setYoutubeShortsBlocking(bool enabled) async {
    if (_busy) return;
    setState(() => _busy = true);
    try {
      if (!enabled) {
        await _saveStudy(
          _study.copyWith(youtubeShortsBlockingEnabled: false),
        );
        _setMessage('YouTube Shorts Blocking is off.');
        return;
      }

      var disclosureAccepted =
          await _settings.isYoutubeShortsDisclosureAccepted();
      if (!mounted) return;
      if (!disclosureAccepted) {
        disclosureAccepted = await _showYoutubeShortsDisclosure();
        if (!disclosureAccepted || !mounted) return;
        await _settings.setYoutubeShortsDisclosureAccepted(true);
      }

      final accessibility = await _native.hasAccessibilityAccess();
      if (!mounted) return;
      setState(() => _accessibilityAccess = accessibility);
      if (!accessibility) {
        _pendingPermissionFlow = 'shorts:accessibility';
        await _native.openAccessibilitySettings();
        return;
      }

      await _saveStudy(
        _study.copyWith(youtubeShortsBlockingEnabled: true),
      );
      _setMessage(
        'YouTube Shorts Blocking is active. “Watch only this” allows one Short.',
      );
    } finally {
      if (mounted) {
        setState(() => _busy = false);
      }
    }
  }

  Future<void> _setInstagramReelsBlocking(bool enabled) async {
    if (_busy) return;
    setState(() => _busy = true);
    try {
      if (!enabled) {
        await _saveStudy(
          _study.copyWith(instagramReelsBlockingEnabled: false),
        );
        _setMessage('Instagram Reels Blocking is off.');
        return;
      }

      var disclosureAccepted =
          await _settings.isInstagramReelsDisclosureAccepted();
      if (!mounted) return;
      if (!disclosureAccepted) {
        disclosureAccepted = await _showInstagramReelsDisclosure();
        if (!disclosureAccepted || !mounted) return;
        await _settings.setInstagramReelsDisclosureAccepted(true);
      }

      final accessibility = await _native.hasAccessibilityAccess();
      if (!mounted) return;
      setState(() => _accessibilityAccess = accessibility);
      if (!accessibility) {
        _pendingPermissionFlow = 'reels:accessibility';
        await _native.openAccessibilitySettings();
        return;
      }

      await _saveStudy(
        _study.copyWith(instagramReelsBlockingEnabled: true),
      );
      _setMessage(
        'Instagram Reels Blocking is active. “Watch only this” allows one Reel.',
      );
    } finally {
      if (mounted) {
        setState(() => _busy = false);
      }
    }
  }

  Future<void> _editGlobalWebDomains() async {
    if (_sessionActive) return;
    final updatedDomains = await showWebsiteRulesSheet(
      context,
      initialDomains: _study.globalCustomBlockedDomains,
      title: 'Global blocked domains',
      description:
          'These domains are blocked whenever Global Website Protection is on, even outside Focus.',
      saveLabel: 'Save Global domains',
    );
    if (updatedDomains == null || !mounted) return;
    await _persistWebDomains(
      globalDomains: updatedDomains,
      successMessage: 'Global domains saved.',
    );
  }

  Future<void> _editFocusWebDomains() async {
    if (_sessionActive) return;
    final updatedDomains = await showWebsiteRulesSheet(
      context,
      initialDomains: _study.customBlockedDomains,
      title: 'Focus blocked domains',
      description:
          'These domains are blocked only while a Focus session is active.',
      saveLabel: 'Save Focus domains',
    );
    if (updatedDomains == null || !mounted) return;
    await _persistWebDomains(
      focusDomains: updatedDomains,
      successMessage: 'Focus domains saved.',
    );
  }

  Future<void> _persistWebDomains({
    Set<String>? globalDomains,
    Set<String>? focusDomains,
    required String successMessage,
  }) async {
    final expectedGlobal = globalDomains ?? _study.globalCustomBlockedDomains;
    final expectedFocus = focusDomains ?? _study.customBlockedDomains;
    await _saveStudy(
      _study.copyWith(
        globalCustomBlockedDomains: expectedGlobal,
        customBlockedDomains: expectedFocus,
      ),
    );

    final persisted = await _settings.loadStudySettings();
    final globalSaved = _sameDomains(
      persisted.globalCustomBlockedDomains,
      expectedGlobal,
    );
    final focusSaved = _sameDomains(
      persisted.customBlockedDomains,
      expectedFocus,
    );
    if (!globalSaved || !focusSaved) {
      if (mounted) setState(() => _study = persisted);
      _setMessage('Domain rules were not saved. Please try again.');
      return;
    }

    if (mounted) setState(() => _study = persisted);
    final synced = await _native.syncWebProtection();
    if (!synced && (_study.globalWebProtectionEnabled || _sessionActive)) {
      _setMessage('Domains saved, but Website Protection needs to reconnect.');
      return;
    }
    try {
      final liveState = await _native.loadWebProtectionState();
      if (mounted) setState(() => _webProtectionState = liveState);
      if (liveState.globalCustomCount != expectedGlobal.length ||
          liveState.focusCustomCount != expectedFocus.length) {
        _setMessage(
          'Domains were saved, but Android has not loaded the new rules yet.',
        );
        return;
      }
    } catch (_) {}
    _setMessage(successMessage);
  }

  bool _sameDomains(Set<String> first, Set<String> second) {
    return first.length == second.length && first.containsAll(second);
  }

  Future<void> _startFocus() async {
    if (_busy) return;
    setState(() => _busy = true);
    try {
      await _runStartFocus();
    } catch (_) {
      _setMessage('Focus setup could not complete. Please try again.');
    } finally {
      if (mounted) setState(() => _busy = false);
    }
  }

  Future<void> _runStartFocus() async {
    final allowMode = _study.policy == StudySettings.allowlist;
    if (_study.isAppStudy && !allowMode) {
      _setMessage('App Study uses Allowlist. Choose your study apps first.');
      return;
    }
    final selected =
        allowMode ? _study.allowedPackages : _study.blockedPackages;
    if (selected.isEmpty) {
      _setMessage(
        allowMode
            ? 'Choose at least one allowed app first.'
            : 'Choose at least one blocked app first.',
      );
      return;
    }
    if (!_study.isStopwatch && _study.durationMinutes <= 0) {
      _setMessage('Choose a focus duration first.');
      return;
    }
    if (!_study.isStopwatch &&
        _study.durationMinutes > _study.maximumDurationMinutes) {
      _setMessage(
        _study.lockedStrictModeEnabled
            ? 'Locked Strict is limited to 3 hours for safety.'
            : 'This Focus duration is longer than the selected mode allows.',
      );
      return;
    }
    if (_study.isAppStudy &&
        !await _chooseDefaultPdfReader(requiredBeforeStart: true)) {
      return;
    }

    final permissionResults = await Future.wait<bool>([
      _native.hasUsageAccess(),
      _native.hasOverlayAccess(),
    ]);
    if (!mounted) return;
    setState(() {
      _usageAccess = permissionResults[0];
      _overlayAccess = permissionResults[1];
    });
    if (!_usageAccess) {
      final accepted = await _showCompatibleUsageDisclosure();
      if (accepted) {
        _pendingPermissionFlow = 'focus:usage';
        await _native.openUsageAccessSettings();
      }
      return;
    }
    if (!_overlayAccess) {
      final accepted = await _showOverlayDisclosure();
      if (accepted) {
        _pendingPermissionFlow = 'focus:overlay';
        await _native.openOverlaySettings();
      }
      return;
    }
    // A denied notification does not weaken blocking. Android still exposes
    // the active foreground service in system task controls.
    await _native.requestNotificationPermission();
    if (!mounted) return;
    if (_study.strictModeEnabled) {
      final dndAccess = await _native.hasDndPolicyAccess();
      if (!mounted) return;
      if (!dndAccess) {
        final accepted = await _showDndDisclosure();
        if (accepted) {
          _pendingPermissionFlow = 'focus:dnd';
          await _native.openDndPolicyAccessSettings();
        }
        return;
      }
    }

    try {
      final browsers = await _native.getBrowserPackages();
      if (!mounted) return;
      setState(() => _browserPackages = browsers);
    } catch (_) {
      _setMessage(
        'Browser protection could not be verified. Focus was not started.',
      );
      return;
    }

    await _native.setExternalVpnCompatibility(false);
    if (_webProtectionRequired) {
      final disclosureAccepted =
          await _settings.isWebProtectionDisclosureAccepted();
      if (!mounted) return;
      if (!disclosureAccepted) {
        final accepted = await _showWebProtectionDisclosure(
          globalProtection: false,
        );
        if (!accepted) {
          _setMessage(
            'Focus was not started. Your app selections were not changed.',
          );
          return;
        }
      }
      var prepared = await _native.prepareWebProtection();
      if (!mounted) return;
      if (!prepared.success && prepared.code == 'vpn_conflict') {
        final choice = await _showVpnConflictChoice();
        if (!mounted || choice == null) return;
        if (choice == 'keep_current') {
          final accepted = await _native.setExternalVpnCompatibility(true);
          if (!accepted) {
            _setMessage(
              'The other VPN is no longer active. Check the VPN and try again.',
            );
            return;
          }
          prepared = const WebProtectionPrepareResult(
            success: true,
            code: 'external_vpn_compatibility',
            message: 'Focus will keep the current VPN.',
          );
        } else {
          prepared = await _native.prepareWebProtection(
            replaceExistingVpn: true,
          );
          if (!mounted) return;
        }
      }
      if (!prepared.success) {
        _setMessage(prepared.message);
        return;
      }
      if (!disclosureAccepted) {
        await _settings.setWebProtectionDisclosureAccepted(true);
      }
    }

    if (_study.strictModeEnabled) {
      if (!mounted) return;
      final prepared = await Navigator.of(context).push<bool>(
        MaterialPageRoute(
          fullscreenDialog: true,
          builder: (_) => StrictPreparationScreen(
            lockedStrict: _study.lockedStrictModeEnabled,
          ),
        ),
      );
      if (prepared != true || !mounted) return;
    }

    final enabledSettings = _study.copyWith(enabled: true);
    final configuredSettings = _study.copyWith(enabled: false);
    try {
      // Persist the complete configuration while inactive. Native code owns
      // the final activation so its timer state is ready before observers see
      // studyModeEnabled=true.
      await _settings.saveStudySettings(configuredSettings);
      final result = await _native.setFocusEnabled(true);
      if (!result.success) {
        await _settings.saveStudySettings(configuredSettings);
        if (mounted) setState(() => _study = configuredSettings);
        _setMessage(result.message);
        return;
      }
      if (mounted) setState(() => _study = enabledSettings);
    } catch (_) {
      await _settings.saveStudySettings(configuredSettings);
      if (mounted) setState(() => _study = configuredSettings);
      _setMessage('Focus could not start. Your settings were restored.');
      return;
    }
    await _refreshLive(whileBusy: true);
    await _loadAnalytics();
    _setMessage(
      _study.lockedStrictModeEnabled
          ? 'Locked Strict Focus is active for the selected duration.'
          : _study.strictModeEnabled
              ? 'Strict Focus is active.'
              : 'Focus session started.',
    );
  }

  Future<void> _stopFocus() async {
    if (_busy) return;
    if (_study.strictModeEnabled) {
      if (_study.lockedStrictModeEnabled) {
        _setMessage(
          'Locked Strict has no emergency exit. Use a timed break or wait for the session to finish.',
        );
        return;
      }
      try {
        await _native.openEmergencyExit();
      } catch (_) {
        _setMessage('Emergency Exit could not be opened. Please try again.');
      }
      return;
    }

    final stop = await showDialog<bool>(
      context: context,
      builder: (context) => AlertDialog(
        backgroundColor: AppColors.card,
        surfaceTintColor: Colors.transparent,
        title: const Text('End focus session?'),
        content: const Text(
          'Normal Focus can be ended at any time. Your completed focus time '
          'will remain in the local report.',
        ),
        actions: [
          TextButton(
            onPressed: () => Navigator.of(context).pop(false),
            child: const Text('Keep focusing'),
          ),
          FilledButton(
            onPressed: () => Navigator.of(context).pop(true),
            child: const Text('End session'),
          ),
        ],
      ),
    );
    if (stop != true) return;

    setState(() => _busy = true);
    var message = 'Focus session ended.';
    try {
      final stopped = _study.copyWith(enabled: false);
      final result = await _native.setFocusEnabled(false);
      if (result.success) {
        if (result.message.trim().isNotEmpty) {
          message = result.message;
        }
        await _settings.saveStudySettings(stopped);
        if (mounted) setState(() => _study = stopped);
      } else {
        message = result.message;
      }
    } catch (_) {
      message = 'Focus could not be stopped. Please try again.';
    } finally {
      if (mounted) setState(() => _busy = false);
    }
    await _refreshLive();
    await _loadAnalytics();
    _setMessage(message);
  }

  Future<void> _endBreak() async {
    if (_busy || !_breakState.active) return;
    setState(() => _busy = true);
    var ended = false;
    try {
      ended = await _native.endFocusBreak();
    } catch (_) {
      _setMessage('Break could not be ended. Please try again.');
      return;
    } finally {
      if (mounted) setState(() => _busy = false);
    }
    await _refreshLive();
    await _loadAnalytics();
    _setMessage(
      ended ? 'Break ended. Focus rules restored.' : 'Break already ended.',
    );
  }

  Future<void> _loadAnalytics() async {
    if (_analyticsLoading) return;
    setState(() => _analyticsLoading = true);
    try {
      final analytics = await _native.loadSelfControlAnalytics(days: 30);
      if (!mounted) return;
      setState(() => _analytics = analytics);
    } catch (_) {
      _setMessage('Could not refresh local reports.');
    } finally {
      if (mounted) setState(() => _analyticsLoading = false);
    }
  }

  Future<void> _loadUsage() async {
    try {
      final summary = await _native.loadTodayUsage();
      if (!mounted) return;
      setState(() => _usageSummary = summary);
    } catch (_) {
      // Usage Access may not be granted yet. The UI safely shows zero.
    }
  }

  Future<void> _setAppLimit(String packageName, int? minutes) async {
    if (_focusLocked) {
      _setMessage('End the active session before changing daily limits.');
      return;
    }
    final limits = <String, int>{..._study.dailyAppLimits};
    if (minutes == null || minutes <= 0) {
      limits.remove(packageName);
    } else {
      limits[packageName] = minutes.clamp(1, 24 * 60).toInt();
    }
    await _saveStudy(_study.copyWith(dailyAppLimits: limits));
    await _loadUsage();
    if (limits.isEmpty) {
      await _native.syncAppLimitProtection();
      _setMessage('Daily limit removed.');
      return;
    }
    final ready = await _ensureAppLimitProtectionAccess();
    if (ready) {
      _setMessage(minutes == null
          ? 'Daily limit removed.'
          : 'Daily limit saved and active.');
    }
  }

  Future<bool> _ensureAppLimitProtectionAccess() async {
    if (_study.dailyAppLimits.isEmpty) return true;
    final results = await Future.wait<bool>([
      _native.hasUsageAccess(),
      _native.hasOverlayAccess(),
    ]);
    if (!mounted) return false;
    setState(() {
      _usageAccess = results[0];
      _overlayAccess = results[1];
    });
    if (!_usageAccess) {
      final accepted = await _showCompatibleUsageDisclosure();
      if (accepted) {
        _pendingPermissionFlow = 'limits:usage';
        await _native.openUsageAccessSettings();
      } else {
        _setMessage(
            'Daily limit saved. Usage Access is needed to activate it.');
      }
      return false;
    }
    if (!_overlayAccess) {
      final accepted = await _showOverlayDisclosure();
      if (accepted) {
        _pendingPermissionFlow = 'limits:overlay';
        await _native.openOverlaySettings();
      } else {
        _setMessage(
            'Daily limit saved. Display Over Apps is needed to activate it.');
      }
      return false;
    }
    await _native.requestNotificationPermission();
    final synced = await _native.syncAppLimitProtection();
    if (!synced) {
      _setMessage(
          'Daily limit was saved, but Android could not start protection.');
    }
    return synced;
  }

  void _setTab(int tab) {
    setState(() => _tab = tab);
    if (tab == 0) _liveClock.value = _deriveLiveClock();
    _syncActiveTicker();
    unawaited(_refreshLive());
    if (tab == 0 || tab == 1) unawaited(_loadUsage());
    if (tab == 2) unawaited(_loadAnalytics());
  }

  void _setMessage(String message) {
    if (!mounted) return;
    setState(() => _message = message);
    _showMessage();
  }

  void _showMessage() {
    if (!mounted || _message.isEmpty) return;
    ScaffoldMessenger.of(context)
      ..clearSnackBars()
      ..showSnackBar(
        SnackBar(
          content: Text(_message),
          behavior: SnackBarBehavior.floating,
          backgroundColor: AppColors.card,
        ),
      );
  }

  String _appName(String packageName) {
    for (final app in _apps) {
      if (app.packageName == packageName && app.appName.trim().isNotEmpty) {
        return app.appName.trim();
      }
    }
    for (final app in _pdfReaders) {
      if (app.packageName == packageName && app.appName.trim().isNotEmpty) {
        return app.appName.trim();
      }
    }
    return 'Installed app';
  }

  @override
  Widget build(BuildContext context) {
    if (_loading) {
      return const Scaffold(
        body: AuroraBackground(child: PandaLoadingScreen()),
      );
    }

    return Scaffold(
      extendBody: true,
      backgroundColor: AppColors.page,
      body: IndexedStack(
        index: _tab,
        children: [
          AuroraBackground(
            child: SafeArea(
              bottom: false,
              child: FocusHomeScreen(
                study: _study,
                breakState: _breakState,
                sessionState: _sessionState,
                liveClock: _liveClock,
                todayUsageMs: _usageSummary.totalMs,
                usageReady: _usageAccess,
                todayFocusMs: _todayFocusMs,
                busy: _busy,
                nameForPackage: _appName,
                onSetPolicy: _setPolicy,
                onSetTrackingMode: _setTrackingMode,
                onSetDuration: _setDuration,
                onSetSessionType: _setSessionType,
                onSetProtectionLevel: _setProtectionLevel,
                onEditApps: _openAppPicker,
                onStartFocus: _startFocus,
                onStopFocus: _stopFocus,
                onEndBreak: _endBreak,
                onOpenBlocks: () => _setTab(1),
                onOpenSettings: () => _setTab(3),
              ),
            ),
          ),
          ColoredBox(
            color: AppColors.page,
            child: SafeArea(
              bottom: false,
              child: BlocksScreen(
                study: _study,
                webProtectionState: _webProtectionState,
                accessibilityAvailable: _accessibilityAccess,
                apps: _apps,
                alwaysAllowedPackages: _alwaysAllowedPackages.toSet(),
                usageByPackage: _usageSummary.byPackage,
                nameForPackage: _appName,
                onEditFocusApps: () => unawaited(_openAppPicker()),
                onSetAppLimit: _setAppLimit,
                onSetGlobalWebProtection: _setGlobalWebProtection,
                onSetYoutubeShortsBlocking: _setYoutubeShortsBlocking,
                onSetInstagramReelsBlocking: _setInstagramReelsBlocking,
                onEditGlobalDomains: () => unawaited(_editGlobalWebDomains()),
                onEditFocusDomains: () => unawaited(_editFocusWebDomains()),
                onRefreshUsage: _loadUsage,
              ),
            ),
          ),
          ColoredBox(
            color: AppColors.page,
            child: SafeArea(
              bottom: false,
              child: AnalyticsScreen(
                analytics: _analytics,
                loading: _analyticsLoading,
                onRefresh: _loadAnalytics,
              ),
            ),
          ),
          SettingsScreen(embedded: true, active: _tab == 3),
        ],
      ),
      bottomNavigationBar: _BottomNav(index: _tab, onChanged: _setTab),
    );
  }
}

class _BottomNav extends StatelessWidget {
  const _BottomNav({required this.index, required this.onChanged});

  final int index;
  final ValueChanged<int> onChanged;

  @override
  Widget build(BuildContext context) {
    return Container(
      padding: const EdgeInsets.fromLTRB(20, 10, 20, 22),
      decoration: const BoxDecoration(
        color: AppColors.page,
        border: Border(
          top: BorderSide(color: AppColors.surfaceBorder),
        ),
      ),
      child: Row(
        mainAxisAlignment: MainAxisAlignment.spaceAround,
        children: [
          _NavItem(
            icon: Icons.hourglass_top_rounded,
            label: 'Focus',
            selected: index == 0,
            onTap: () => onChanged(0),
          ),
          _NavItem(
            icon: Icons.block_rounded,
            label: 'Blocks',
            selected: index == 1,
            onTap: () => onChanged(1),
          ),
          _NavItem(
            icon: Icons.bar_chart_rounded,
            label: 'Reports',
            selected: index == 2,
            onTap: () => onChanged(2),
          ),
          _NavItem(
            icon: Icons.settings_rounded,
            label: 'Settings',
            selected: index == 3,
            onTap: () => onChanged(3),
          ),
        ],
      ),
    );
  }
}

class _NavItem extends StatelessWidget {
  const _NavItem({
    required this.icon,
    required this.label,
    required this.selected,
    required this.onTap,
  });

  final IconData icon;
  final String label;
  final bool selected;
  final VoidCallback onTap;

  @override
  Widget build(BuildContext context) {
    return Material(
      color: selected
          ? AppColors.lime.withValues(alpha: 0.14)
          : Colors.transparent,
      borderRadius: BorderRadius.circular(AppRadius.pill),
      child: InkWell(
        borderRadius: BorderRadius.circular(AppRadius.pill),
        onTap: onTap,
        child: Padding(
          padding: const EdgeInsets.symmetric(horizontal: 10, vertical: 8),
          child: Column(
            mainAxisSize: MainAxisSize.min,
            children: [
              Icon(
                icon,
                size: 21,
                color: selected ? AppColors.lime : AppColors.textSecondary,
              ),
              const SizedBox(height: 3),
              Text(
                label,
                style: TextStyle(
                  color: selected ? AppColors.lime : AppColors.textSecondary,
                  fontSize: 11,
                  fontWeight: FontWeight.w800,
                ),
              ),
            ],
          ),
        ),
      ),
    );
  }
}

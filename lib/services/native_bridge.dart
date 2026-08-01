import 'package:flutter/services.dart';

class NativeBridge {
  static const _channel = MethodChannel('trustissue/native');

  Future<bool> hasAccessibilityAccess() async {
    return await _channel.invokeMethod<bool>('hasAccessibilityAccess') ?? false;
  }

  Future<bool> hasUsageAccess() async {
    return await _channel.invokeMethod<bool>('hasUsageAccess') ?? false;
  }

  Future<bool> hasOverlayAccess() async {
    return await _channel.invokeMethod<bool>('hasOverlayAccess') ?? false;
  }

  Future<bool> requestNotificationPermission() async {
    return await _channel.invokeMethod<bool>('requestNotificationPermission') ??
        false;
  }

  Future<bool> hasBatteryOptimizationExemption() async {
    return await _channel.invokeMethod<bool>(
          'hasBatteryOptimizationExemption',
        ) ??
        false;
  }

  Future<bool> hasDndPolicyAccess() async {
    return await _channel.invokeMethod<bool>('hasDndPolicyAccess') ?? false;
  }

  Future<void> openDndPolicyAccessSettings() async {
    await _channel.invokeMethod<void>('openDndPolicyAccessSettings');
  }

  Future<void> openAccessibilitySettings() async {
    await _channel.invokeMethod<void>('openAccessibilitySettings');
  }

  Future<void> openUsageAccessSettings() async {
    await _channel.invokeMethod<void>('openUsageAccessSettings');
  }

  Future<void> openOverlaySettings() async {
    await _channel.invokeMethod<void>('openOverlaySettings');
  }

  Future<void> openBatteryOptimizationSettings() async {
    await _channel.invokeMethod<void>('openBatteryOptimizationSettings');
  }

  Future<void> openAppSettings() async {
    await _channel.invokeMethod<void>('openAppSettings');
  }

  Future<String> exportDebugLog() async {
    return await _channel.invokeMethod<String>('exportDebugLog') ??
        'Downloads/trustissue-log.txt';
  }

  Future<void> clearDebugLog() async {
    await _channel.invokeMethod<void>('clearDebugLog');
  }

  Future<void> purgeLegacyPrivateData() async {
    await _channel.invokeMethod<void>('purgeLegacyPrivateData');
  }

  Future<void> clearLocalAnalytics() async {
    await _channel.invokeMethod<void>('clearLocalAnalytics');
  }

  Future<List<InstalledAppInfo>> getInstalledApps() async {
    final result = await _channel.invokeListMethod<dynamic>('getInstalledApps');
    return (result ?? const [])
        .whereType<Map<dynamic, dynamic>>()
        .map(InstalledAppInfo.fromNative)
        .where((app) => app.packageName.isNotEmpty)
        .toList();
  }

  Future<List<String>> getAlwaysAllowedPackages() async {
    final result =
        await _channel.invokeListMethod<dynamic>('getAlwaysAllowedPackages');
    return (result ?? const [])
        .map((value) => value.toString())
        .where((value) => value.isNotEmpty)
        .toList();
  }

  Future<List<String>> getBrowserPackages() async {
    final result =
        await _channel.invokeListMethod<dynamic>('getBrowserPackages');
    return (result ?? const [])
        .map((value) => value.toString())
        .where((value) => value.isNotEmpty)
        .toList();
  }

  Future<List<InstalledAppInfo>> getPdfReaderApps() async {
    final result = await _channel.invokeListMethod<dynamic>('getPdfReaderApps');
    return (result ?? const [])
        .whereType<Map<dynamic, dynamic>>()
        .map(InstalledAppInfo.fromNative)
        .where((app) => app.packageName.isNotEmpty)
        .toList();
  }

  Future<UsageSummary> loadTodayUsage() async {
    final result =
        await _channel.invokeMapMethod<String, dynamic>('loadTodayUsage');
    return UsageSummary.fromNative(result ?? const {});
  }

  Future<bool> syncAppLimitProtection() async {
    return await _channel.invokeMethod<bool>('syncAppLimitProtection') ?? false;
  }

  Future<WebProtectionState> loadWebProtectionState() async {
    final result = await _channel
        .invokeMapMethod<String, dynamic>('loadWebProtectionState');
    return WebProtectionState.fromNative(result ?? const {});
  }

  Future<WebProtectionPrepareResult> prepareWebProtection({
    bool replaceExistingVpn = false,
    bool forceRequired = false,
  }) async {
    final result = await _channel.invokeMapMethod<String, dynamic>(
      'prepareWebProtection',
      {
        'replaceExistingVpn': replaceExistingVpn,
        'forceRequired': forceRequired,
      },
    );
    return WebProtectionPrepareResult.fromNative(result ?? const {});
  }

  Future<bool> syncWebProtection() async {
    return await _channel.invokeMethod<bool>('syncWebProtection') ?? false;
  }

  Future<bool> setExternalVpnCompatibility(bool enabled) async {
    return await _channel.invokeMethod<bool>(
          'setExternalVpnCompatibility',
          {'enabled': enabled},
        ) ??
        false;
  }

  Future<Uint8List?> getAppIcon(String packageName) async {
    if (packageName.isEmpty) return null;
    return _channel.invokeMethod<Uint8List>(
      'getAppIcon',
      {'packageName': packageName},
    );
  }

  Future<FocusStartResult> setFocusEnabled(bool enabled) async {
    final result = await _channel.invokeMapMethod<String, dynamic>(
      'recordStudyModeChanged',
      {'enabled': enabled},
    );
    return FocusStartResult.fromNative(result ?? const {});
  }

  Future<void> openEmergencyExit() async {
    await _channel.invokeMethod<void>('openEmergencyExit');
  }

  Future<void> recordEmergencyExit({
    required String reason,
    required String source,
  }) async {
    await _channel.invokeMethod<void>(
      'recordEmergencyExit',
      {
        'reason': reason,
        'source': source,
      },
    );
  }

  Future<void> recordEmergencyExitAttempt({required String source}) async {
    await _channel.invokeMethod<void>(
      'recordEmergencyExitAttempt',
      {'source': source},
    );
  }

  Future<void> recordEmergencyExitCancelled({required String source}) async {
    await _channel.invokeMethod<void>(
      'recordEmergencyExitCancelled',
      {'source': source},
    );
  }

  Future<FocusBreakState> loadFocusBreakState() async {
    final result =
        await _channel.invokeMapMethod<String, dynamic>('loadFocusBreakState');
    return FocusBreakState.fromNative(result ?? const {});
  }

  Future<bool> endFocusBreak() async {
    return await _channel.invokeMethod<bool>('endFocusBreak') ?? false;
  }

  Future<FocusSessionState> loadFocusSessionState() async {
    final result = await _channel
        .invokeMapMethod<String, dynamic>('loadFocusSessionState');
    return FocusSessionState.fromNative(result ?? const {});
  }

  Future<List<SelfControlDayMetrics>> loadSelfControlAnalytics({
    int days = 30,
  }) async {
    final result = await _channel.invokeListMethod<dynamic>(
      'loadSelfControlAnalytics',
      {'days': days},
    );
    return (result ?? const [])
        .whereType<Map<dynamic, dynamic>>()
        .map(SelfControlDayMetrics.fromNative)
        .toList();
  }
}

class FocusStartResult {
  const FocusStartResult({
    required this.success,
    required this.code,
    required this.message,
  });

  final bool success;
  final String code;
  final String message;

  factory FocusStartResult.fromNative(Map<String, dynamic> data) {
    return FocusStartResult(
      success: data['success'] == true,
      code: data['code']?.toString() ?? 'unknown',
      message: data['message']?.toString() ?? 'Focus state was not changed.',
    );
  }
}

class WebProtectionState {
  const WebProtectionState({
    required this.required,
    required this.state,
    required this.permissionGranted,
    required this.externalVpnActive,
    required this.externalVpnCompatibility,
    required this.globalEnabled,
    required this.message,
    this.globalCustomCount = 0,
    this.focusCustomCount = 0,
    this.activeRuleCount = 0,
    this.protectedBrowserCount = 0,
  });

  final bool required;
  final String state;
  final bool permissionGranted;
  final bool externalVpnActive;
  final bool externalVpnCompatibility;
  final bool globalEnabled;
  final String message;
  final int globalCustomCount;
  final int focusCustomCount;
  final int activeRuleCount;
  final int protectedBrowserCount;

  bool get active => state == 'active';
  bool get stopping => state == 'stopping';
  bool get starting => state == 'starting';
  bool get reconnecting => state == 'reconnecting';
  bool get degraded => required && state == 'degraded';
  bool get usingExternalVpn => state == 'external_vpn';

  factory WebProtectionState.fromNative(Map<String, dynamic> data) {
    return WebProtectionState(
      required: data['required'] == true,
      state: data['state']?.toString() ?? 'not_required',
      permissionGranted: data['permissionGranted'] == true,
      externalVpnActive: data['externalVpnActive'] == true,
      externalVpnCompatibility: data['externalVpnCompatibility'] == true,
      globalEnabled: data['globalEnabled'] == true,
      message: data['message']?.toString() ?? '',
      globalCustomCount: (data['globalCustomCount'] as num?)?.toInt() ?? 0,
      focusCustomCount: (data['focusCustomCount'] as num?)?.toInt() ?? 0,
      activeRuleCount: (data['activeRuleCount'] as num?)?.toInt() ?? 0,
      protectedBrowserCount:
          (data['protectedBrowserCount'] as num?)?.toInt() ?? 0,
    );
  }
}

class WebProtectionPrepareResult {
  const WebProtectionPrepareResult({
    required this.success,
    required this.code,
    required this.message,
  });

  final bool success;
  final String code;
  final String message;

  factory WebProtectionPrepareResult.fromNative(
    Map<String, dynamic> data,
  ) {
    return WebProtectionPrepareResult(
      success: data['success'] == true,
      code: data['code']?.toString() ?? 'unknown',
      message: data['message']?.toString() ??
          'Web Protection permission was not changed.',
    );
  }
}

class FocusBreakState {
  const FocusBreakState({
    required this.active,
    required this.remainingMs,
  });

  final bool active;
  final int remainingMs;

  factory FocusBreakState.fromNative(Map<String, dynamic> data) {
    final value = data['remainingMs'];
    final remainingMs = value is int
        ? value
        : value is num
            ? value.round()
            : int.tryParse(value?.toString() ?? '') ?? 0;
    return FocusBreakState(
      active: data['active'] == true && remainingMs > 0,
      remainingMs: remainingMs,
    );
  }
}

class FocusSessionState {
  const FocusSessionState({
    required this.active,
    required this.targetMs,
    required this.remainingMs,
    required this.focusedMs,
    required this.breaksLeft,
    this.sessionType = 'timer',
    this.trackingMode = 'book',
    this.activelyCounting = false,
  });

  final bool active;
  final int targetMs;
  final int remainingMs;
  final int focusedMs;
  final int breaksLeft;
  final String sessionType;
  final String trackingMode;
  final bool activelyCounting;

  bool get isStopwatch => sessionType == 'stopwatch';

  double get remainingFraction {
    if (!active || targetMs <= 0) return 1;
    return (remainingMs / targetMs).clamp(0, 1).toDouble();
  }

  factory FocusSessionState.fromNative(Map<String, dynamic> data) {
    int number(String key) {
      final value = data[key];
      if (value is int) return value;
      if (value is num) return value.round();
      return int.tryParse(value?.toString() ?? '') ?? 0;
    }

    final targetMs = number('targetMs');
    final remainingMs = number('remainingMs');
    return FocusSessionState(
      active: data['active'] == true && targetMs > 0 && remainingMs > 0,
      targetMs: targetMs,
      remainingMs: remainingMs,
      focusedMs: number('focusedMs'),
      breaksLeft: number('breaksLeft'),
      sessionType: data['sessionType']?.toString() == 'stopwatch'
          ? 'stopwatch'
          : 'timer',
      trackingMode: data['trackingMode']?.toString() == 'app' ? 'app' : 'book',
      activelyCounting: data['activelyCounting'] == true,
    );
  }
}

class UsageSummary {
  const UsageSummary({
    required this.totalMs,
    required this.byPackage,
  });

  final int totalMs;
  final Map<String, int> byPackage;

  factory UsageSummary.fromNative(Map<String, dynamic> data) {
    int number(dynamic value) {
      if (value is int) return value;
      if (value is num) return value.round();
      return int.tryParse(value?.toString() ?? '') ?? 0;
    }

    final raw = data['byPackage'];
    final byPackage = <String, int>{};
    if (raw is Map) {
      for (final entry in raw.entries) {
        final packageName = entry.key.toString().trim();
        final usedMs = number(entry.value);
        if (packageName.isNotEmpty && usedMs > 0) {
          byPackage[packageName] = usedMs;
        }
      }
    }
    return UsageSummary(
      totalMs: number(data['totalMs']),
      byPackage: byPackage,
    );
  }
}

class InstalledAppInfo {
  const InstalledAppInfo({
    required this.appName,
    required this.packageName,
  });

  final String appName;
  final String packageName;

  factory InstalledAppInfo.fromNative(Map<dynamic, dynamic> data) {
    return InstalledAppInfo(
      appName: data['appName']?.toString() ?? '',
      packageName: data['packageName']?.toString() ?? '',
    );
  }
}

class SelfControlDayMetrics {
  const SelfControlDayMetrics({
    required this.date,
    required this.studyStarts,
    this.studyCompletedCount = 0,
    required this.studyDurationMs,
    required this.blockedAttempts,
    required this.blockedDurationMs,
    required this.handledCount,
    required this.giveUpCount,
    required this.exitAttemptCount,
    required this.exitCancelledCount,
    required this.exitSuccessCount,
    required this.breakCount,
    required this.breakManualEndCount,
    required this.breakExpiredCount,
    required this.breakReturnedCount,
    required this.breakDurationMs,
    required this.breakActualDurationMs,
    required this.breakUnusedReturnedMs,
    required this.focusApps,
    required this.blockedApps,
    required this.breakApps,
  });

  final String date;
  final int studyStarts;
  final int studyCompletedCount;
  final int studyDurationMs;
  final int blockedAttempts;
  final int blockedDurationMs;
  final int handledCount;
  final int giveUpCount;
  final int exitAttemptCount;
  final int exitCancelledCount;
  final int exitSuccessCount;
  final int breakCount;
  final int breakManualEndCount;
  final int breakExpiredCount;
  final int breakReturnedCount;
  final int breakDurationMs;
  final int breakActualDurationMs;
  final int breakUnusedReturnedMs;
  final List<AnalyticsAppMetric> focusApps;
  final List<AnalyticsAppMetric> blockedApps;
  final List<AnalyticsAppMetric> breakApps;

  int get resistedCount => handledCount + exitCancelledCount;
  bool get hasActivity =>
      studyStarts > 0 ||
      studyCompletedCount > 0 ||
      studyDurationMs > 0 ||
      blockedAttempts > 0 ||
      blockedDurationMs > 0 ||
      exitAttemptCount > 0 ||
      exitCancelledCount > 0 ||
      exitSuccessCount > 0 ||
      breakCount > 0 ||
      breakActualDurationMs > 0;

  factory SelfControlDayMetrics.fromNative(Map<dynamic, dynamic> data) {
    int number(String key) {
      final value = data[key];
      if (value is int) return value;
      if (value is num) return value.round();
      return int.tryParse(value?.toString() ?? '') ?? 0;
    }

    return SelfControlDayMetrics(
      date: data['date']?.toString() ?? '',
      studyStarts: number('studyStarts'),
      studyCompletedCount: number('studyCompletedCount'),
      studyDurationMs: number('studyDurationMs'),
      blockedAttempts: number('blockedAttempts'),
      blockedDurationMs: number('blockedDurationMs'),
      handledCount: number('handledCount'),
      giveUpCount: number('giveUpCount'),
      exitAttemptCount: number('exitAttemptCount'),
      exitCancelledCount: number('exitCancelledCount'),
      exitSuccessCount: number('exitSuccessCount'),
      breakCount: number('breakCount'),
      breakManualEndCount: number('breakManualEndCount'),
      breakExpiredCount: number('breakExpiredCount'),
      breakReturnedCount: number('breakReturnedCount'),
      breakDurationMs: number('breakDurationMs'),
      breakActualDurationMs: number('breakActualDurationMs'),
      breakUnusedReturnedMs: number('breakUnusedReturnedMs'),
      focusApps: AnalyticsAppMetric.listFromNative(data['focusApps']),
      blockedApps: AnalyticsAppMetric.listFromNative(data['blockedApps']),
      breakApps: AnalyticsAppMetric.listFromNative(data['breakApps']),
    );
  }
}

class AnalyticsAppMetric {
  const AnalyticsAppMetric({
    required this.appName,
    required this.packageName,
    required this.durationMs,
    required this.attempts,
    required this.breakCount,
    required this.allottedMs,
    required this.actualMs,
    this.returnedCount = 0,
    this.gaveUpCount = 0,
  });

  final String appName;
  final String packageName;
  final int durationMs;
  final int attempts;
  final int breakCount;
  final int allottedMs;
  final int actualMs;
  final int returnedCount;
  final int gaveUpCount;

  factory AnalyticsAppMetric.fromNative(Map<dynamic, dynamic> data) {
    int number(String key) {
      final value = data[key];
      if (value is int) return value;
      if (value is num) return value.round();
      return int.tryParse(value?.toString() ?? '') ?? 0;
    }

    return AnalyticsAppMetric(
      appName: data['appName']?.toString() ?? '',
      packageName: data['packageName']?.toString() ?? '',
      durationMs: number('durationMs'),
      attempts: number('attempts'),
      breakCount: number('breakCount'),
      allottedMs: number('allottedMs'),
      actualMs: number('actualMs'),
      returnedCount: number('returnedCount'),
      gaveUpCount: number('gaveUpCount'),
    );
  }

  static List<AnalyticsAppMetric> listFromNative(Object? value) {
    if (value is! List) return const [];
    return value
        .whereType<Map<dynamic, dynamic>>()
        .map(AnalyticsAppMetric.fromNative)
        .toList();
  }
}

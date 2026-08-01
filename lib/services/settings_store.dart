import 'dart:convert';

import 'package:shared_preferences/shared_preferences.dart';

class SettingsStore {
  static const studyModeEnabledKey = 'studyModeEnabled';
  static const studyModePolicyKey = 'studyModePolicy';
  static const studyTrackingModeKey = 'studyTrackingMode';
  static const studyDurationMinutesKey = 'studyDurationMinutes';
  static const studySessionTypeKey = 'studySessionType';
  static const studyAllowedPackagesJsonKey = 'studyAllowedPackagesJson';
  static const studyBlockedPackagesJsonKey = 'studyBlockedPackagesJson';
  static const studyStrictModeEnabledKey = 'studyStrictModeEnabled';
  static const studyLockedStrictModeEnabledKey = 'studyLockedStrictModeEnabled';
  static const defaultPdfReaderPackageKey = 'defaultPdfReaderPackage';
  static const pdfReaderSetupCompleteKey = 'pdfReaderSetupComplete';
  static const webProtectionCustomDomainsJsonKey =
      'webProtectionCustomDomainsJson';
  static const globalWebProtectionEnabledKey = 'globalWebProtectionEnabled';
  static const globalWebProtectionCustomDomainsJsonKey =
      'globalWebProtectionCustomDomainsJson';
  static const _webProtectionDisclosureVersionKey =
      'webProtectionDisclosureVersion';
  static const _currentWebProtectionDisclosureVersion = 2;
  static const _legacyWebProtectionDisclosureAcceptedKey =
      'webProtectionDisclosureAccepted';
  static const dailyAppLimitsJsonKey = 'dailyAppLimitsJson';
  static const youtubeShortsBlockingEnabledKey = 'youtubeShortsBlockingEnabled';
  static const _youtubeShortsDisclosureVersionKey =
      'youtubeShortsDisclosureVersion';
  static const _currentYoutubeShortsDisclosureVersion = 1;
  static const instagramReelsBlockingEnabledKey =
      'instagramReelsBlockingEnabled';
  static const _instagramReelsDisclosureVersionKey =
      'instagramReelsDisclosureVersion';
  static const _currentInstagramReelsDisclosureVersion = 1;
  static const onboardingCompleteKey = 'onboardingComplete';
  static const _localOnlyMigrationKey = 'localOnlyMigrationV1Complete';

  static const _legacySharingKeys = <String>[
    'backendUrl',
    'childToken',
    'familyCode',
    'deviceId',
    'deviceName',
    'sharingPaused',
    'parentSharingEnabled',
  ];

  Future<SharedPreferences> get _prefs => SharedPreferences.getInstance();

  Future<void> migrateToLocalOnly() async {
    final prefs = await _prefs;
    if (prefs.getBool(_localOnlyMigrationKey) == true) return;
    for (final key in _legacySharingKeys) {
      await prefs.remove(key);
    }
    await prefs.setBool(_localOnlyMigrationKey, true);
  }

  Future<bool> isOnboardingComplete() async {
    final prefs = await _prefs;
    return prefs.getBool(onboardingCompleteKey) ?? false;
  }

  Future<void> setOnboardingComplete(bool value) async {
    final prefs = await _prefs;
    await prefs.setBool(onboardingCompleteKey, value);
  }

  Future<StudySettings> loadStudySettings() async {
    final prefs = await _prefs;
    await prefs.reload();
    final storedTrackingMode = prefs.getString(studyTrackingModeKey);
    final trackingMode = storedTrackingMode == StudySettings.appStudy
        ? StudySettings.appStudy
        : StudySettings.bookStudy;
    final storedPolicy =
        prefs.getString(studyModePolicyKey) ?? StudySettings.allowlist;
    final strictModeEnabled = prefs.getBool(studyStrictModeEnabledKey) ?? false;
    final sessionType =
        prefs.getString(studySessionTypeKey) == StudySettings.stopwatch
            ? StudySettings.stopwatch
            : StudySettings.timer;
    return StudySettings(
      enabled: prefs.getBool(studyModeEnabledKey) ?? false,
      policy: trackingMode == StudySettings.appStudy
          ? StudySettings.allowlist
          : storedPolicy,
      trackingMode: trackingMode,
      durationMinutes: prefs.getInt(studyDurationMinutesKey) ?? 25,
      sessionType: sessionType,
      allowedPackages: StudySettings.decodePackages(
        prefs.getString(studyAllowedPackagesJsonKey),
      ),
      blockedPackages: StudySettings.decodePackages(
        prefs.getString(studyBlockedPackagesJsonKey),
      ),
      strictModeEnabled: strictModeEnabled,
      lockedStrictModeEnabled: sessionType != StudySettings.stopwatch &&
          strictModeEnabled &&
          (prefs.getBool(studyLockedStrictModeEnabledKey) ?? false),
      defaultPdfReaderPackage:
          prefs.getString(defaultPdfReaderPackageKey) ?? '',
      pdfReaderSetupComplete: prefs.getBool(pdfReaderSetupCompleteKey) ?? false,
      customBlockedDomains: StudySettings.decodePackages(
        prefs.getString(webProtectionCustomDomainsJsonKey),
      ),
      globalWebProtectionEnabled:
          prefs.getBool(globalWebProtectionEnabledKey) ?? false,
      globalCustomBlockedDomains: StudySettings.decodePackages(
        prefs.getString(globalWebProtectionCustomDomainsJsonKey),
      ),
      dailyAppLimits: StudySettings.decodeAppLimits(
        prefs.getString(dailyAppLimitsJsonKey),
      ),
      youtubeShortsBlockingEnabled:
          prefs.getBool(youtubeShortsBlockingEnabledKey) ?? false,
      instagramReelsBlockingEnabled:
          prefs.getBool(instagramReelsBlockingEnabledKey) ?? false,
    );
  }

  Future<void> saveStudySettings(StudySettings settings) async {
    final prefs = await _prefs;
    // Disable first when stopping, but enable only after the complete session
    // configuration is durable. Native observers must never see an active
    // session with a partially-written mode, duration or app list.
    if (!settings.enabled) {
      await prefs.setBool(studyModeEnabledKey, false);
    }
    await prefs.setString(studyModePolicyKey, settings.policy);
    await prefs.setString(studyTrackingModeKey, settings.trackingMode);
    await prefs.setInt(studyDurationMinutesKey, settings.durationMinutes);
    await prefs.setString(studySessionTypeKey, settings.sessionType);
    await prefs.setString(
      studyAllowedPackagesJsonKey,
      StudySettings.encodePackages(settings.allowedPackages),
    );
    await prefs.setString(
      studyBlockedPackagesJsonKey,
      StudySettings.encodePackages(settings.blockedPackages),
    );
    await prefs.setBool(
      studyStrictModeEnabledKey,
      settings.strictModeEnabled,
    );
    await prefs.setBool(
      studyLockedStrictModeEnabledKey,
      settings.strictModeEnabled && settings.lockedStrictModeEnabled,
    );
    await prefs.setString(
      defaultPdfReaderPackageKey,
      settings.defaultPdfReaderPackage,
    );
    await prefs.setBool(
      pdfReaderSetupCompleteKey,
      settings.pdfReaderSetupComplete,
    );
    await prefs.setString(
      webProtectionCustomDomainsJsonKey,
      StudySettings.encodePackages(settings.customBlockedDomains),
    );
    await prefs.setBool(
      globalWebProtectionEnabledKey,
      settings.globalWebProtectionEnabled,
    );
    await prefs.setString(
      globalWebProtectionCustomDomainsJsonKey,
      StudySettings.encodePackages(settings.globalCustomBlockedDomains),
    );
    await prefs.setString(
      dailyAppLimitsJsonKey,
      StudySettings.encodeAppLimits(settings.dailyAppLimits),
    );
    await prefs.setBool(
      youtubeShortsBlockingEnabledKey,
      settings.youtubeShortsBlockingEnabled,
    );
    await prefs.setBool(
      instagramReelsBlockingEnabledKey,
      settings.instagramReelsBlockingEnabled,
    );
    if (settings.enabled) {
      await prefs.setBool(studyModeEnabledKey, true);
    }
  }

  Future<void> savePdfReaderSelection(String packageName) async {
    final prefs = await _prefs;
    await prefs.setString(defaultPdfReaderPackageKey, packageName);
    await prefs.setBool(pdfReaderSetupCompleteKey, true);
  }

  Future<bool> isWebProtectionDisclosureAccepted() async {
    final prefs = await _prefs;
    return prefs.getInt(_webProtectionDisclosureVersionKey) ==
        _currentWebProtectionDisclosureVersion;
  }

  Future<void> setWebProtectionDisclosureAccepted(bool value) async {
    final prefs = await _prefs;
    await prefs.remove(_legacyWebProtectionDisclosureAcceptedKey);
    if (value) {
      await prefs.setInt(
        _webProtectionDisclosureVersionKey,
        _currentWebProtectionDisclosureVersion,
      );
    } else {
      await prefs.remove(_webProtectionDisclosureVersionKey);
    }
  }

  Future<bool> isYoutubeShortsDisclosureAccepted() async {
    final prefs = await _prefs;
    return prefs.getInt(_youtubeShortsDisclosureVersionKey) ==
        _currentYoutubeShortsDisclosureVersion;
  }

  Future<void> setYoutubeShortsDisclosureAccepted(bool value) async {
    final prefs = await _prefs;
    if (value) {
      await prefs.setInt(
        _youtubeShortsDisclosureVersionKey,
        _currentYoutubeShortsDisclosureVersion,
      );
    } else {
      await prefs.remove(_youtubeShortsDisclosureVersionKey);
    }
  }

  Future<bool> isInstagramReelsDisclosureAccepted() async {
    final prefs = await _prefs;
    return prefs.getInt(_instagramReelsDisclosureVersionKey) ==
        _currentInstagramReelsDisclosureVersion;
  }

  Future<void> setInstagramReelsDisclosureAccepted(bool value) async {
    final prefs = await _prefs;
    if (value) {
      await prefs.setInt(
        _instagramReelsDisclosureVersionKey,
        _currentInstagramReelsDisclosureVersion,
      );
    } else {
      await prefs.remove(_instagramReelsDisclosureVersionKey);
    }
  }
}

class StudySettings {
  const StudySettings({
    required this.enabled,
    required this.policy,
    required this.trackingMode,
    required this.durationMinutes,
    this.sessionType = timer,
    required this.allowedPackages,
    required this.blockedPackages,
    required this.strictModeEnabled,
    this.lockedStrictModeEnabled = false,
    this.defaultPdfReaderPackage = '',
    this.pdfReaderSetupComplete = false,
    required this.customBlockedDomains,
    this.globalWebProtectionEnabled = false,
    this.globalCustomBlockedDomains = const <String>{},
    this.dailyAppLimits = const <String, int>{},
    this.youtubeShortsBlockingEnabled = false,
    this.instagramReelsBlockingEnabled = false,
  });

  static const allowlist = 'allowlist';
  static const blocklist = 'blocklist';
  static const appStudy = 'app';
  static const bookStudy = 'book';
  static const timer = 'timer';
  static const stopwatch = 'stopwatch';
  static const maxLockedStrictMinutes = 3 * 60;
  static const maxStrictMinutes = 8 * 60;
  static const maxNormalMinutes = 24 * 60;

  final bool enabled;
  final String policy;
  final String trackingMode;
  final int durationMinutes;
  final String sessionType;
  final Set<String> allowedPackages;
  final Set<String> blockedPackages;
  final bool strictModeEnabled;
  final bool lockedStrictModeEnabled;
  final String defaultPdfReaderPackage;
  final bool pdfReaderSetupComplete;
  final Set<String> customBlockedDomains;
  final bool globalWebProtectionEnabled;
  final Set<String> globalCustomBlockedDomains;
  final Map<String, int> dailyAppLimits;
  final bool youtubeShortsBlockingEnabled;
  final bool instagramReelsBlockingEnabled;

  bool get isAppStudy => trackingMode == appStudy;
  bool get isStopwatch => sessionType == stopwatch;
  int get breakAllowance => isStopwatch ? 0 : durationMinutes ~/ 30;
  int get maximumDurationMinutes => lockedStrictModeEnabled
      ? maxLockedStrictMinutes
      : strictModeEnabled
          ? maxStrictMinutes
          : maxNormalMinutes;

  StudySettings copyWith({
    bool? enabled,
    String? policy,
    String? trackingMode,
    int? durationMinutes,
    String? sessionType,
    Set<String>? allowedPackages,
    Set<String>? blockedPackages,
    bool? strictModeEnabled,
    bool? lockedStrictModeEnabled,
    String? defaultPdfReaderPackage,
    bool? pdfReaderSetupComplete,
    Set<String>? customBlockedDomains,
    bool? globalWebProtectionEnabled,
    Set<String>? globalCustomBlockedDomains,
    Map<String, int>? dailyAppLimits,
    bool? youtubeShortsBlockingEnabled,
    bool? instagramReelsBlockingEnabled,
  }) {
    final nextSessionType = sessionType == stopwatch
        ? stopwatch
        : sessionType == timer
            ? timer
            : this.sessionType;
    final nextStrictModeEnabled = strictModeEnabled ?? this.strictModeEnabled;
    final nextLockedStrictModeEnabled = nextStrictModeEnabled &&
        nextSessionType != stopwatch &&
        (lockedStrictModeEnabled ?? this.lockedStrictModeEnabled);
    return StudySettings(
      enabled: enabled ?? this.enabled,
      policy: policy ?? this.policy,
      trackingMode: trackingMode ?? this.trackingMode,
      durationMinutes: durationMinutes ?? this.durationMinutes,
      sessionType: nextSessionType,
      allowedPackages: allowedPackages ?? this.allowedPackages,
      blockedPackages: blockedPackages ?? this.blockedPackages,
      strictModeEnabled: nextStrictModeEnabled,
      lockedStrictModeEnabled: nextLockedStrictModeEnabled,
      defaultPdfReaderPackage:
          defaultPdfReaderPackage ?? this.defaultPdfReaderPackage,
      pdfReaderSetupComplete:
          pdfReaderSetupComplete ?? this.pdfReaderSetupComplete,
      customBlockedDomains: customBlockedDomains ?? this.customBlockedDomains,
      globalWebProtectionEnabled:
          globalWebProtectionEnabled ?? this.globalWebProtectionEnabled,
      globalCustomBlockedDomains:
          globalCustomBlockedDomains ?? this.globalCustomBlockedDomains,
      dailyAppLimits: dailyAppLimits ?? this.dailyAppLimits,
      youtubeShortsBlockingEnabled:
          youtubeShortsBlockingEnabled ?? this.youtubeShortsBlockingEnabled,
      instagramReelsBlockingEnabled:
          instagramReelsBlockingEnabled ?? this.instagramReelsBlockingEnabled,
    );
  }

  static Set<String> decodePackages(String? raw) {
    if (raw == null || raw.trim().isEmpty) return <String>{};
    try {
      final decoded = jsonDecode(raw);
      if (decoded is List) {
        return decoded
            .map((value) => value.toString().trim())
            .where((value) => value.isNotEmpty)
            .toSet();
      }
    } catch (_) {
      return <String>{};
    }
    return <String>{};
  }

  static String encodePackages(Set<String> packages) {
    final values = packages.toList()..sort();
    return jsonEncode(values);
  }

  static Map<String, int> decodeAppLimits(String? raw) {
    if (raw == null || raw.trim().isEmpty) return <String, int>{};
    try {
      final decoded = jsonDecode(raw);
      if (decoded is! Map) return <String, int>{};
      final result = <String, int>{};
      for (final entry in decoded.entries) {
        final packageName = entry.key.toString().trim();
        final value = entry.value;
        final minutes = value is num
            ? value.round()
            : int.tryParse(value?.toString() ?? '');
        if (packageName.isNotEmpty && minutes != null && minutes > 0) {
          result[packageName] = minutes.clamp(1, maxNormalMinutes).toInt();
        }
      }
      return result;
    } catch (_) {
      return <String, int>{};
    }
  }

  static String encodeAppLimits(Map<String, int> limits) {
    final normalized = <String, int>{};
    final keys = limits.keys.toList()..sort();
    for (final packageName in keys) {
      final minutes = limits[packageName] ?? 0;
      if (packageName.trim().isNotEmpty && minutes > 0) {
        normalized[packageName.trim()] =
            minutes.clamp(1, maxNormalMinutes).toInt();
      }
    }
    return jsonEncode(normalized);
  }
}

import 'package:flutter_test/flutter_test.dart';
import 'package:shared_preferences/shared_preferences.dart';
import 'package:trustissue/services/native_bridge.dart';
import 'package:trustissue/services/settings_store.dart';

void main() {
  group('StudySettings', () {
    test('package sets round-trip in stable order', () {
      final encoded = StudySettings.encodePackages({
        'example.zeta',
        'example.alpha',
      });

      expect(
        StudySettings.decodePackages(encoded),
        {'example.alpha', 'example.zeta'},
      );
    });

    test('strict and normal modes expose safe maximum durations', () {
      const normal = StudySettings(
        enabled: false,
        policy: StudySettings.allowlist,
        trackingMode: StudySettings.bookStudy,
        durationMinutes: 25,
        allowedPackages: {},
        blockedPackages: {},
        strictModeEnabled: false,
        customBlockedDomains: {},
      );

      expect(normal.maximumDurationMinutes, 24 * 60);
      expect(
        normal.copyWith(strictModeEnabled: true).maximumDurationMinutes,
        8 * 60,
      );
    });

    test('app study mode is explicit and survives copyWith', () {
      const settings = StudySettings(
        enabled: false,
        policy: StudySettings.allowlist,
        trackingMode: StudySettings.bookStudy,
        durationMinutes: 25,
        allowedPackages: {},
        blockedPackages: {},
        strictModeEnabled: false,
        customBlockedDomains: {},
      );

      final appStudy = settings.copyWith(trackingMode: StudySettings.appStudy);
      expect(appStudy.isAppStudy, isTrue);
      expect(settings.isAppStudy, isFalse);
    });

    test('global website settings remain separate from focus domains', () {
      const settings = StudySettings(
        enabled: false,
        policy: StudySettings.allowlist,
        trackingMode: StudySettings.bookStudy,
        durationMinutes: 25,
        allowedPackages: {},
        blockedPackages: {},
        strictModeEnabled: false,
        customBlockedDomains: {'focus.example'},
      );

      final global = settings.copyWith(
        globalWebProtectionEnabled: true,
        globalCustomBlockedDomains: {'global.example'},
      );

      expect(global.globalWebProtectionEnabled, isTrue);
      expect(global.globalCustomBlockedDomains, {'global.example'});
      expect(global.customBlockedDomains, {'focus.example'});
    });

    test('YouTube Shorts blocking survives copyWith', () {
      const settings = StudySettings(
        enabled: false,
        policy: StudySettings.allowlist,
        trackingMode: StudySettings.bookStudy,
        durationMinutes: 25,
        allowedPackages: {},
        blockedPackages: {},
        strictModeEnabled: false,
        customBlockedDomains: {},
      );

      final enabled = settings.copyWith(youtubeShortsBlockingEnabled: true);

      expect(enabled.youtubeShortsBlockingEnabled, isTrue);
      expect(settings.youtubeShortsBlockingEnabled, isFalse);
    });

    test('Instagram Reels blocking survives copyWith', () {
      const settings = StudySettings(
        enabled: false,
        policy: StudySettings.allowlist,
        trackingMode: StudySettings.bookStudy,
        durationMinutes: 25,
        allowedPackages: {},
        blockedPackages: {},
        strictModeEnabled: false,
        customBlockedDomains: {},
      );

      final enabled = settings.copyWith(instagramReelsBlockingEnabled: true);

      expect(enabled.instagramReelsBlockingEnabled, isTrue);
      expect(settings.instagramReelsBlockingEnabled, isFalse);
    });
  });

  group('SettingsStore website domains', () {
    test('global and Focus domains persist independently', () async {
      SharedPreferences.setMockInitialValues({});
      final store = SettingsStore();
      const settings = StudySettings(
        enabled: false,
        policy: StudySettings.allowlist,
        trackingMode: StudySettings.bookStudy,
        durationMinutes: 25,
        allowedPackages: {},
        blockedPackages: {},
        strictModeEnabled: false,
        customBlockedDomains: {'focus.example'},
        globalWebProtectionEnabled: true,
        globalCustomBlockedDomains: {'global.example'},
      );

      await store.saveStudySettings(settings);
      final loaded = await store.loadStudySettings();

      expect(loaded.customBlockedDomains, {'focus.example'});
      expect(loaded.globalCustomBlockedDomains, {'global.example'});
      expect(loaded.globalWebProtectionEnabled, isTrue);
    });

    test('outdated website disclosure requires fresh acceptance', () async {
      SharedPreferences.setMockInitialValues({
        'webProtectionDisclosureAccepted': true,
      });
      final store = SettingsStore();

      expect(await store.isWebProtectionDisclosureAccepted(), isFalse);

      await store.setWebProtectionDisclosureAccepted(true);
      expect(await store.isWebProtectionDisclosureAccepted(), isTrue);

      await store.setWebProtectionDisclosureAccepted(false);
      expect(await store.isWebProtectionDisclosureAccepted(), isFalse);
    });

    test('YouTube Shorts setting and disclosure persist', () async {
      SharedPreferences.setMockInitialValues({});
      final store = SettingsStore();
      const settings = StudySettings(
        enabled: false,
        policy: StudySettings.allowlist,
        trackingMode: StudySettings.bookStudy,
        durationMinutes: 25,
        allowedPackages: {},
        blockedPackages: {},
        strictModeEnabled: false,
        customBlockedDomains: {},
        youtubeShortsBlockingEnabled: true,
      );

      await store.saveStudySettings(settings);
      await store.setYoutubeShortsDisclosureAccepted(true);
      final loaded = await store.loadStudySettings();

      expect(loaded.youtubeShortsBlockingEnabled, isTrue);
      expect(await store.isYoutubeShortsDisclosureAccepted(), isTrue);
    });

    test('Instagram Reels setting and disclosure persist', () async {
      SharedPreferences.setMockInitialValues({});
      final store = SettingsStore();
      const settings = StudySettings(
        enabled: false,
        policy: StudySettings.allowlist,
        trackingMode: StudySettings.bookStudy,
        durationMinutes: 25,
        allowedPackages: {},
        blockedPackages: {},
        strictModeEnabled: false,
        customBlockedDomains: {},
        instagramReelsBlockingEnabled: true,
      );

      await store.saveStudySettings(settings);
      await store.setInstagramReelsDisclosureAccepted(true);
      final loaded = await store.loadStudySettings();

      expect(loaded.instagramReelsBlockingEnabled, isTrue);
      expect(await store.isInstagramReelsDisclosureAccepted(), isTrue);
    });
  });

  group('native result parsing', () {
    test('start failure remains a typed failure', () {
      final result = FocusStartResult.fromNative({
        'success': false,
        'code': 'accessibility_missing',
        'message': 'Accessibility is required.',
      });

      expect(result.success, isFalse);
      expect(result.code, 'accessibility_missing');
    });

    test('session progress is bounded', () {
      final session = FocusSessionState.fromNative({
        'active': true,
        'targetMs': 1000,
        'remainingMs': 250,
        'focusedMs': 750,
        'breaksLeft': 0,
        'trackingMode': 'app',
        'activelyCounting': true,
      });

      expect(session.active, isTrue);
      expect(session.remainingFraction, 0.25);
      expect(session.trackingMode, 'app');
      expect(session.activelyCounting, isTrue);
    });

    test('web protection exposes native rule verification counts', () {
      final state = WebProtectionState.fromNative({
        'required': true,
        'state': 'active',
        'permissionGranted': true,
        'externalVpnActive': false,
        'externalVpnCompatibility': false,
        'globalEnabled': true,
        'globalCustomCount': 2,
        'focusCustomCount': 1,
        'activeRuleCount': 19,
        'protectedBrowserCount': 2,
        'message': 'Active',
      });

      expect(state.globalCustomCount, 2);
      expect(state.focusCustomCount, 1);
      expect(state.activeRuleCount, 19);
      expect(state.protectedBrowserCount, 2);
    });
  });
}

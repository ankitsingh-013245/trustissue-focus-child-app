# TrustIssue Focus — Child Android App

TrustIssue Focus is a local-first Flutter and native Android self-control application. It helps a user run focus sessions, limit distracting applications, apply website rules, review local focus analytics, and use stricter protection modes when extra friction is needed.

This repository contains the standalone **child Android app** from the larger TrustIssue project. It does not include the parent application or backend services. The current app does not require a TrustIssue account or backend connection for its core focus features.

> **Project status:** active pre-release software. The package version is `0.2.4+11`. Review the privacy-policy placeholders, Play release checklist, accessibility disclosure, VPN behavior, signing setup, and device QA before publishing a production build.

## What the app does

- Runs timer-based or stopwatch-based Focus sessions.
- Supports blocklist and allowlist app policies.
- Supports continuous/book study and selected-app study modes.
- Provides Normal, Strict, and Locked Strict protection levels.
- Tracks focus time, blocked attempts, breaks, returns, exits, and app-level summaries locally.
- Offers controlled focus breaks and session-scoped study-tool access.
- Supports PDF-reader selection for study workflows.
- Applies daily app-limit protection using Android usage information.
- Can show an overlay/focus pill during selected-app study.
- Can apply Focus-only or Global Website Protection using a local Android `VpnService` DNS filter.
- Supports locally configured blocked domains and browser protection rules.
- Can optionally detect and block YouTube Shorts and Instagram Reels through Android Accessibility APIs.
- Restores eligible protection state after reboot, app update, time changes, and timezone changes.
- Provides an emergency-exit flow and bounded local diagnostic-log export.
- Includes daily, weekly, and monthly focus analytics screens.

## Platform support

| Item | Requirement |
| --- | --- |
| Target platform | Android only |
| Minimum Android version | Android 10 / API 29 |
| Flutter/Dart constraint | Dart `>=3.4.0 <4.0.0` |
| Java | JDK 17 |
| Android namespace | `com.trustissue.child` |
| App version | `0.2.4+11` |

The repository does not currently contain iOS, web, Windows, macOS, or Linux platform projects. Several core features depend directly on Android services and permissions.

## Technology

- Flutter and Dart for application UI, state presentation, local settings, and analytics views.
- Kotlin for Android focus enforcement, usage monitoring, accessibility behavior, overlays, local VPN/DNS filtering, receivers, activities, and platform integrations.
- Flutter `MethodChannel` named `trustissue/native` for communication between Dart and Kotlin.
- `shared_preferences` for Flutter-side local settings.
- Android private preferences/storage for native rules, session state, and local metrics.
- OkHttp for encrypted DNS requests used by Website Protection.

## Architecture

```text
Flutter screens and widgets
        │
        ├── SettingsStore / local UI state
        │
        └── MethodChannel: trustissue/native
                    │
                    ├── MainActivity platform API
                    ├── Focus policy and session engine
                    ├── Usage monitor foreground service
                    ├── Accessibility protection service
                    ├── Overlay and focus-gate activities
                    ├── Website Protection VpnService
                    └── Boot/time/update restore receivers
```

Core boundaries:

- Flutter owns the visual experience and user configuration flows.
- Kotlin owns Android permission checks and protection that must continue outside the Flutter activity.
- Focus rules and analytics remain local to the device.
- Website Protection is a DNS-filtering split tunnel; it does not decrypt HTTPS traffic or inspect page content.

See [`docs/combined_protection_architecture.md`](docs/combined_protection_architecture.md) for VPN-conflict handling and the boundary for any future combined provider tunnel.

## Repository structure

| Path | Purpose |
| --- | --- |
| `lib/main.dart` | App entry point and onboarding/home routing. |
| `lib/screens/` | Onboarding, home, focus setup, block rules, analytics, settings, strict preparation, and emergency-exit UI. |
| `lib/widgets/` | Shared visual components, website controls, app tiles, PDF reader picker, and loading UI. |
| `lib/services/` | Local settings, native channel wrapper, and app-icon cache. |
| `lib/theme/` | Application colors, typography, and theme. |
| `android/app/src/main/kotlin/` | Native Android policy, services, receivers, activities, VPN/DNS, overlays, and detectors. |
| `android/app/src/main/res/` | Android manifests, icons, XML service configuration, strings, styles, and animations. |
| `test/` | Flutter unit/widget tests for settings and domain-rule behavior. |
| `android/app/src/test/` | Kotlin/JVM policy, DNS codec, usage, detector, and retry tests. |
| `docs/` | Privacy draft, Play release checklist, accessibility review script, and protection architecture. |
| `tools/generate_feature_audit.py` | Generates the feature-audit workbook. |
| `TrustIssue_Feature_Audit_2026-07-23.xlsx` | Point-in-time feature and release audit. |

## Clone and open locally

### 1. Install prerequisites

Install:

- [Git](https://git-scm.com/)
- [Flutter SDK](https://docs.flutter.dev/get-started/install) on the stable channel
- Android Studio with Android SDK and platform tools
- JDK 17
- An Android 10+ emulator or physical device

Confirm the environment:

```powershell
flutter --version
flutter doctor -v
java -version
```

Resolve every required Android/Flutter issue reported by `flutter doctor` before continuing.

### 2. Clone the repository

```powershell
git clone https://github.com/ankitsingh-013245/trustissue-focus-child-app.git
cd trustissue-focus-child-app
```

### 3. Fetch Flutter packages

```powershell
flutter pub get
```

Flutter/Android tooling generates `android/local.properties` with machine-specific SDK paths. Do not commit that file.

### 4. Open the project

In Android Studio:

1. Choose **Open**.
2. Select the cloned `trustissue-focus-child-app` folder, not only the `android` subfolder.
3. Allow Gradle and Flutter package indexing to finish.
4. Select an Android 10+ device.

In VS Code:

```powershell
code .
```

Install the Flutter and Dart extensions if prompted.

### 5. Run the application

List connected devices:

```powershell
flutter devices
```

Run a debug build:

```powershell
flutter run
```

The app starts with onboarding. Android permissions are intentionally requested through user-visible flows rather than silently enabled.

## Device setup and permissions

Not every feature needs every permission. Enable only the features being tested.

| Access | Used for |
| --- | --- |
| Usage Access | Foreground-app usage, daily limits, and compatible app-policy enforcement. |
| Display over other apps | Focus pill and supported overlay experiences. |
| Notification permission | Foreground-service and protection status notifications. |
| Do Not Disturb policy access | Strict-mode notification suppression/restoration. |
| Battery optimization exemption | More reliable long-running focus protection. |
| Accessibility service | Optional advanced blocking, study overlays, and separately enabled Shorts/Reels controls. |
| Android VPN consent | Focus-only or Global Website Protection. |

For a clean test:

1. Complete onboarding.
2. Grant Usage Access when asked.
3. Select a small test set of blocked or allowed apps.
4. Start a short Normal Focus session.
5. Test Strict mode only after reviewing DND and emergency-exit behavior.
6. Enable Accessibility only when testing features that clearly disclose the requirement.
7. Enable Website Protection only after reviewing the VPN disclosure and any existing VPN conflict.

## Website Protection and network behavior

Android permits one active `VpnService` per user/profile. TrustIssue therefore asks before replacing another VPN and supports keeping the existing VPN with TrustIssue filtering paused.

When enabled, the local VPN:

- Includes eligible browser packages rather than tunneling all device traffic.
- Checks user and focus domain rules locally.
- Sends DNS requests through Cloudflare's family DNS-over-HTTPS resolver.
- Uses CleanBrowsing's family resolver as a failure-only fallback.
- Does not decrypt HTTPS, inspect web pages, read search text, or store browser history.
- Keeps a short in-memory DNS cache for performance.

DNS hostnames and ordinary network metadata are processed by the resolver used for the request under that resolver's privacy terms. Review the privacy draft before any public release.

## Accessibility behavior

Accessibility is optional for compatible core focus and daily-limit behavior, but it is required for advanced features marked as such.

When enabled, the service can observe foreground package transitions to apply configured focus rules. YouTube Shorts and Instagram Reels blocking are separately controlled. Their visible accessibility node trees are evaluated in memory only while the relevant feature and app are active.

The implementation is designed not to capture screenshots, read notifications/messages/passwords/form values, type into other apps, or submit forms. Review:

- [`docs/ACCESSIBILITY_REVIEW_SCRIPT.md`](docs/ACCESSIBILITY_REVIEW_SCRIPT.md)
- [`docs/PRIVACY_POLICY_DRAFT.md`](docs/PRIVACY_POLICY_DRAFT.md)
- [`docs/PLAY_RELEASE_CHECKLIST.md`](docs/PLAY_RELEASE_CHECKLIST.md)

## Local data and privacy

The app is local-first and does not require an account. It stores focus configuration, selected apps, session state, domain rules, consent flags, blocked-attempt metrics, break activity, and analytics on the Android device.

- Android backup and device transfer are disabled for app data.
- Cleartext network traffic is disabled.
- Local reports can be cleared from Settings.
- Diagnostic logs are exported only after an explicit user action.
- Uninstalling the app removes app-managed data according to Android behavior.

The privacy policy is currently a draft and contains contact/URL placeholders. Replace those placeholders before distribution.

## Tests and validation

### Flutter checks

```powershell
flutter analyze
flutter test
```

### Android Kotlin/JVM tests

From the repository root on Windows:

```powershell
Set-Location android
.\gradlew.bat :app:testDebugUnitTest
Set-Location ..
```

On macOS/Linux:

```bash
cd android
./gradlew :app:testDebugUnitTest
cd ..
```

The repository currently contains Flutter tests plus Kotlin tests covering focus policies, usage events, daily duration splitting, DNS packet handling, accessibility ownership, YouTube/Instagram detectors, and retry behavior.

Use the app-scoped Gradle task shown above. Running the aggregate `testDebugUnitTest` task also executes tests bundled inside Flutter plugins; with Android SDK 36 and JDK 17, the current `shared_preferences_android` Robolectric test requires JDK 21 even though the TrustIssue app tests themselves pass on JDK 17.

## Build APKs

Debug APK:

```powershell
flutter build apk --debug
```

Release APK without a configured upload key falls back to the debug signing configuration for local installability. Do not distribute that artifact as a production release.

## Configure production signing

1. Create an Android upload keystore and store it outside the repository.
2. Copy `android/key.properties.example` to `android/key.properties`.
3. Replace every placeholder with the local keystore values.
4. Keep `android/key.properties` and the keystore private. Both are ignored by Git.
5. Build an Android App Bundle:

```powershell
flutter build appbundle --release
```

Expected output:

```text
build/app/outputs/bundle/release/app-release.aab
```

Never commit passwords, `key.properties`, `.jks`, `.keystore`, `.p12`, or private-key files.

## Release checklist

Before shipping:

1. Replace privacy-policy contact and public URL placeholders.
2. Review all permission disclosures on a physical Android device.
3. Complete the Accessibility and VPN declarations required by the target store.
4. Test with and without Usage Access, Overlay, DND, Accessibility, VPN consent, and battery exemption.
5. Test reboot, app update, time change, timezone change, process death, and permission revocation.
6. Test VPN conflict handling and DNS failure behavior.
7. Run Flutter and Kotlin tests.
8. Generate a signed AAB with the private upload key.
9. Follow [`docs/PLAY_RELEASE_CHECKLIST.md`](docs/PLAY_RELEASE_CHECKLIST.md).

## Troubleshooting

### `flutter doctor` reports Android toolchain errors

- Open Android Studio's SDK Manager and install the recommended SDK/platform tools.
- Accept licenses with `flutter doctor --android-licenses`.
- Confirm JDK 17 is selected.

### No device is available

- Start an Android 10+ emulator, or enable Developer options and USB debugging on a physical device.
- Run `flutter devices` again.

### Gradle cannot find the SDK

- Run `flutter pub get` and `flutter run` from the repository root.
- Confirm Flutter/Android Studio generated `android/local.properties`.
- Do not copy another developer's absolute SDK paths.

### A focus feature does not work

- Check the Settings screen for the feature's required Android access.
- Confirm the selected test app is not in the always-allowed package set.
- Review exported diagnostics only after reproducing the issue.
- Android emulators may not reproduce every OEM background-service or Accessibility behavior; verify on a physical device.

### Website Protection does not start

- Check whether another VPN is active.
- Review the app's VPN-conflict prompt.
- Confirm Android VPN consent was granted.
- Check network connectivity and encrypted DNS availability.

## Generated files excluded from Git

The `.gitignore` excludes Flutter/Gradle build output, `.dart_tool`, plugin metadata, Android SDK paths, IDE files, logs, and all common signing-key formats. A fresh clone recreates generated files with `flutter pub get`, Gradle sync, tests, or a build.

## License

No explicit open-source license is included yet. The repository is publicly readable, but public visibility alone does not grant broad reuse or redistribution rights. Add an intentional license before inviting external reuse or contributions.

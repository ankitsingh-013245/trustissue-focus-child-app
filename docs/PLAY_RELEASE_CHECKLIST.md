# Play release checklist

## Product and artifact

- Confirm the AAB contains only the local-first focus app.
- Confirm `INTERNET`, `ACCESS_NETWORK_STATE`, `FOREGROUND_SERVICE`, and
  `FOREGROUND_SERVICE_SYSTEM_EXEMPTED` support the user-consented Focus and
  Global Web Protection VPN.
- Confirm `FOREGROUND_SERVICE_SPECIAL_USE`, `PACKAGE_USAGE_STATS`,
  `SYSTEM_ALERT_WINDOW`, `POST_NOTIFICATIONS`, `ACCESS_NOTIFICATION_POLICY`,
  and `RECEIVE_BOOT_COMPLETED` match the compatible protection, daily-limit,
  Strict Focus, notification, and restore flows described below.
- Confirm no Camera, Notification Listener, exact-alarm, location, contacts,
  microphone, or storage permission is merged.
- Confirm the non-exported `VpnService` is protected by
  `android.permission.BIND_VPN_SERVICE`, declares `systemExempted`, and does
  not support always-on mode.
- Confirm the non-exported compatible monitor declares `specialUse` and its
  package-transition-only purpose.
- Confirm `android:usesCleartextTraffic` is false.
- Confirm cloud-backup and device-transfer rules exclude all private app data.
- Confirm release uses the Play upload key, never the debug key.
- Confirm target and compile SDK 36.
- Run R8/resource shrinking verification on the signed release candidate.

## Compatible protection declarations

- Explain that Usage Access supplies package-level usage transitions and daily
  foreground totals; screen content is not read.
- Explain that Display Over Other Apps presents the user-configured block
  screen and local Focus controls.
- Show the in-app disclosures immediately before opening each Android setting.
- Show that notification permission controls notification visibility only and
  that the app does not request Notification Listener access.
- Demonstrate daily-limit enforcement while the limited app remains in the
  foreground after crossing its limit.

## Optional Accessibility declaration

- Set `isAccessibilityTool` to false.
- Select App functionality as the purpose.
- Explain that foreground package changes are used for user-created focus
  rules and contextual, session-only study-tool handoffs.
- Explain separately that, only when YouTube Shorts or Instagram Reels Blocking
  is enabled, visible accessibility labels and view identifiers from that app
  are inspected in memory to detect its short-form player and a swipe. State
  that these labels are not stored or sent and that the service does not click,
  type, or interact with content controls. Disclose that the explicit Leave
  action sends Android Back, with Home as a safety fallback.
- State that screenshots, document content, notifications, passwords, and
  messages are not read.
- Upload a reviewer video showing:
  1. permission-free onboarding;
  2. opening the optional Accessibility row in Settings;
  3. the in-app prominent disclosure;
  4. the Android setting;
  5. creating and starting a focus rule;
  6. a PDF-reader study-tool handoff and its timer behavior;
  7. a blocked app;
  8. enabling YouTube Shorts Blocking through its separate disclosure;
  9. opening a Short, choosing `Watch only this`, and showing that the gate
     returns after a swipe to the next Short;
  10. normal YouTube videos and the home-feed Shorts tab not being blocked;
  11. enabling Instagram Reels Blocking through its separate disclosure;
  12. opening a Reel, choosing `Watch only this`, and showing that the gate
      returns after a swipe to the next Reel;
  13. Instagram Home, Explore, posts, Stories, and messages not being blocked;
  14. revoking access and the unavailable feature state.

## VPN Service declaration

- Declare the user-facing focus/browser-filtering purpose in Play Console.
- Show the separate in-app Web Protection disclosure immediately before the
  Android VPN consent screen.
- State that Focus-only VPN protection runs when Focus allows at least one
  browser and stops when Focus ends. State separately that Global protection
  includes all installed browsers and remains active outside Focus until the
  user switches it off. Neither mode decrypts normal web traffic.
- Disclose that DNS hostnames are sent over encrypted DNS to Cloudflare's
  family resolver for adult/malware filtering, with CleanBrowsing Family used
  only as a failure fallback.
- Upload a reviewer video showing:
  1. all browsers blocked and no VPN running;
  2. allowing a browser;
  3. the prominent Web Protection disclosure;
  4. Android VPN consent;
  5. the foreground VPN notification;
  6. a blocked app website and custom domain;
  7. VPN denial/revocation causing browser-only fail-safe blocking;
  8. Focus ending and the Focus-only VPN stopping;
  9. Global protection remaining active after Focus, then stopping when its
     toggle is switched off.

## Store listing and review

- Do not claim unbreakable blocking or uninstall prevention.
- Do not use unverified user-count or security claims.
- Describe Normal and Strict behavior accurately.
- Provide reviewer steps that require no login.
- Publish the privacy policy at a stable public HTTPS URL and link it in the
  listing.
- Complete Data Safety from the final merged artifact, not from assumptions.

## QA gate

- Policy-engine unit tests.
- Flutter widget/state tests.
- Android instrumentation test for Accessibility enable/disable recovery.
- YouTube app-version regression fixtures for Shorts detection, false-positive
  checks on Home/search/normal videos, one-Short pass expiry, scroll detection,
  and service/process restart.
- Instagram app-version regression fixtures for Reels detection, false-positive
  checks on Home/Explore/posts/Stories/messages, one-Reel pass expiry, scroll
  detection, and service/process restart.
- Direct PDF handoff, Android chooser, system picker, 2-minute expiry, session
  PDF promotion, Home cancellation, and no-chaining checks.
- Browser allowed/blocked policy changes, custom-domain locking, DNS failure,
  Wi-Fi/mobile switching, captive portal, sleep/wake, another-VPN conflict,
  browser install/removal during Focus, permission revocation, and session-end
  VPN shutdown checks.
- Clock-change, process-death, reboot/unlock, and break-expiry checks.
- Physical and gesture Back checks on every Focus, study-tool, and web-block
  gate, including predictive Back on Android 16.
- App-limit threshold crossing without switching away from the limited app.
- App Study sessions spanning local midnight and daylight-saving transitions.
- Android 10 through Android 16.
- Samsung, Pixel, Xiaomi/Redmi, Oppo/Realme, Vivo, and Motorola smoke tests.
- Internal testing, then closed testing, then staged production rollout.

# TrustIssue Focus privacy policy draft

Last updated: 2026-07-24

Replace the contact and public policy URL placeholders before release.

## Summary

TrustIssue Focus is designed to work locally on the user's Android device. The
app does not require an account and does not send focus rules, app activity,
reports, messages, notification content, or diagnostic logs to a TrustIssue
server.

## Accessibility

The user may optionally enable the Android Accessibility service for faster
advanced protection. For Focus, the service observes foreground application
package changes so it can apply focus rules selected by the user. When a
blocked app is opened, the service moves it out of the foreground, sends a
media-pause action, and presents the Focus Block screen. During App Study, it
also displays a local floating timer and icon controls over a study app selected
by the user. If that primary study app opens a PDF reader, Gallery, or an
Android system picker, package transitions and Android intent-handler
information are used to offer limited study-tool access. Quick access lasts up
to two minutes and pauses the study timer. A verified PDF reader can instead be
allowed only for the current Focus session, where its active time counts toward
the study timer. Tool access cannot authorize another app and is cleared when
the session ends.

The user can separately enable YouTube Shorts Blocking and Instagram Reels
Blocking. Only while one of those features is on and its app is visible, the
service retrieves that app's visible Accessibility node tree in memory to
identify the short-form player and a swipe to the next item. The app does not
persist or transmit the retrieved labels,
titles, creator names, or view identifiers. It stores only the feature setting
and, after the user chooses `Watch only this`, an opaque fingerprint with a
maximum 15-minute lifetime. The pass is cleared when the user scrolls to
another short-form item, leaves the relevant app, turns the screen off,
disables the feature, or the service stops.

The service does not click, type, or interact with content controls in another
app, capture screenshots, read document content, notifications, passwords,
messages, or form values, or submit forms. The block screen's explicit Leave
action can send Android Back, with Home as a safety fallback.

The user can decline this access. Compatible Focus and Daily App Limit
protection remain available; features marked as requiring Accessibility,
including YouTube Shorts and Instagram Reels Blocking, remain unavailable. The
user can revoke the access at any time in Android Settings.

## Focus and Global Web Protection

Focus Web Protection is conditional. It does not run when the user's Focus
rules block every installed browser. If at least one browser is allowed, the
app presents a separate prominent disclosure and asks for Android VPN consent.
Once consent is granted, Focus-only protection starts automatically with Focus
and stops when the Focus session ends. Denying or revoking VPN consent causes
browser access to be blocked for safety; non-browser study apps and the Focus
timer can continue.

The user can separately enable Global Website Protection. In Global mode, all
installed browsers join the local VPN and protection continues outside Focus
until the user switches Global Website Protection off. If Focus starts while
Global mode is enabled, the active Focus rules are added; ending Focus removes
those Focus rules but does not turn off Global protection. The app explains
this duration before requesting VPN consent, and a materially updated
disclosure must be accepted again.

The VPN is a split-tunnel DNS filter. In Focus-only mode, browser packages
allowed by the active Focus policy join it; in Global mode, all installed
browsers join it. Their DNS requests are routed through encrypted DNS to
Cloudflare's family resolver for adult-content and malware filtering, with
CleanBrowsing's family resolver used only if the primary resolver fails. The
requested website hostname and ordinary network metadata are therefore
processed by the resolver used for that request under its applicable privacy
terms. TrustIssue does not attach an account identifier, focus report,
selected-app list, or advertising identifier to those requests.

Blocked-app website rules, bypass-domain rules, and domains added by the user
are checked locally before a DNS request is sent. Normal web traffic remains in
the browser. TrustIssue does not decrypt HTTPS traffic or read page content,
passwords, form values, search text, or browser history. DNS responses may be
cached in memory for up to 30 seconds for performance; that cache is not
persisted.

## Data stored on the device

The app stores selected focus rules, session-scoped study-tool package choices,
custom blocked domains, Web Protection consent state, session timing,
blocked-attempt counts, break activity, and daily focus summaries locally on
the device. It also stores whether YouTube Shorts or Instagram Reels Blocking
is enabled and a short-lived, platform-isolated opaque one-item pass when
requested. Temporary study-tool choices are cleared when the Focus session
ends. The Settings screen lets the user delete local reports. Uninstalling the
app also removes application data according to Android behavior.

## Diagnostics

The user may manually export a bounded diagnostic text file. It contains
protection event codes and package-level decisions, not screen text, messages,
notification content, passwords, or form values. The file is not uploaded by
the app.

## Sharing and sale

TrustIssue Focus does not sell personal data and does not share focus activity
with parents, advertisers, data brokers, or a TrustIssue backend. When Focus
Web Protection is active, DNS hostnames are sent to Cloudflare's family
resolver, or to the failure-only CleanBrowsing fallback, only for the filtering
purpose described above.

## Security

The app uses Android private application storage, excludes its files,
databases, preferences, and reports from Android cloud backup and device
transfer, and disables cleartext network traffic. No security method can
guarantee absolute protection.

## Contact

Privacy contact: REPLACE_WITH_SUPPORT_EMAIL

Public policy URL: REPLACE_WITH_PUBLIC_HTTPS_URL

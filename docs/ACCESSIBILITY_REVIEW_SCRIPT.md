# Accessibility reviewer script

## Prominent disclosure shown in app

TrustIssue uses Android Accessibility only to identify the foreground app, move
a blocked app out of view, pause active media, and show the Focus Block screen
using the rules you choose. During App Study it also displays a floating timer
and icon controls over the selected study app. When that study app opens a PDF
reader, Gallery, or system picker, foreground package transitions are used to
offer short, session-only study-tool access.

If the user separately enables YouTube Shorts or Instagram Reels Blocking,
visible Accessibility labels and view identifiers from that app are checked in
memory only while that app is visible. They are used to identify the short-form
player and a swipe to the next item. The labels are not stored or sent, and
TrustIssue never clicks, types, or interacts with content controls in YouTube
or Instagram. The explicit Leave action can send Android Back, with Home as a
safety fallback.

It does not capture screenshots or read document content, notifications,
passwords, or messages. Focus history stays on this phone. You can decline or
turn the access off at any time in Android Settings.

Buttons: `Not now` and `Continue to Settings`.

## Reviewer steps

1. Open the app and complete the permission-free welcome screen.
2. Choose Allowlist or Blocklist, select at least one app, and set a duration.
3. Press Start Focus.
4. Review the prominent disclosure and choose Continue to Settings.
5. Enable TrustIssue Focus protection in Android Accessibility Settings.
6. Return to the app and press Start Focus again.
7. Choose App Study, open a selected study app, and observe the floating timer.
   Leave the study app and confirm the remaining time pauses.
8. Tap the timer, review the icon controls, and let it collapse and auto-hide.
9. From the selected study app, open a PDF in an installed reader. Observe the
   Study Tool Handoff card. Confirm 2-minute access pauses the main timer, while
   enabling Allow as Study App counts time and lasts only for this session.
10. From the PDF reader, try to open another unselected app and confirm it
    cannot create a second handoff.
11. Open an app covered by the chosen rule and observe the block explanation.
12. Start audio or video in a blocked app and observe that playback pauses when
   the Focus Block screen appears.
13. Revoke Accessibility and return to observe Protection degraded.
14. In Blocks, enable YouTube Shorts Blocking and accept its separate prominent
    disclosure.
15. Open a YouTube Short and observe the gate. Choose `Watch only this`, then
    swipe to the next Short and confirm the gate returns.
16. In Blocks, enable Instagram Reels Blocking and accept its separate prominent
    disclosure.
17. Open an Instagram Reel and observe the gate. Choose `Watch only this`, then
    swipe to the next Reel and confirm the gate returns.
18. Open Instagram Home, Explore, a post, Stories, and messages and confirm that
    the Reels gate does not appear.
19. Open a normal YouTube video and the YouTube home screen and confirm neither
    is blocked merely because the Shorts navigation tab is visible.

No account, invitation code, parent dashboard, or external test server is
required.

Web Protection uses a separate prominent disclosure and Android VPN consent.
It is not presented as an Accessibility capability.

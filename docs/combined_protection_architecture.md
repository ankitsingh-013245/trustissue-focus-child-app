# Combined Protection architecture

## Current production boundary

Android permits one active `VpnService` per user/profile. TrustIssue therefore
never starts its local DNS VPN over another VPN without an explicit user
choice. The supported conflict outcomes are:

- keep the existing VPN and pause TrustIssue website filtering for that Focus
  session;
- switch to TrustIssue Website Protection through Android's consent screen;
- cancel without changing either VPN or the saved Focus app selection.

If the kept VPN disappears during Focus, browsers fail closed until protection
is reviewed. Other allowed apps remain usable.

## Full combined tunnel

A true combined tunnel must use one TrustIssue-owned TUN and this packet path:

`Android TUN -> local DNS/rule classifier -> encrypted provider tunnel -> Internet`

The provider stage must be implemented by a maintained WireGuard/OpenVPN
userspace backend. It must not be simulated by starting a second VPN service or
by routing unsupported resolver IP traffic into the DNS-only packet loop.

Activation requirements:

1. Import and validate an exportable provider configuration.
2. Store private keys using Android Keystore-backed encryption.
3. Protect the provider socket from the TrustIssue TUN to prevent routing loops.
4. Forward IPv4/IPv6 and TCP/UDP while intercepting only local DNS policy
   traffic.
5. Pass network-switch, kill-switch, DNS-leak and provider-reconnect device
   tests.
6. Keep the combined option capability-gated until all checks pass.

Arbitrary installed VPN apps cannot be chained because Android does not expose
their tunnel configuration or packet transport to another app.

<!-- Use this as README.md in the public open-source repo. -->

# KAPO VPN (Android)

**An anonymous, no-logs VPN client. No email. No account. Just a code.**

KAPO is a privacy-first VPN built on [AmneziaWG](https://github.com/amnezia-vpn)
(an obfuscated WireGuard fork). You buy an anonymous access code with crypto at
[kapovpn.com](https://kapovpn.com), type it into the app, and connect — there is
no sign-up, name, or email. Your WireGuard keys are generated **on your device**
and never leave it.

This repository is the **client app**. It's open source so you can verify
exactly what runs on your device.

## Features
- **Anonymous** — an access code is the only credential; no account, no email.
- **On-device keys** — your private key is generated locally and never sent.
- **Multi-hop** — route through 2 or 3 countries.
- **Obfuscated** — AmneziaWG disguises traffic where ordinary VPNs get blocked.
- **No activity logs** — the service keeps operational state in RAM.
- **Ad & tracker blocking**, up to 5 devices per code.

## Install
- **Direct APK:** https://kapovpn.com/#download — each release lists its SHA-256.
- **Google Play / F-Droid:** coming soon.
- **F-Droid / IzzyOnDroid:** (see the download page on kapovpn.com)
- **Aurora Store:** search "KAPO VPN" (mirrors Google Play, no Google account)
- **Direct APK:** https://kapovpn.com/#download — each release lists its SHA-256
  so you can verify the download.

## Build from source
Requirements: JDK 17+, Android SDK, NDK r27+.

```bash
git clone https://github.com/kapovpn/kapo-vpn-android.git
cd kapovpn-android
cp gradle.properties.example gradle.properties   # fill in signing values for a release build
./gradlew :ui:assembleRelease                    # or assembleDebug for an unsigned build
```
The APK lands in `ui/build/outputs/apk/`.

> A signed **release** build needs your own keystore (see
> `gradle.properties.example`). A **debug** build needs no signing values.

## What the app talks to
The client contacts a single control-plane host (`api.kapovpn.com`) to validate
a code and register this device's **public** key as a WireGuard peer. It sends
only: the anonymous code, the device public key, and a device id. Nothing
personal. The server infrastructure is operated separately.

## Security
See [SECURITY.md](SECURITY.md). Report issues to abuse@kapovpn.com.

## License
Apache-2.0. This project is a fork of WireGuard/AmneziaWG for Android; all source
files carry the Apache-2.0 SPDX header. See [COPYING](COPYING).

WireGuard is a registered trademark of Jason A. Donenfeld. KAPO VPN is not
sponsored or endorsed by the WireGuard or Amnezia projects.

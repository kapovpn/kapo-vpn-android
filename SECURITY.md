# Security Policy

KAPO VPN is an anonymous, no-logs VPN client. We take security and privacy
reports seriously.

## Reporting a vulnerability

Please email **abuse@kapovpn.com** (or kapovpnapp@proton.me) with:
- a description of the issue and its impact,
- steps to reproduce or a proof of concept,
- the app version and device/OS.

Please give us a reasonable window to fix the issue before public disclosure.
We do not run a paid bug bounty at this time, but we credit reporters who wish
to be named.

## Scope

This repository is the **client application**. It:
- generates its WireGuard keypair on-device; the private key never leaves the device,
- sends only an anonymous account code, the device's public key, and a device id
  to the control plane,
- keeps no activity logs.

The control-plane/server infrastructure is operated separately and is not part
of this repository.

## Building & verifying

Build instructions are in the README. Release builds published by us are signed;
the download page at kapovpn.com lists the SHA-256 checksums of official APKs so
you can verify a download matches our build.

# Changelog

## 1.0.5

- Fix: reproducible build — convert scope.list line endings to LF so F-Droid verification passes.

## 1.0.4

- Multiple network profiles: create, name, edit and delete as many profiles as you need.
- Randomize on launch: optionally pick a different profile each time an app starts.
- Randomize button in the profile editor: fills all Wi-Fi and mobile fields with plausible random values in one tap.
- Save current network: capture your real Wi-Fi or mobile state directly into a new profile.

## 1.0.3

- Build: updated the Android Gradle Plugin to 9.1.1. No behaviour changes.

## 1.0.2

First public release.

- Per-app Wi-Fi, Data, Both or Off, applied live with no reboot.
- System framework layer (connectivity, Wi-Fi and phone services) gated by caller uid.
- Inside-the-app layer for socket addresses, network interfaces, scan results and settings.
- Coverage switch: System, Apps or Both.
- One editable network profile so every answer stays consistent.
- Stand-in cellular network in Both mode.
- Android 9 to 17 supported. Android 10 to 17 verified. Android 9 is unverified.
- Reproducible build for F-Droid: no dependency metadata block or VCS info in the APK, LF line endings, pinned Gradle wrapper checksum.

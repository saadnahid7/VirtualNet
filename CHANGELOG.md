# Changelog

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

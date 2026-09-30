# VirtualNet

Per-app network spoofing for LSPosed / Vector. Pick an app and choose what it is told about your connection:
**Wi-Fi**, **mobile data**, **both**, or **off**. A phone that is only on mobile data can look like it is on Wi-Fi to one app,
and the reverse, while every other app sees the truth.

- Android 10 to 17 (API 29 to 37) tested, libxposed API 102 (LSPosed 1.10+, Vector). Android 9: see Limits.
- About 120 KB release APK. No AppCompat, no Material, no network permission.
- Developed by the Droid Rooter Team. MIT licensed.

## How it works

Two layers, both driven by the same per-app setting:

1. **System framework (recommended).** Hooks inside `system_server` (ConnectivityService, WifiService) and `com.android.phone` rewrite the answers for the apps you picked, matched by caller uid. Nothing per app needs to be scoped, and callbacks are covered at the source.
2. **Inside the app (optional).** Scoping an app in Vector or LSPosed adds the checks that never reach the system: socket local address, `NetworkInterface`, Wi-Fi scan results and `Settings.Global`.

| Area | System framework | Scoped app |
|---|---|---|
| Network capabilities, link properties, legacy `NetworkInfo`, network list, callbacks | yes | yes |
| `WifiManager` info, DHCP, enabled state | yes | yes |
| `TelephonyManager` network type, data state | yes (phone) | yes |
| Stand-in cellular network in Both mode | yes | yes |
| Socket local address, `NetworkInterface`, scan results, `Settings.Global` | no | yes |

One editable profile (SSID, router MAC, local IP and prefix, gateway, DNS, signal, link speed, frequency, mobile type, mobile interface and IP) keeps every answer consistent, so an app that checks the IP range sees the range you chose.

## Modes

| Mode | App sees |
|---|---|
| Wi-Fi | Wi-Fi connected, not metered, mobile data disconnected |
| Data | Mobile data connected and metered, Wi-Fi off and disconnected |
| Both | Wi-Fi is the default network, mobile data also connected |
| Off | Nothing changed |

Changes take effect without a reboot. An app that already read the state keeps what it read until it asks again or restarts.

## Install

1. Install `VirtualNet` from Releases and enable it in Vector or LSPosed.
2. Add **System Framework** and **Phone Services** to its scope (the app offers this on first launch), then restart the device or its framework once.
3. Open VirtualNet and pick a mode for an app. No restart is needed. For socket and interface checks, also scope that app.

## Lab

`lab/` is a small detector app (`VirtualNet Lab`). It reads the connection through every public route above, prints a verdict
(Wi-Fi, mobile, both) and logs one line per probe under the tag `VNL`. Scope it in VirtualNet like any other app to verify a change.
`scripts/deploy.sh <serial>` installs both apps on a rooted device or emulator and `scripts/lab-run.sh <serial> <mode>` sets a mode and prints what the Lab saw (debug builds only).

## Limits

- Anything that bypasses the Java framework sees the real network: raw sockets, native code, server-side IP checks. `/proc/net/*` is blocked for normal apps on Android 10+ but readable for apps targeting old SDKs, and is not spoofed.
- Apps that verify with latency, public IP or carrier attestation can still tell.
- `Both` mode adds a stand-in cellular `Network` (id 9998). Callbacks registered for a cellular request do not fire for it.
- System-wide indicators (status bar, quick settings) are unchanged.
- **Android 9 (API 28):** untested. On the Vector 2.2 lab image the framework could not inject its service into `system_server` or hand it to the module app, so no module can read settings there. This is a framework limit, not something VirtualNet can work around.
- A change in system hooks needs the framework restarted once after installing or updating the module.

## Build

```
./gradlew :app:assembleRelease :lab:assembleRelease
```

Needs JDK 17+ and the Android SDK with platform 37.

See `docs/TESTING.md` for the device matrix.

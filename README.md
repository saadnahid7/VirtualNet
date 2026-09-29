# VirtualNet

Per-app network spoofing for LSPosed / Vector. Pick an app and choose what it is told about your connection:
**Wi-Fi**, **mobile data**, **both**, or **off**. A phone that is only on mobile data can look like it is on Wi-Fi to one app,
and the reverse, while every other app sees the truth.

- Android 9 to 17 (API 28 to 37), libxposed API 102 (LSPosed 1.10+, Vector).
- About 100 KB release APK. No AppCompat, no Material, no network permission.
- Developed by the Droid Rooter Team. MIT licensed.

## What it changes

Real traffic is untouched. VirtualNet only changes the answers apps get when they ask about the connection.

| Area | Hooked |
|---|---|
| `ConnectivityManager` | `getActiveNetworkInfo`, `getNetworkInfo`, `getAllNetworkInfo`, `getNetworkCapabilities`, `getLinkProperties`, `isActiveNetworkMetered` |
| Network callbacks | Every `NetworkCallback` event (capabilities and link properties) via `CallbackHandler`; transport and NOT_METERED requirements in `registerNetworkCallback` / `requestNetwork` are relaxed so a Wi-Fi request still matches |
| `WifiManager` | `isWifiEnabled`, `getWifiState`, `getConnectionInfo` (SSID, BSSID, IP, RSSI, speed, frequency), `getDhcpInfo` |
| `TelephonyManager` | network type, data network type, data state, `isDataEnabled` |
| `java.net.NetworkInterface` | interface list, names, IPv4 address |
| `Settings.Global` | `wifi_on`, `mobile_data` |

One editable profile (SSID, router MAC, local IP and prefix, gateway, DNS, signal, link speed, frequency, mobile type,
mobile interface and IP) keeps every answer consistent, so an app that checks the IP range sees the range you chose.

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
2. Open VirtualNet, pick a mode for an app. It asks the framework to add the app to scope (or add it yourself).
3. Restart the target app once.

## Lab

`lab/` is a small detector app (`VirtualNet Lab`). It reads the connection through every public route above, prints a verdict
(Wi-Fi, mobile, both) and logs one line per probe under the tag `VNL`. Scope it in VirtualNet like any other app to verify a change.
`scripts/deploy.sh <serial>` installs both apps on a rooted device or emulator and `scripts/lab-run.sh <serial> <mode>` sets a mode and prints what the Lab saw (debug builds only).

## Limits

- Anything that bypasses the Java framework sees the real network: raw sockets, `/proc/net/*`, native code, server-side IP checks.
- Apps that verify with latency, public IP or carrier attestation can still tell.
- `Both` mode fabricates the second network only in the legacy and telephony answers; `getAllNetworks()` still lists real networks.
- System-wide indicators (status bar, quick settings) are unchanged.

## Build

```
./gradlew :app:assembleRelease :lab:assembleRelease
```

Needs JDK 17+ and the Android SDK with platform 37.

## Verified on

| Device | Android | Result |
|---|---|---|
| Emulator (Vector 2.2) | 10 (API 29) | Wi-Fi, Data, Both, Off all consistent |
| Poco X3 Pro (LineageOS, Magisk + Vector 2.2) | 15 (API 35) | Wi-Fi-only phone shown as mobile data, and mobile shown as Wi-Fi |

See `docs/TESTING.md` for the matrix as it grows.

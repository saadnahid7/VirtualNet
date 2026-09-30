# Architecture

VirtualNet changes what an app is told about its connection. It never touches real traffic.

## Settings

The settings app writes one set of keys (`config/Config.kt`): a mode per package, one network profile, and the coverage switch.
The keys live in local preferences and are mirrored into the framework's remote preferences (`config/Store.kt`), which every hooked process reads.
A change therefore reaches a running app without a reboot.

## Two layers

| Layer | Where | Selected by |
|---|---|---|
| System | `system_server` (ConnectivityService, WifiService) and `com.android.phone` | Coverage System or Both |
| Apps | Inside each scoped app | Coverage Apps or Both |

Both layers run the same shaping code (`hooks/common/Shape.kt`), so their answers agree. Running both is safe because shaping is idempotent.

### System layer

`hooks/system/SystemHooks.kt` attaches when the connectivity and Wi-Fi services are published, so it does not depend on a class name, package relocation or Android version. It rewrites results only for callers whose uid has a mode (`SysState`). System and root callers are never touched.

The capabilities chokepoint is found by name at runtime because it changed across releases:

| Android | Method |
|---|---|
| 10 to 16 | `networkCapabilitiesRestrictedForCallerPermissions` |
| 17 | `createWithSensitiveInfoSanitizedIfNecessaryWhenParceled` |

The service's own capability objects are copied before editing.

### Phone layer

`hooks/system/PhoneHooks.kt` runs in `com.android.phone` and answers the telephony calls (network type, data state, data enabled) by caller uid.

### App layer

`hooks/app/AppHooks.kt` covers what never reaches the system: the socket local address (`IoBridge`), `NetworkInterface`, Wi-Fi scan results and `Settings.Global`. It also rewrites callback delivery and relaxes network requests inside the app.

## Failure handling

Every hook runs the real method first, rewrites inside a `try`, and returns the real result on any error. A failure is logged once. Reflection misses are expected on some releases and are reported once.

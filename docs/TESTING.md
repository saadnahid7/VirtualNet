# Testing

Each row is one device, one Android build, one framework version. A result applies only to that combination.

`scripts/matrix.py <api> ...` boots the rooted AVD, installs both apps, scopes only System Framework and Phone Services, restarts zygote, then checks Wi-Fi, Data, Both and Off with the Lab app **not** in scope. `scripts/deploy.sh` and `scripts/lab-run.sh` do the same by hand on a device.

Pass means the Lab agrees with the mode on: legacy network info, capabilities and transport, metered flag, `WifiManager` state and IP, telephony type (Data, Both), and the latest default-network callback. Off must leak nothing.

| Device | API | Framework | wifi | data | both | off |
|---|---|---|---|---|---|---|
| Emulator google_apis x86_64 | 28 | Vector 2.2 debug | n/a | n/a | n/a | n/a |
| Emulator google_apis x86_64 | 29 | Vector 2.2 debug | pass | pass | pass | pass |
| Emulator google_apis x86_64 | 30 | Vector 2.2 debug | pass | pass | pass | pass |
| Emulator google_apis x86_64 | 31 | Vector 2.2 debug | pass | pass | pass | pass |
| Emulator google_apis x86_64 | 33 | Vector 2.2 debug | pass | pass | pass | pass |
| Emulator google_apis x86_64 | 34 | Vector 2.2 debug | pass | pass | pass | pass |
| Emulator google_apis x86_64 | 35 | Vector 2.2 debug | pass | pass | pass | pass |
| Emulator google_apis x86_64 | 36 | Vector 2.2 debug | pass | pass | pass | pass |
| Emulator google_apis x86_64 | 37 | Vector 2.2 debug | pass | pass | pass | pass |
| Poco X3 Pro, LineageOS | 35 | Vector 2.2 | pass | pass | pass | pass |

API 28: on this image the framework logs `Failed to inject VectorService into system_server` and `NoSuchMethodError getContentProviderExternal`. The module class loads but `onSystemServerStarting` is never called and the module app never receives the service, so nothing can be tested. Not a VirtualNet result.

Not covered by the system layer (need the app in scope): socket local address, `NetworkInterface`, scan results, `Settings.Global`. Those were verified earlier with app scope on API 35 (Poco X3 Pro).

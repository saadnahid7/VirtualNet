# Testing

Each row is one device, one Android build, one framework version. A result applies only to that combination.

Run: `scripts/deploy.sh <serial>` then `scripts/lab-run.sh <serial> wifi|data|both|off`.
Pass means every "says" tag in the Lab agrees with the mode (Wi-Fi mode: no CELL tag except telephony; Data mode: no WIFI tag).

| Device | API | Framework | wifi | data | both | off |
|---|---|---|---|---|---|---|
| Emulator google_apis x86_64 | 29 | Vector 2.2 | pass | pass | pass | pass |
| Poco X3 Pro, LineageOS | 35 | Vector 2.2 | pass | pass | pass | pass |

After the socket, stand-in network, interface-address and scan-result hooks (commit `gap-closing`): Poco X3 Pro API 35 passed wifi, both and data; TCP/UDP local address, `getInterfaceAddresses`, scan results and `getAllNetworks` (2 entries in both) all matched the mode.

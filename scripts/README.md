# Scripts

Test harness for rooted emulators and devices. These are development tools, not part of the shipped app.

| Script | What it does |
|---|---|
| `matrix.py <api> ...` | Boots each rooted AVD, installs both debug apps, scopes System Framework and Phone Services, restarts the framework, then checks Wi-Fi, Data, Both and Off with the Lab app left out of scope. A second pass scopes only the Lab and tests the Apps layer. |
| `prep.py <api>` | Boots one AVD and sets it up for manual testing. |
| `deploy.sh <serial>` | Installs both apps on a device and enables the module. |
| `lab-run.sh <serial> <mode>` | Sets a mode for the Lab app (debug builds only), runs it and prints what it saw. |

Environment:

- `VNET_ADB` path to `adb` (default: the Android SDK on this author's machine)
- `VNET_LAB_REPO` folder that holds `scripts/start-root-emulator.ps1` and the rooted AVDs

The mode-change hook (`DebugReceiver`) exists only in debug builds.

#!/usr/bin/env bash
# Usage: scripts/deploy.sh <serial>  -- installs app + lab, refreshes the Vector registration, scopes the lab.
A=${ADB:-/d/AndroidDev/Sdk/platform-tools/adb.exe}; S="-s $1"; C=/data/adb/modules/zygisk_vector/cli
cd "$(dirname "$0")/.."
$A $S install -r -g app/build/outputs/apk/debug/app-debug.apk | tail -1
$A $S install -r -g lab/build/outputs/apk/debug/lab-debug.apk | tail -1
$A $S shell pm grant com.droidrooter.virtualnet.lab android.permission.READ_PHONE_STATE
$A $S shell pm grant com.droidrooter.virtualnet.lab android.permission.ACCESS_FINE_LOCATION
$A $S shell "su -c '$C modules disable com.droidrooter.virtualnet >/dev/null; $C modules enable com.droidrooter.virtualnet; $C scope set com.droidrooter.virtualnet com.droidrooter.virtualnet.lab/0'" | tail -2
$A $S shell am force-stop com.droidrooter.virtualnet
$A $S shell am start -n com.droidrooter.virtualnet/.ui.MainActivity >/dev/null
sleep 3

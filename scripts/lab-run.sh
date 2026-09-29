#!/usr/bin/env bash
# Usage: scripts/lab-run.sh <serial> <mode: off|wifi|data|both> [wait-seconds]
# Sets the VirtualNet mode for the Lab app, restarts it, and prints what the Lab saw.
A=${ADB:-/d/AndroidDev/Sdk/platform-tools/adb.exe}
S="-s $1"; MODE=$2; WAIT=${3:-6}
$A $S logcat -c
$A $S shell am broadcast -n dev.virtualnet/.DebugReceiver --es pkg dev.virtualnet.lab --es mode "$MODE" >/dev/null
for i in $(seq 1 20); do
  $A $S logcat -d -s VirtualNet | grep -q "debug set dev.virtualnet.lab" && break
  sleep 0.5
done
sleep 2   # let the framework push the new value to remote preferences
$A $S shell am force-stop dev.virtualnet.lab
$A $S logcat -c
$A $S shell am start -n dev.virtualnet.lab/.LabActivity --ez run true >/dev/null
sleep "$WAIT"
$A $S logcat -d -s VNL VirtualNet | sed 's/^.*\(VNL\|VirtualNet\) *: //' | grep -v "^---"

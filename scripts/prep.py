#!/usr/bin/env python3
"""Boot one rooted emulator and set it up for manual poking: scripts/prep.py <api>"""
import sys, time
sys.path.insert(0, __file__.rsplit("/", 1)[0].rsplit("\\", 1)[0])
import matrix as M

api = int(sys.argv[1])
M.start(api)
assert M.wait_boot(), "boot timeout"
M.go_root()
for apk in (M.APP, M.LAB):
    M.adb("install", "-r", "-g", apk, timeout=180)
M.su(f"{M.CLI} modules disable com.droidrooter.virtualnet; {M.CLI} modules enable com.droidrooter.virtualnet; {M.CLI} scope set com.droidrooter.virtualnet system/0 com.android.phone/0")
M.su("setprop ctl.restart zygote")
time.sleep(20)
assert M.wait_boot(), "boot timeout 2"
M.go_root()
M.sh("monkey -p com.droidrooter.virtualnet -c android.intent.category.LAUNCHER 1")
time.sleep(6)
print("ready")

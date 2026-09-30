#!/usr/bin/env python3
"""Rooted-emulator test matrix. Usage: scripts/matrix.py <api> [<api> ...]

For each API level: boot the rooted AVD, install VirtualNet + Lab, scope only the system framework
(and com.android.phone), restart zygote, then check wifi / data / both / off with the Lab left UNscoped.
Results are appended to .local/matrix-results.jsonl.
"""
import json, os, re, subprocess, sys, time

ADB = os.environ.get("VNET_ADB", "D:/AndroidDev/Sdk/platform-tools/adb.exe")
LAB_REPO = "D:/Documents/ChatGPT/Flutter_Lsposed"
ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
SER = "emulator-5558"
CLI = "/data/adb/modules/zygisk_vector/cli"
APP = os.path.join(ROOT, "app/build/outputs/apk/debug/app-debug.apk")
LAB = os.path.join(ROOT, "lab/build/outputs/apk/debug/lab-debug.apk")

def adb(*a, timeout=120, check=False):
    r = subprocess.run([ADB, "-s", SER, *a], capture_output=True, text=True, timeout=timeout)
    return (r.stdout or "").replace("\r", "")

def sh(cmd, timeout=120):
    return adb("shell", cmd, timeout=timeout)

def su(cmd, timeout=120):
    # adbd runs as root after `adb root`, so no su wrapper (and no Magisk prompt) is involved.
    return sh(cmd, timeout=timeout)

def step(msg):
    print(time.strftime("%H:%M:%S"), msg, flush=True)

def go_root():
    subprocess.run([ADB, "-s", SER, "root"], capture_output=True, text=True, timeout=60)
    time.sleep(3)
    subprocess.run([ADB, "-s", SER, "wait-for-device"], capture_output=True, text=True, timeout=120)
    time.sleep(2)

def qemu_running():
    r = subprocess.run(["tasklist"], capture_output=True, text=True).stdout
    return "qemu-system-x86_64" in r

def start(api):
    while qemu_running():
        time.sleep(2)
    args = ["powershell", "-NoProfile", "-File", "scripts/start-root-emulator.ps1", "-Api", str(api)]
    if api != 28:
        args.append("-Patched")
    # DEVNULL, not pipes: the emulator inherits the handles and would keep a pipe open until it exits.
    subprocess.run(args, cwd=LAB_REPO, stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL, timeout=120)
    step(f"emulator {api} launched")

def wait_boot(limit=420):
    t0 = time.time()
    while time.time() - t0 < limit:
        if "device" in subprocess.run([ADB, "-s", SER, "get-state"], capture_output=True, text=True).stdout:
            if sh("getprop sys.boot_completed").strip() == "1" and "found" in sh("service check package"):
                time.sleep(8)
                return True
        time.sleep(4)
    return False

def kill():
    try:
        sh("sync"); adb("emu", "kill", timeout=30)
    except Exception:
        pass
    for _ in range(60):
        if not qemu_running():
            break
        time.sleep(2)
    time.sleep(3)

def lab_run(mode, cov="both"):
    adb("logcat", "-c")
    adb("shell", "am", "broadcast", "-n", "dev.virtualnet/.DebugReceiver", "--es", "pkg", "dev.virtualnet.lab", "--es", "mode", mode, "--es", "cov", cov)
    for _ in range(20):
        if "debug set dev.virtualnet.lab" in adb("logcat", "-d", "-s", "VirtualNet"):
            break
        time.sleep(0.5)
    time.sleep(3)
    sh("am force-stop dev.virtualnet.lab")
    adb("logcat", "-c")
    sh("am start -n dev.virtualnet.lab/.LabActivity --ez run true")
    time.sleep(11)
    return adb("logcat", "-d", "-s", "VNL")

def parse(log):
    d, events = {}, []
    for line in log.splitlines():
        m = re.search(r"VNL\s*:\s*(.*)$", line)
        if not m:
            continue
        p = m.group(1).split("|")
        if len(p) < 4:
            continue
        if p[0] == "Live callbacks":
            events.append(p[2])
        else:
            d[p[1]] = (p[2], p[3])
    return d, events

def cell_nets(d):
    return [k for k, v in d.items() if k.startswith("network ") and v[1] == "CELL"]

def check(mode, d, events):
    bad = []
    def g(k): return d.get(k, ("", ""))
    def need(cond, msg):
        if not cond: bad.append(msg)
    last = [e for e in events if e.startswith("default caps")]
    if mode == "data":
        need(g("getActiveNetworkInfo")[1] == "CELL", "activeInfo")
        need(g("transports")[1] == "CELL", "transports")
        need(g("NOT_METERED")[0] == "false", "notMetered")
        need(g("isWifiEnabled")[0] == "false", "wifiEnabled")
        need(g("ipAddress")[0] == "0.0.0.0", "wifiIp")
        need(g("networkType")[1] == "CELL", "telephony")
        need("connected=false" in g("getNetworkInfo(1)")[0], "info1")
        need(bool(last) and "says=CELL" in last[-1], "callback")
    elif mode == "wifi":
        need(g("getActiveNetworkInfo")[1] == "WIFI", "activeInfo")
        need(g("transports")[1] == "WIFI", "transports")
        need(g("NOT_METERED")[0] == "true", "notMetered")
        need(g("isWifiEnabled")[0] == "true", "wifiEnabled")
        need("Home-WiFi" in g("ssid / bssid")[0], "ssid")
        need(g("ipAddress")[0] == "192.168.1.24", "wifiIp")
        need("connected=false" in g("getNetworkInfo(0)")[0], "info0")
        need(bool(last) and "says=WIFI" in last[-1], "callback")
    elif mode == "both":
        need(g("getNetworkInfo(0)")[1] == "CELL" and g("getNetworkInfo(1)")[1] == "WIFI", "infos")
        need(g("transports")[1] == "WIFI", "transports")
        need(len(cell_nets(d)) >= 1, "cellNetwork")
        need("Home-WiFi" in g("ssid / bssid")[0], "ssid")
        need(g("networkType")[1] == "CELL", "telephony")
    elif mode == "off":
        need("Home-WiFi" not in g("ssid / bssid")[0], "leakSsid")
        need(g("ipAddress")[0] != "192.168.1.24", "leakIp")
        need("rmnet_data0" not in " ".join(v[0] for k, v in d.items() if k.startswith("network ")), "leakIface")
    return bad

def check_apps(mode, d, events):
    """Apps layer also covers sockets and interfaces, which the system layer cannot."""
    bad = check(mode, d, events)
    tcp = d.get("TCP local address", ("", ""))[0]
    ifs = " ".join(v[0] for k, v in d.items() if k.startswith("up+ipv4") or k == "interfaces")
    if mode == "data":
        if "10.72.14.201" not in tcp: bad.append("tcpLocal")
        if "10.72.14.201" not in ifs: bad.append("ifaceIp")
    elif mode in ("wifi", "both"):
        if "192.168.1.24" not in tcp: bad.append("tcpLocal")
        if "192.168.1.24" not in ifs: bad.append("ifaceIp")
    return bad

def run_api(api):
    res = {"api": api, "modes": {}, "notes": []}
    start(api)
    if not wait_boot():
        res["notes"].append("boot timeout"); kill(); return res
    res["sdk"] = sh("getprop ro.build.version.sdk").strip()
    step(f"api {api} booted (sdk {res['sdk']})")
    go_root()
    if "uid=0" not in su("id"):
        res["notes"].append("no root"); kill(); return res
    if not sh(f"ls {CLI}").strip().startswith("/"):
        res["notes"].append("no vector"); kill(); return res
    for apk in (APP, LAB):
        adb("install", "-r", "-g", apk, timeout=180)
    sh("pm grant dev.virtualnet.lab android.permission.READ_PHONE_STATE")
    sh("pm grant dev.virtualnet.lab android.permission.ACCESS_FINE_LOCATION")
    su(f"{CLI} modules disable dev.virtualnet; {CLI} modules enable dev.virtualnet; {CLI} scope set dev.virtualnet system/0 com.android.phone/0")
    step("restarting zygote")
    su("setprop ctl.restart zygote")
    time.sleep(20)
    subprocess.run([ADB, "-s", SER, "wait-for-device"], capture_output=True, text=True, timeout=120)
    if not wait_boot():
        res["notes"].append("boot timeout after zygote restart"); kill(); return res
    go_root()
    step("system hooks restarted")
    sh("monkey -p dev.virtualnet -c android.intent.category.LAUNCHER 1")
    time.sleep(6)
    # Runtime grants can be dropped by the framework restart on some releases; regrant before testing.
    sh("pm grant dev.virtualnet.lab android.permission.READ_PHONE_STATE")
    sh("pm grant dev.virtualnet.lab android.permission.ACCESS_FINE_LOCATION")
    hooks = adb("logcat", "-d", "-s", "VirtualNet")
    res["hooks"] = sorted(set(re.findall(r"hooked (\S+)", hooks)))
    res["missing"] = sorted(set(re.findall(r"system: (\S+) not present", hooks)))
    res["warn"] = sorted(set(re.findall(r"W VirtualNet: .*?\] (.{0,120})", hooks)))[:8]
    for mode in ("data", "wifi", "both", "off"):
        step(f"mode {mode}")
        try:
            d, ev = parse(lab_run(mode))
            bad = check(mode, d, ev)
            res["modes"][mode] = "pass" if not bad and d else ("fail: " + ",".join(bad) if d else "no data")
            if bad:
                keys = ["getActiveNetworkInfo", "transports", "NOT_METERED", "isWifiEnabled", "ipAddress", "networkType", "ssid / bssid"]
                res.setdefault("detail", {})[mode] = {k: d.get(k) for k in keys if k in d} | {"events": [e[:90] for e in ev[-3:]]}
        except Exception as e:
            res["modes"][mode] = f"error {e}"
    # Phase B: the Apps layer alone, with only the Lab in scope (the Android 9 route).
    step("phase B: apps only")
    su(f"{CLI} scope set dev.virtualnet dev.virtualnet.lab/0")
    res["apps"] = {}
    for mode in ("data", "wifi", "both", "off"):
        step(f"apps mode {mode}")
        try:
            d, ev = parse(lab_run(mode, "apps"))
            bad = check_apps(mode, d, ev)
            res["apps"][mode] = "pass" if not bad and d else ("fail: " + ",".join(bad) if d else "no data")
        except Exception as e:
            res["apps"][mode] = f"error {e}"
    kill()
    return res

if __name__ == "__main__":
    os.makedirs(os.path.join(ROOT, ".local"), exist_ok=True)
    for a in sys.argv[1:]:
        r = run_api(int(a))
        print(json.dumps(r), flush=True)
        with open(os.path.join(ROOT, ".local/matrix-results.jsonl"), "a") as f:
            f.write(json.dumps(r) + "\n")

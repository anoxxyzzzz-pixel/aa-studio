#!/usr/bin/env python3
"""Uji AA Mouse Pro di emulator: ADB sendiri -> server injeksi -> sentuhan multi-jari dari mouse/keyboard virtual."""
import json, re, subprocess, sys, time, glob, xml.etree.ElementTree as ET
PKG = "aa.projek.mp.max"
RESULTS = []
def run(cmd, timeout=90):
    try:
        r = subprocess.run(cmd, shell=True, capture_output=True, text=True, timeout=timeout)
        return (r.stdout or "") + (r.stderr or "")
    except subprocess.TimeoutExpired:
        return "TIMEOUT"
def sh(cmd, timeout=90): return run("adb shell " + json.dumps(cmd), timeout)
def check(n, ok, d=""):
    RESULTS.append((n, ok)); print(f"RESULT {'PASS' if ok else 'FAIL'} | {n} | {d}", flush=True)
def info(m): print("INFO", m, flush=True)
def crashes():
    out = run("adb logcat -d -b crash", 30)
    return [l for l in out.splitlines() if "FATAL EXCEPTION" in l or (PKG in l and "Exception" in l)][:10]
def aalog(): return run("adb logcat -d -s AAOTG:I", 30)
def ui_nodes():
    sh("rm -f /sdcard/ui.xml"); run("adb shell uiautomator dump /sdcard/ui.xml", 40); run("adb pull /sdcard/ui.xml /tmp/ui.xml")
    try: root = ET.parse("/tmp/ui.xml").getroot()
    except Exception: return []
    out = []
    for n in root.iter("node"):
        b = re.findall(r"\d+", n.get("bounds", ""))
        if len(b) == 4:
            x1, y1, x2, y2 = map(int, b)
            out.append({"text": n.get("text", ""), "cls": n.get("class", ""), "cx": (x1+x2)//2, "cy": (y1+y2)//2})
    return out
def tap_text(t):
    for n in ui_nodes():
        if n["text"] == t or (t in n["text"] and "Button" in n["cls"]):
            sh(f"input tap {n['cx']} {n['cy']}"); return True
    return False

run("adb root"); time.sleep(2); run("adb wait-for-device")
for k in ("window_animation_scale", "transition_animation_scale", "animator_duration_scale"): sh(f"settings put global {k} 0")
sh("settings put global hide_error_dialogs 1"); sh("input keyevent 3"); sh("input keyevent 82")
time.sleep(25); run("adb logcat -c")
apk = glob.glob("app/build/outputs/apk/release/*.apk")[0]
info("ukuran APK (emulator, 3 ABI): %s byte" % run(f"stat -c %s {apk}").strip())
check("APK terpasang", "Success" in run(f"adb install -r {apk}", 180), "")
sh(f"appops set {PKG} SYSTEM_ALERT_WINDOW allow"); sh(f"pm grant {PKG} android.permission.POST_NOTIFICATIONS")

# adbd TCP (setara debugging USB lalu `adb tcpip 5555`)
info("tcpip: " + run("adb tcpip 5555").strip()); time.sleep(4); run("adb wait-for-device")
info("props: secure=%s" % sh("getprop ro.adb.secure").strip())

sh(f"am start -n {PKG}/.MainActivity"); time.sleep(4)
sh("input swipe 540 1700 540 300 400"); sh("input swipe 540 1700 540 300 400"); time.sleep(1)
ok = tap_text("Sambung ke 127.0.0.1")
info("tombol sambung diketuk: %s" % ok)
time.sleep(10)
lg = aalog()
info("log: " + " | ".join(l.split("AAOTG")[-1].strip() for l in lg.splitlines())[:400])
check("ADB tersambung dari dalam aplikasi", "Tersambung" in " ".join(n["text"] for n in ui_nodes()) or "SERVER READY" in lg, "")
check("Server injeksi (app_process) jalan & melapor READY", "SERVER READY" in lg, [l for l in lg.splitlines() if "SERVER" in l][:2])

# layar uji di depan, lalu pemetaan
sh(f"am start -n {PKG}/.TestPadActivity"); time.sleep(3)
sh(f"am start-foreground-service -n {PKG}/.CaptureService"); time.sleep(5)
check("Overlay tangkap aktif (fokus + pointer capture)", "CAPTURE true" in aalog(), "")

cc = run("gcc -static -O1 -o /tmp/uin ci/uin.c 2>&1"); info("gcc: " + (cc.strip()[:100] or "ok"))
run("adb push /tmp/uin /data/local/tmp/uin"); sh("chmod 755 /data/local/tmp/uin")
p = subprocess.Popen("adb shell /data/local/tmp/uin", shell=True, stdin=subprocess.PIPE, stdout=subprocess.PIPE, text=True)
time.sleep(5)
def cmd(s):
    p.stdin.write(s + "\n"); p.stdin.flush(); time.sleep(0.12)
run("adb logcat -c")
cmd("kd 17")                         # W -> joystick (jari id 0)
for _ in range(10): cmd("mm 6 -2")   # gerak mouse -> kamera (jari id 1)
cmd("bd")                            # klik kiri -> tembak (jari id 2)
time.sleep(0.3); cmd("bu"); cmd("ku 17"); time.sleep(1)
full = aalog()
info("log setelah input: " + " | ".join(l.split("AAOTG")[-1].strip() for l in full.splitlines() if " TP " not in l)[-900:])
info("SERVER lines: " + " | ".join(l.split("AAOTG")[-1].strip() for l in full.splitlines() if "SERVER" in l)[:700])
info("top: " + " ".join(sh("dumpsys activity activities | grep -E 'topResumedActivity'").split())[:200])
sh("input tap 500 500"); time.sleep(1)
info("tap normal -> TP: " + str(len([l for l in aalog().splitlines() if " TP " in l])))
info("logcat error: " + " | ".join(run("adb logcat -d -s InputDispatcher:W AndroidRuntime:E InputManager:W", 30).splitlines()[-6:])[:600])
tp = [l for l in full.splitlines() if " TP " in l]
info("TP contoh: " + " | ".join(l.split("AAOTG")[-1].strip() for l in tp[:3]) + " ... " + (tp[-1].split("AAOTG")[-1].strip() if tp else ""))
maxp = max([int(re.search(r"pointers=(\d+)", l).group(1)) for l in tp] or [0])
idsseen = set(re.findall(r"ids=\[([^\]]*)\]", " ".join(tp)))
info("max jari serentak: %d, ids: %s" % (maxp, list(idsseen)[:8]))
check("Sentuhan hasil injeksi sampai ke layar (TestPad menerima)", len(tp) > 0, f"{len(tp)} event")
check("Multi-jari: joystick + kamera + tembak serentak (>=3 jari)", maxp >= 3, f"maks {maxp}")
check("Setelah semua dilepas tidak ada jari tertinggal", tp and "pointers=1" in tp[-1] and ("action=1" in tp[-1] or "action=6" in tp[-1]) or (tp and "action=1 " in tp[-1]), tp[-1].split("AAOTG")[-1].strip() if tp else "")

# pairing lewat notifikasi: layanan mulai, notifikasi tampil, tidak crash (pairing asli tidak ada di emulator)
sh("am startservice -n %s/.PairingService" % PKG); sh("am start-foreground-service -n %s/.PairingService" % PKG); time.sleep(4)
nt = sh("dumpsys notification --noredact")
check("Notifikasi pairing tampil (mencari layanan pairing)", "Mencari layanan pairing" in nt, "")
sh("am start-foreground-service -a aa.projek.mp.max.PAIR_STOP -n %s/.PairingService" % PKG); time.sleep(2)
p.stdin.write("q\n"); p.stdin.flush()
check("Tidak ada crash", not crashes(), "; ".join(crashes()))
fails = [n for n, ok in RESULTS if not ok]
print(f"TOTAL {len(RESULTS)} pemeriksaan, {len(RESULTS)-len(fails)} lulus, {len(fails)} gagal")
for n in fails: print("GAGAL:", n)

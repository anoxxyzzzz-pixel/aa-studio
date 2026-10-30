#!/usr/bin/env python3
"""Uji AA Mouse OTG di emulator. Mouse/keyboard fisik ditiru dengan perangkat uinput virtual (root)."""
import json, re, subprocess, sys, time, glob
PKG = "com.aaotg.app"
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

run("adb root"); time.sleep(2); run("adb wait-for-device")
for k in ("window_animation_scale", "transition_animation_scale", "animator_duration_scale"): sh(f"settings put global {k} 0")
sh("settings put global hide_error_dialogs 1"); sh("input keyevent 3"); sh("input keyevent 82")
time.sleep(25); run("adb logcat -c")
apk = glob.glob("app/build/outputs/apk/release/*.apk")[0]
info("ukuran APK: %s byte" % run(f"stat -c %s {apk}").strip())
out = run(f"adb install -r {apk}", 180)
check("APK terpasang", "Success" in out, out.strip()[-80:])
sh(f"appops set {PKG} SYSTEM_ALERT_WINDOW allow"); sh(f"pm grant {PKG} android.permission.POST_NOTIFICATIONS")
info("uinput: " + sh("ls -l /dev/uinput").strip())
sh(f"am start-foreground-service -n {PKG}/.CaptureService"); time.sleep(6)
lg = aalog()
info("log awal: " + " | ".join(l.split("AAOTG:")[-1].strip() for l in lg.splitlines())[:300])
check("Overlay tangkap aktif & mendapat fokus", "FOCUS true" in lg, "")
check("Pointer capture diberikan ke overlay", "CAPTURE true" in lg, "")
# uinput: program C (dikompilasi di CI, static x86_64) membuat mouse & keyboard virtual
cc = run("gcc -static -O1 -o /tmp/uin ci/uin.c 2>&1")
info("gcc: " + (cc.strip() or "ok"))
run("adb push /tmp/uin /data/local/tmp/uin"); sh("chmod 755 /data/local/tmp/uin")
out = run("adb shell /data/local/tmp/uin", 60)
info("uinput out: " + out.strip()[:200])
info("perangkat input: " + " | ".join(l.strip() for l in sh("getevent -p").splitlines() if "name:" in l))
time.sleep(2)
lg = aalog()
moves = [l for l in lg.splitlines() if "CAP_MOVE" in l]
info("CAP_MOVE: %d, contoh: %s" % (len(moves), moves[:1]))
check("Gerakan mouse relatif diterima overlay", len(moves) >= 5, f"{len(moves)} event")
check("Klik kiri mouse diterima", "CAP_BTN 1 true" in lg and "CAP_BTN 1 false" in lg, "")
check("Tombol keyboard (W) diterima", "KEY 51 0" in lg, "")
check("Tidak ada crash", not crashes(), "; ".join(crashes()))
fails = [n for n, ok in RESULTS if not ok]
print(f"TOTAL {len(RESULTS)} pemeriksaan, {len(RESULTS)-len(fails)} lulus, {len(fails)} gagal")
for n in fails: print("GAGAL:", n)

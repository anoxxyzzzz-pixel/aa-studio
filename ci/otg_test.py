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

# ---- skrip uinput yang berjalan DI emulator (python tidak ada di emulator -> dikirim sebagai program kecil via toybox? pakai python host + adb push)
UINPUT = r'''
import os, struct, fcntl, time, sys
UI_SET_EVBIT=0x40045564; UI_SET_KEYBIT=0x40045565; UI_SET_RELBIT=0x40045566
UI_DEV_SETUP=0x405c5503; UI_DEV_CREATE=0x5501
EV_KEY, EV_REL, EV_SYN = 1, 2, 0
def mk(name, keys, rels):
    fd = os.open("/dev/uinput", os.O_WRONLY | os.O_NONBLOCK)
    fcntl.ioctl(fd, UI_SET_EVBIT, EV_KEY)
    for k in keys: fcntl.ioctl(fd, UI_SET_KEYBIT, k)
    if rels:
        fcntl.ioctl(fd, UI_SET_EVBIT, EV_REL)
        for r in rels: fcntl.ioctl(fd, UI_SET_RELBIT, r)
    setup = struct.pack("HHHH80sI", 3, 0x1234, 0x5678, 1, name.encode(), 0)
    fcntl.ioctl(fd, UI_DEV_SETUP, setup); fcntl.ioctl(fd, UI_DEV_CREATE)
    return fd
def ev(fd, t, c, v): os.write(fd, struct.pack("llHHi", 0, 0, t, c, v))
def syn(fd): ev(fd, EV_SYN, 0, 0)
BTN_LEFT=0x110
KEY_A, KEY_W = 30, 17
mouse = mk("AA Test Mouse", [BTN_LEFT, 0x111, 0x112], [0, 1, 8])
kb = mk("AA Test Keyboard", [KEY_A, KEY_W, 57], [])
time.sleep(2)
print("CREATED", flush=True)
for i in range(12):
    ev(mouse, EV_REL, 0, 7); ev(mouse, EV_REL, 1, -3); syn(mouse); time.sleep(0.05)
ev(mouse, EV_KEY, BTN_LEFT, 1); syn(mouse); time.sleep(0.1); ev(mouse, EV_KEY, BTN_LEFT, 0); syn(mouse)
ev(kb, EV_KEY, KEY_W, 1); syn(kb); time.sleep(0.2); ev(kb, EV_KEY, KEY_W, 0); syn(kb)
time.sleep(1)
print("DONE", flush=True)
'''
open("/tmp/uin.py", "w").write(UINPUT)

run("adb root"); time.sleep(2); run("adb wait-for-device")
for k in ("window_animation_scale", "transition_animation_scale", "animator_duration_scale"): sh(f"settings put global {k} 0")
sh("settings put global hide_error_dialogs 1"); sh("input keyevent 3"); sh("input keyevent 82")
time.sleep(25); run("adb logcat -c")
apk = glob.glob("app/build/outputs/apk/release/*.apk")[0]
info("ukuran APK: %s byte" % run(f"stat -c %s {apk}").strip())
out = run(f"adb install -r {apk}", 180)
check("APK terpasang", "Success" in out, out.strip()[-80:])
sh(f"appops set {PKG} SYSTEM_ALERT_WINDOW allow"); sh(f"pm grant {PKG} android.permission.POST_NOTIFICATIONS")
info("uinput: " + sh("ls -l /dev/uinput").strip() + " | python3 di emulator: " + sh("which python3").strip())
sh(f"am start-foreground-service -n {PKG}/.CaptureService"); time.sleep(6)
lg = aalog()
info("log awal: " + " | ".join(l.split("AAOTG:")[-1].strip() for l in lg.splitlines())[:300])
check("Overlay tangkap aktif & mendapat fokus", "FOCUS true" in lg, "")
check("Pointer capture diberikan ke overlay", "CAPTURE true" in lg, "")
# uinput: jalankan dari host lewat adb shell tidak mungkin tanpa python -> pakai toybox? coba python bila ada
py = sh("which python3 python").strip()
if py:
    run("adb push /tmp/uin.py /data/local/tmp/uin.py")
    out = run("adb shell python3 /data/local/tmp/uin.py", 60)
    info("uinput out: " + out.strip()[:200])
else:
    info("python tidak ada di emulator; uji mouse/keyboard virtual dilewati")
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

#!/usr/bin/env python3
"""Uji asap AA Mouse Pro di emulator: pasang, buka, tidak crash. (Shizuku/root & mouse fisik tidak ada di emulator.)"""
import json, re, subprocess, sys, time, xml.etree.ElementTree as ET
PKG = "com.aamouse.pro"
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
def ui_nodes():
    sh("rm -f /sdcard/ui.xml"); run("adb shell uiautomator dump --windows /sdcard/ui.xml", 40)
    run("adb pull /sdcard/ui.xml /tmp/ui.xml")
    try: root = ET.parse("/tmp/ui.xml").getroot()
    except Exception: return []
    out = []
    for n in root.iter("node"):
        b = re.findall(r"\d+", n.get("bounds", ""))
        if len(b) == 4:
            x1, y1, x2, y2 = map(int, b)
            out.append({"text": n.get("text", ""), "desc": n.get("content-desc", ""), "cls": n.get("class", ""), "cx": (x1+x2)//2, "cy": (y1+y2)//2, "pkg": n.get("package", "")})
    return out
def snap(name):
    try:
        import base64, io
        from PIL import Image
        run("adb exec-out screencap -p > /tmp/snap.png")
        im = Image.open("/tmp/snap.png").convert("RGB"); im.thumbnail((234, 520))
        buf = io.BytesIO(); im.save(buf, "JPEG", quality=45)
        b = base64.b64encode(buf.getvalue()).decode()
        for i in range(0, len(b), 180): print(f"IMG {name} {i//180:03d} {b[i:i+180]}", flush=True)
    except Exception as e: info(f"snap gagal: {e}")
def crashes():
    out = run("adb logcat -d -b crash", 30) + run("adb logcat -d -s AndroidRuntime:E", 30)
    return [l for l in out.splitlines() if "FATAL EXCEPTION" in l or (PKG in l and "Exception" in l)][:10]

run("adb root"); time.sleep(2); run("adb wait-for-device")
for k in ("window_animation_scale", "transition_animation_scale", "animator_duration_scale"): sh(f"settings put global {k} 0")
sh("settings put global hide_error_dialogs 1"); sh("input keyevent 3"); sh("input keyevent 82")
time.sleep(25); run("adb logcat -c")
import glob
apk = glob.glob("app/build/outputs/apk/release/*.apk")[0]
info("ukuran APK: %s byte" % run(f"stat -c %s {apk}").strip())
out = run(f"adb install -r {apk}", 180)
check("APK terpasang", "Success" in out, out.strip()[-80:])
info("ABI HP emulator: " + sh("getprop ro.product.cpu.abilist").strip())
pm = sh(f"dumpsys package {PKG} | grep -E 'versionName|versionCode' | head -2")
check("Versi 1.0 & ID paket com.aamouse.pro", "versionName=1.0" in pm, " ".join(pm.split()))
sh(f"appops set {PKG} SYSTEM_ALERT_WINDOW allow"); sh(f"pm grant {PKG} android.permission.POST_NOTIFICATIONS")
label = sh(f"cmd package resolve-activity --brief -c android.intent.category.LAUNCHER {PKG}").strip().splitlines()[-1:]
info("launcher activity: %s" % label)
sh(f"am start -W -n {PKG}/xtr.keymapper.activity.MainActivity"); time.sleep(10)
pid = sh(f"pidof {PKG}").strip()
check("Aplikasi berjalan setelah dibuka", bool(pid), f"pid={pid}")
top = sh("dumpsys activity activities | grep -E 'topResumedActivity'").strip()
check("Layar utama AA Mouse Pro tampil di depan", PKG in top, top[:140])
nodes = [n for n in ui_nodes() if n["pkg"] == PKG]
texts = [n["text"] for n in nodes if n["text"]] + ["desc:" + n["desc"] for n in nodes if n["desc"]]
info("teks layar utama: " + " | ".join(texts)[:700])
snap("main")
check("Teks 'XtMapper' tidak muncul di layar utama", not any("xtmapper" in t.lower() for t in texts), "")
check("Nama 'AA Mouse Pro' tampil", any("AA Mouse Pro" in t for t in texts), "")
# buka menu/tombol yang ada, satu per satu, pastikan tidak crash
tapped = []
for n in nodes:
    if "Button" in n["cls"] or "Image" in n["cls"]:
        if len(tapped) >= 4: break
        sh(f"input tap {n['cx']} {n['cy']}"); time.sleep(3); tapped.append(n["text"] or n["desc"] or n["cls"])
        info("setelah ketuk %r: %s" % (tapped[-1], " | ".join(x["text"] for x in ui_nodes() if x["text"])[:300]))
        snap("tap%d" % len(tapped)); sh("input keyevent 4"); time.sleep(2)
        if not sh(f"pidof {PKG}").strip():
            sh(f"am start -n {PKG}/xtr.keymapper.activity.MainActivity"); time.sleep(5)
check("Tidak crash setelah mengetuk tombol utama", not crashes(), "; ".join(crashes()))
info("memori: " + " ".join(sh(f"dumpsys meminfo {PKG} | grep -E 'TOTAL PSS'").split())[:120])
info("native libs: " + sh(f"ls /data/app/*/{PKG}*/lib/*/ 2>/dev/null").replace("\n", " ")[:200])

# --- Uji proses server (jalur yang sama dengan Shizuku: app_process sebagai user shell)
info("== uji server sebagai shell ==")
run("adb unroot"); time.sleep(4); run("adb wait-for-device")
sh(f"am force-stop {PKG}")
ai = sh(f"pm path {PKG}").strip().replace("package:", "").splitlines()[0]
libdir = ai.rsplit("/", 1)[0] + "/lib/x86_64"
info("apk=%s libdir=%s whoami=%s" % (ai, libdir, sh("id").strip()))
info("libs: " + sh(f"ls {libdir}").replace("\n", " "))
run(f"adb shell 'nohup app_process -Djava.library.path={libdir} -Djava.class.path={ai} / xtr.keymapper.server.RemoteServiceShell --no-auto-launch --no-logcat > /data/local/tmp/srv.log 2>&1 &'", 20)
time.sleep(15)
info("srv.log: " + " | ".join(sh("cat /data/local/tmp/srv.log").splitlines())[:1500])
svc = sh("service list | grep -i xtmapper")
check("Server (RemoteService) berhasil start & terdaftar", "xtmapper" in svc, svc.strip())
info("proses: " + sh("ps -A | grep app_process").strip()[:200])
log = run("adb logcat -d | grep -E 'xtmapper-server|AndroidRuntime|FATAL|UnsatisfiedLink' | tail -20", 30)
info("logcat server: " + " | ".join(log.splitlines())[:1500])

info("== uji perbaikan SecurityException (shell uid) ==")
for flag in ("", "--fix"):
    out = run(f"adb shell 'app_process -Djava.library.path={libdir} -Djava.class.path={ai} / xtr.keymapper.server.SelfTest {flag} 2>&1'", 60)
    info("selftest %r: %s" % (flag, " | ".join(l for l in out.splitlines() if "SELFTEST" in l or "Exception" in l)[:500]))
    if flag == "":
        before = out
    else:
        after = out
check("Sebelum perbaikan: reproduksi SecurityException uid 2000 (uji valid)", "SecurityException" in before and "SELFTEST_BEFORE OK" not in before, "")
check("Sesudah perbaikan: context server boleh memanggil sistem", "SELFTEST_AFTER OK" in after, "")

check("Tidak ada crash", not crashes(), "; ".join(crashes()))
fails = [n for n, ok in RESULTS if not ok]
print(f"TOTAL {len(RESULTS)} pemeriksaan, {len(RESULTS)-len(fails)} lulus, {len(fails)} gagal")
for n in fails: print("GAGAL:", n)
print("--- logcat ---\n" + run(f"adb logcat -d | grep -E 'aamouse|AndroidRuntime|xtr.keymapper' | tail -30", 30))

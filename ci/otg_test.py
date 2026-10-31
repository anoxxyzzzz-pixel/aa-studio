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

# ---- Web Source offline: halaman lokal + audio, panel bisa digeser (jaringan dimatikan)
import wave, struct, math
sh("am start-foreground-service -a aa.projek.mp.max.WEB_STOP -n %s/.WebService" % PKG); sh("am force-stop %s" % PKG); time.sleep(2)
w = wave.open("/tmp/tone.wav", "w"); w.setnchannels(1); w.setsampwidth(2); w.setframerate(22050)
w.writeframes(b"".join(struct.pack("<h", int(12000 * math.sin(2 * math.pi * 440 * i / 22050))) for i in range(22050 * 2))); w.close()
open("/tmp/index.html", "w").write("""<!doctype html><meta charset=utf-8><body style="background:#123;color:#fff"><h1>OFFLINE OK</h1>
<audio id=a src="tone.wav" loop autoplay></audio><script>
var a=document.getElementById('a');window.addEventListener('load',function(){console.log('AA_LOAD');});
a.addEventListener('playing',function(){console.log('AA_PLAYING');});a.play().catch(function(e){});
setTimeout(function(){console.log('AA_HOOK '+(!!window.__aaAudio));},7000);
fetch('/%2e%2e/web.json').then(function(r){console.log('AA_TRAV '+r.status);});
</script>""")
run("adb push /tmp/index.html /data/local/tmp/index.html"); run("adb push /tmp/tone.wav /data/local/tmp/tone.wav")
uid = sh(f"stat -c %U /data/data/{PKG}").strip()
sh(f"mkdir -p /data/data/{PKG}/files/local/t1 && cp /data/local/tmp/index.html /data/local/tmp/tone.wav /data/data/{PKG}/files/local/t1/ && chown -R {uid}:{uid} /data/data/{PKG}/files")
web = [{"id": "w1", "name": "TestWeb", "url": "https://aa.local/t1/index.html", "x": 0.30, "y": 0.20, "w": 0.45, "h": 0.5, "visible": True, "muted": False, "volume": 100}]
open("/tmp/web.json", "w").write(json.dumps(web)); run("adb push /tmp/web.json /data/local/tmp/web.json")
sh(f"cp /data/local/tmp/web.json /data/data/{PKG}/files/web.json && chown {uid}:{uid} /data/data/{PKG}/files/web.json")
sh("svc wifi disable"); sh("svc data disable"); time.sleep(3)
run("adb logcat -c")
sh(f"am start-foreground-service -n {PKG}/.WebService"); time.sleep(14)
wl = run("adb logcat -d -s AAStudio:I AAStudio-js:I AAOTG:I", 30)
info("web log: " + " | ".join(dict.fromkeys(l.split(": ", 1)[-1] for l in wl.splitlines() if "AA_" in l or "page" in l))[:400])
check("Web Source lokal termuat tanpa internet", "AA_LOAD" in wl, "")
check("Audio Web Source berbunyi tanpa internet", "AA_PLAYING" in wl, "")
check("Pengatur suara terpasang di halaman", "AA_HOOK true" in wl, "")
check("Anti path-traversal pada file lokal", "AA_TRAV 404" in wl, "")
nodes = [n for n in ui_nodes() if n["text"] == "TestWeb"]
before = json.loads(sh(f"cat /data/data/{PKG}/files/web.json"))[0]
if nodes:
    n = nodes[0]; sh(f"input swipe {n['cx']} {n['cy']} {n['cx']-120} {n['cy']+90} 500"); time.sleep(2)
after = json.loads(sh(f"cat /data/data/{PKG}/files/web.json"))[0]
info(f"geser: judul ditemukan={bool(nodes)} x {before['x']}->{after['x']:.3f} y {before['y']}->{after['y']:.3f}")
check("Panel Web Source bisa digeser & posisinya tersimpan", bool(nodes) and (abs(after["x"] - before["x"]) > 0.02 or abs(after["y"] - before["y"]) > 0.02), "")
sh("svc wifi enable"); sh("svc data enable")
p.stdin.write("q\n"); p.stdin.flush()
check("Tidak ada crash", not crashes(), "; ".join(crashes()))
fails = [n for n, ok in RESULTS if not ok]
print(f"TOTAL {len(RESULTS)} pemeriksaan, {len(RESULTS)-len(fails)} lulus, {len(fails)} gagal")
for n in fails: print("GAGAL:", n)

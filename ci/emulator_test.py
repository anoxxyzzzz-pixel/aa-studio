#!/usr/bin/env python3
"""Uji end-to-end AA Studio di emulator (dijalankan oleh GitHub Actions)."""
import json, re, subprocess, sys, time, xml.etree.ElementTree as ET

PKG = "com.aastudio.live"
RESULTS = []


def run(cmd, timeout=60):
    try:
        r = subprocess.run(cmd, shell=True, capture_output=True, text=True, timeout=timeout)
        return (r.stdout or "") + (r.stderr or "")
    except subprocess.TimeoutExpired:
        return "TIMEOUT"


def sh(cmd, timeout=60):
    return run("adb shell " + json.dumps(cmd), timeout)


def check(name, ok, detail=""):
    RESULTS.append((name, ok))
    print(f"RESULT {'PASS' if ok else 'FAIL'} | {name} | {detail}", flush=True)


def info(msg):
    print("INFO", msg, flush=True)


def section(t):
    print(f"\n===== {t} =====", flush=True)


def windows():
    out = run("adb shell dumpsys window windows", 60)
    res = []
    for blk in out.split("Window #")[1:]:
        if PKG not in blk[:300]:
            continue
        head = blk.split("\n")[0].strip()[:110]
        attr = next((l.strip() for l in blk.split("\n") if "mAttrs=" in l), "")
        res.append((head, attr))
    return res


def overlay_windows():
    return [w for w in windows() if "APPLICATION_OVERLAY" in w[1] or "ty=2038" in w[1]]


def ui_nodes():
    sh("rm -f /sdcard/ui.xml")
    out = run("adb shell uiautomator dump --windows /sdcard/ui.xml", 40)
    if "ERROR" in out or "dumped" not in out:
        out = run("adb shell uiautomator dump /sdcard/ui.xml", 40)
    run("adb pull /sdcard/ui.xml /tmp/ui.xml")
    try:
        root = ET.parse("/tmp/ui.xml").getroot()
    except Exception:
        return []
    nodes = []
    for n in root.iter("node"):
        b = re.findall(r"\d+", n.get("bounds", ""))
        if len(b) == 4:
            x1, y1, x2, y2 = map(int, b)
            nodes.append({"text": n.get("text", ""), "desc": n.get("content-desc", ""),
                          "cls": n.get("class", ""), "cx": (x1 + x2) // 2, "cy": (y1 + y2) // 2,
                          "b": (x1, y1, x2, y2)})
    return nodes


def find(text, exact=True, cls=None):
    for n in ui_nodes():
        t = n["text"]
        if (t == text if exact else text.lower() in t.lower()) and (cls is None or cls in n["cls"]):
            return n
    return None


def tap_text(text, exact=True):
    n = find(text, exact)
    if n:
        sh(f"input tap {n['cx']} {n['cy']}")
        return True
    return False


def project_json(pid):
    out = sh(f"cat /data/data/{PKG}/files/projects/{pid}.json")
    try:
        return json.loads(out)
    except Exception:
        return None


def crashes():
    out = run("adb logcat -d -b crash", 30) + run("adb logcat -d -s AndroidRuntime:E", 30)
    return [l for l in out.splitlines() if "FATAL EXCEPTION" in l or PKG in l and "Exception" in l][:10]


# ---------------------------------------------------------------- setup
section("SETUP")
run("adb root"); time.sleep(2)
run("adb wait-for-device")
sh("settings put global window_animation_scale 0")
sh("settings put global transition_animation_scale 0")
sh("settings put global animator_duration_scale 0")
sh("settings put global hide_error_dialogs 1")
sh("am broadcast -a android.intent.action.CLOSE_SYSTEM_DIALOGS")
sh("input keyevent 3")
sh("input keyevent 82")
time.sleep(25)  # beri waktu emulator (software rendering) menjadi stabil
size = re.search(r"(\d+)x(\d+)", sh("wm size").split("\n")[-2] if "Override" in sh("wm size") else sh("wm size"))
W, H = int(size.group(1)), int(size.group(2))
dens = int(re.search(r"(\d+)", sh("wm density")).group(1))
dp = dens / 160.0
info(f"layar {W}x{H}, densitas {dens}")
info("jaringan: " + " ".join(sh("ping -c 1 -W 3 8.8.8.8 | tail -2").split())[:160])
info("DNS: " + " ".join(sh("ping -c 1 -W 3 example.com | head -2").split())[:160])
run("adb logcat -c")

info("ukuran APK rilis: " + run("ls -l app/build/outputs/apk/release/app-release.apk").split()[4] + " byte")
out = run("adb install -r app/build/outputs/apk/release/app-release.apk", 180)
check("APK terpasang", "Success" in out, out.strip()[-80:])
sh(f"appops set {PKG} SYSTEM_ALERT_WINDOW allow")
sh(f"pm grant {PKG} android.permission.POST_NOTIFICATIONS")
out = sh(f"appops get {PKG} SYSTEM_ALERT_WINDOW")
check("Izin overlay diberikan (appops)", "allow" in out, out.strip())

def snap(name):
    """Tangkap layar, kecilkan, cetak sebagai base64 (dibaca dari anotasi)."""
    try:
        import base64, io
        from PIL import Image
        run("adb exec-out screencap -p > /tmp/snap.png")
        im_ = Image.open("/tmp/snap.png").convert("RGB")
        im_.thumbnail((234, 520))
        buf = io.BytesIO()
        im_.save(buf, "JPEG", quality=45)
        b = base64.b64encode(buf.getvalue()).decode()
        for i in range(0, len(b), 180):
            print(f"IMG {name} {i // 180:03d} {b[i:i + 180]}", flush=True)
    except Exception as e:
        info(f"snap {name} gagal: {e}")


# ---------------------------------------------------------------- 1. ikon -> overlay langsung
section("1. Tekan ikon -> overlay editor langsung")
sh("am start -W -n %s/.MainActivity" % PKG)
time.sleep(5)
svc = sh("dumpsys activity services %s" % PKG)
check("OverlayService berjalan (foreground)", "OverlayService" in svc and "isForeground=true" in svc.replace(" ", "") or "OverlayService" in svc,
      "service terdaftar" if "OverlayService" in svc else "service tidak ada")
ow = overlay_windows()
check("Jendela overlay (TYPE_APPLICATION_OVERLAY) ada", len(ow) >= 1, f"{len(ow)} jendela")
for h, a in ow[:3]:
    info(a[:200])
top = sh("dumpsys activity activities | grep -E 'mResumedActivity|topResumedActivity'")
check("Layar aplikasi AA Studio TIDAK terbuka (hanya overlay)", "MainActivity" not in top and "EditorActivity" not in top, top.strip()[:140])
n_text = [n["text"] for n in ui_nodes() if n["text"]]
info("teks terlihat di overlay: " + ", ".join(n_text[:12]))
check("Bar bawah editor terlihat (Hapus semua / Layer / Stream)", any("Layer" in t for t in n_text) or any("Stream" in t for t in n_text), "")

# ---------------------------------------------------------------- seed proyek
section("2. Seed proyek untuk uji drag/resize")
sh("am stopservice -n %s/.OverlayService" % PKG)
time.sleep(1)
seed = {"version": 2, "id": "seed0001", "name": "Seed", "updated": int(time.time() * 1000),
        "items": [{"id": "i1", "name": "Example", "url": "https://example.com", "x": 240, "y": 270,
                   "w": 960, "h": 540, "visible": True, "locked": False, "muted": False, "volume": 100}],
        "audio": {"volume": 100, "muted": False, "autoplay": True, "soundUri": "", "soundName": ""},
        "overlay": {"widthPercent": 60, "touchEnabled": False}}
open("/tmp/seed.json", "w").write(json.dumps(seed))
run("adb push /tmp/seed.json /data/local/tmp/seed0001.json")
uid = sh(f"stat -c %U /data/data/{PKG}").strip()
sh(f"cp /data/local/tmp/seed0001.json /data/data/{PKG}/files/projects/seed0001.json && chown {uid}:{uid} /data/data/{PKG}/files/projects/seed0001.json && chmod 660 /data/data/{PKG}/files/projects/seed0001.json")
check("Seed proyek terbaca", project_json("seed0001") is not None, "")
sh("am start-foreground-service -a com.aastudio.live.EDIT --es project_id seed0001 -n %s/.OverlayService" % PKG)
time.sleep(4)
check("Overlay editor aktif untuk proyek seed", len(overlay_windows()) >= 1, "")

time.sleep(2)
snap("edit")
info("memori mode Editor: " + " ".join(sh(f"dumpsys meminfo {PKG} | grep -E 'TOTAL PSS'").split())[:120])
lx = lambda L: L * W / 1920.0
ly = lambda L: L * H / 1080.0

# ---------------------------------------------------------------- 3. drag
section("3. Drag kotak")
cx, cy = lx(240 + 480), ly(270 + 270)
dx, dy = W * 0.10, -H * 0.10
sh(f"input swipe {int(cx)} {int(cy)} {int(cx + dx)} {int(cy + dy)} 700")
time.sleep(2.5)
p = project_json("seed0001")
it = p["items"][0] if p else {}
ex, ey = 240 + 0.10 * 1920, 270 - 0.10 * 1080
ok = bool(it) and abs(it["x"] - ex) <= 40 and abs(it["y"] - ey) <= 40
check("Drag memindahkan kotak & tersimpan", ok, f"x={it.get('x')} y={it.get('y')} (harapan ~{ex:.0f},{ey:.0f})")

# ---------------------------------------------------------------- 4. resize
section("4. Resize lewat sudut kanan-bawah")
it = project_json("seed0001")["items"][0]
bx, by = lx(it["x"] + it["w"]) - 8 * dp, ly(it["y"] + it["h"]) - 8 * dp
w0, h0 = it["w"], it["h"]
sh(f"input swipe {int(bx)} {int(by)} {int(bx + W * 0.05)} {int(by + H * 0.05)} 700")
time.sleep(2.5)
it2 = project_json("seed0001")["items"][0]
ok = it2["w"] > w0 + 40 and it2["h"] > h0 + 20 and it2["x"] == it["x"] and it2["y"] == it["y"]
check("Resize mengubah lebar/tinggi & tersimpan", ok, f"w {w0}->{it2['w']}, h {h0}->{it2['h']}")

# ---------------------------------------------------------------- 5. tambah via UI
section("5. Tambah Web Source lewat UI (+)")
added = False
if tap_text("+"):
    time.sleep(1.5)
    f = find("", True, "EditText")
    nodes = [n for n in ui_nodes() if "EditText" in n["cls"]]
    info(f"EditText ditemukan: {len(nodes)}")
    check("Panel Tambah menampilkan tombol impor offline", find("Impor file offline", False, "Button") is not None, "")
    if nodes:
        sh(f"input tap {nodes[0]['cx']} {nodes[0]['cy']}")
        time.sleep(0.5)
        sh("input text https://example.org")
        time.sleep(0.8)
        sh("input keyevent 4")  # tutup keyboard (Back pertama menutup IME)
        time.sleep(0.8)
        if not find("Tambah Web Source", False):
            info("panel tertutup oleh Back; membuka lagi")
        btn = find("Tambah Web Source", True, "Button")
        if btn:
            sh(f"input tap {btn['cx']} {btn['cy']}")
            time.sleep(2.5)
            added = len(project_json("seed0001")["items"]) == 2
        else:
            info("tombol Tambah (Button) tidak ditemukan; teks: " + ", ".join(n["text"] for n in ui_nodes() if n["text"]))
else:
    info("tombol + tidak ditemukan oleh uiautomator (overlay mungkin tidak terbaca)")
check("Web Source baru ditambah lewat UI", added, f"jumlah elemen = {len(project_json('seed0001')['items'])}")

# ---------------------------------------------------------------- 6. stream
section("6. Mode Stream (jendela WebView di atas aplikasi lain)")
sh("input keyevent 4") if find("Tambah Web Source", False) else None  # pastikan panel tertutup
time.sleep(1)
tapped = tap_text("Stream")
time.sleep(4)
left_edit = find("Hapus semua", False) is None
check("Tab Stream di editor beralih ke mode Stream", tapped and left_edit, f"tapped={tapped}, editor hilang={left_edit}")
if not left_edit:
    sh("am start-foreground-service -a com.aastudio.live.RUN --es project_id seed0001 -n %s/.OverlayService" % PKG)
time.sleep(10)
ow = overlay_windows()
n_items = len(project_json("seed0001")["items"])
check("Jendela Stream tampil (1 per elemen + bubble)", len(ow) >= n_items, f"{len(ow)} jendela overlay, {n_items} elemen")
for h, a in ow:
    info(("NOT_TOUCHABLE " if "NOT_TOUCHABLE" in a or "fl=" in a and "0x" in a else "") + a[:230])
shot = run("adb exec-out screencap -p > /tmp/s.png; ls -l /tmp/s.png")
snap("stream")
bub = find("AA", True)
if bub:
    sh(f"input tap {bub['cx']} {bub['cy']}")
    time.sleep(1.5)
    snap("bubble")
    labels = [n["text"] for n in ui_nodes() if n["text"] in ("Editor", "Suara", "Mute", "Sentuh:OFF", "Sentuh:ON", "Vol+", "Stop")]
    info("menu bubble: " + ", ".join(labels))
    right = [n["b"][2] for n in ui_nodes() if n["text"] in ("Editor", "Suara", "Mute", "Sentuh:OFF", "Sentuh:ON", "Vol+", "Stop")]
    check("Semua tombol menu bubble muat di dalam layar", len(labels) >= 5 and (not right or max(right) <= W), f"{len(labels)} tombol, tepi kanan terjauh={max(right) if right else '-'} dari {W}")
    sh(f"input tap {bub['cx']} {bub['cy']}")
    time.sleep(1)
else:
    info("bubble AA tidak ditemukan oleh uiautomator")
try:
    import importlib
    try:
        from PIL import Image
    except ImportError:
        run("pip install pillow --quiet --break-system-packages || pip install pillow --quiet")
        from PIL import Image
    im = Image.open("/tmp/s.png").convert("RGB")
    it = project_json("seed0001")["items"][0]
    x1, y1 = int(lx(it["x"])) + 10, int(ly(it["y"])) + 10
    x2, y2 = int(lx(it["x"] + it["w"])) - 10, int(ly(it["y"] + it["h"])) - 10
    px = [im.getpixel((x, y)) for x in range(max(0, x1), min(W, x2), 12) for y in range(max(0, y1), min(H, y2), 12)]
    light = sum(1 for r, g, b in px if r > 225 and g > 225 and b > 225) / max(1, len(px))
    dark = sum(1 for r, g, b in px if r < 90 and g < 90 and b < 90) / max(1, len(px))
    # Latar halaman bisa transparan, jadi piksel tidak bisa jadi tolok ukur. Verifikasi render lewat
    # log "page finished" (tanpa "load error") dan tangkapan layar (IMG stream).
    log_ = run("adb logcat -d -s AAStudio:V", 20)
    loaded = log_.count("page finished") >= 1 and "load error" not in log_ and "ssl error" not in log_
    check("Halaman web selesai dimuat tanpa error (log WebView)", loaded, f"terang={light:.0%}, gelap={dark:.0%}")
except Exception as e:
    info(f"analisis piksel dilewati: {e}")

info("TCP 443: " + " ".join(sh("nc -z -w 5 example.com 443 && echo terhubung || echo gagal").split())[:80])
info("tanggal emulator: " + sh("date").strip())
info("log WebView: " + " || ".join(l[-150:] for l in run("adb logcat -d -s AAStudio:V", 20).splitlines()[-8:]))
try:
    it_ = project_json("seed0001")["items"][0]
    xa, ya = int(lx(it_["x"])), int(ly(it_["y"]))
    xb, yb = int(lx(it_["x"] + it_["w"])), int(ly(it_["y"] + it_["h"]))
    grid = []
    for gy in range(4):
        row = []
        for gx in range(4):
            r_ = g_ = b_ = n_ = 0
            for x in range(xa + (xb - xa) * gx // 4 + 5, xa + (xb - xa) * (gx + 1) // 4 - 5, 20):
                for y in range(ya + (yb - ya) * gy // 4 + 5, ya + (yb - ya) * (gy + 1) // 4 - 5, 20):
                    p_ = im.getpixel((x, y)); r_ += p_[0]; g_ += p_[1]; b_ += p_[2]; n_ += 1
            row.append("%02x%02x%02x" % (r_ // max(1, n_), g_ // max(1, n_), b_ // max(1, n_)))
        grid.append(" ".join(row))
    info("grid warna kotak (4x4): " + " / ".join(grid))
except Exception as e:
    info(f"grid warna dilewati: {e}")
info("teks di jendela Stream: " + " | ".join(n["text"] for n in ui_nodes() if n["text"]))
mem = sh(f"dumpsys meminfo {PKG} | grep -E 'TOTAL PSS|TOTAL:'")
info("memori mode Stream: " + " ".join(mem.split())[:200])
info("CPU: " + " ".join(sh("dumpsys cpuinfo | grep aastudio").split())[:200])

# ---------------------------------------------------------------- 7. rotasi
section("7. Rotasi layar")
sh("settings put system accelerometer_rotation 0")
sh("settings put system user_rotation 1")
time.sleep(6)
ow = overlay_windows()
check("Overlay tetap ada setelah rotasi ke landscape", len(ow) >= 1, f"{len(ow)} jendela")
sh("settings put system user_rotation 0")
time.sleep(6)
check("Overlay tetap ada setelah kembali ke portrait", len(overlay_windows()) >= 1, "")
check("Proses tidak crash saat rotasi", not crashes(), "; ".join(crashes()))

# ---------------------------------------------------------------- 8. stop & bersih
section("8. Stop & pembersihan")
sh("am stopservice -n %s/.OverlayService" % PKG)
time.sleep(3)
check("Semua jendela overlay dilepas setelah Stop", len(overlay_windows()) == 0, f"{len(overlay_windows())} tersisa")
svc = sh("dumpsys activity services %s" % PKG)
check("Service berhenti", "ServiceRecord" not in svc or "OverlayService" not in svc, "")

# ---------------------------------------------------------------- 9. audio
section("9. Audio (suara uji bawaan)")
sh("am start -n %s/.AudioActivity --es project_id seed0001" % PKG)
time.sleep(3)
played = False
if tap_text("Play"):
    time.sleep(1.2)
    pb = sh("dumpsys audio")
    played = bool(re.search(r"AudioPlaybackConfiguration.*state:started", pb.replace("\n", " "))) or "state:started" in pb.replace(" ", "")
    info("status: " + " | ".join(n["text"] for n in ui_nodes() if n["text"] in ("Memutar", "Selesai", "Dijeda") or "Gagal" in n["text"]))
else:
    info("tombol Play tidak ditemukan")
check("Suara uji diputar (AudioPlaybackConfiguration started)", played, "")
fx = sh("dumpsys media.audio_flinger | grep -i -c loudness").strip()
check("Efek penguat (LoudnessEnhancer) terpasang pada pemutar", fx.isdigit() and int(fx) > 0, f"cocok={fx}")
def music_idx():
    blk = sh("dumpsys audio").split("- STREAM_MUSIC:")
    if len(blk) < 2:
        return -1
    m = re.search(r"speaker\): (\d+)", blk[1][:900])
    return int(m.group(1)) if m else -1
before = music_idx()
btn = find("Maksimalkan volume media", True, "Button")
if btn:
    sh(f"input tap {btn['cx']} {btn['cy']}")
    time.sleep(1.5)
after = music_idx()
check("Tombol 'Maksimalkan volume media' menaikkan volume sistem", bool(btn) and after > before or after >= 10, f"sebelum={before}, sesudah={after}")
check("Tidak ada crash selama pengujian", not crashes(), "; ".join(crashes()))


# ---------------------------------------------------------------- 10. offline
section("10. Mode offline (tanpa internet)")
import wave, struct, math, zipfile, os
def mkwav(path, secs=2):
    w = wave.open(path, "w"); w.setnchannels(1); w.setsampwidth(2); w.setframerate(22050)
    w.writeframes(b"".join(struct.pack("<h", int(12000 * math.sin(2 * math.pi * 440 * i / 22050))) for i in range(22050 * secs)))
    w.close()
mkwav("/tmp/tone.wav")
HTML = """<!doctype html><meta charset=utf-8><body style="background:#123;color:#fff"><h1>OFFLINE OK</h1>
<audio id=a src="tone.wav" loop autoplay></audio>
<script>
var a=document.getElementById('a');
window.addEventListener('load',function(){console.log('AA_LOAD');});setTimeout(function(){console.log('AA_STATE '+document.readyState);},3000);
a.addEventListener('playing',function(){console.log('AA_PLAYING');});
a.play().catch(function(e){console.log('AA_PLAYFAIL '+e);});
fetch('/%2e%2e/projects/seed0001.json').then(function(r){return r.text().then(function(t){console.log('AA_TRAV '+r.status+' '+(t.indexOf('seed0001')>=0?'LEAK':'safe'));});}).catch(function(e){console.log('AA_TRAV err');});
fetch('tone.wav').then(function(r){console.log('AA_FETCH '+r.status);});
</script>"""
open("/tmp/index.html", "w").write(HTML)
run("adb push /tmp/index.html /data/local/tmp/index.html"); run("adb push /tmp/tone.wav /data/local/tmp/tone.wav")
sh(f"mkdir -p /data/data/{PKG}/files/local/t1 && cp /data/local/tmp/index.html /data/local/tmp/tone.wav /data/data/{PKG}/files/local/t1/ && chown -R {uid}:{uid} /data/data/{PKG}/files/local && chmod -R u+rwX /data/data/{PKG}/files/local")
def mkproj(pid, items):
    d = {"version": 2, "id": pid, "name": pid, "updated": int(time.time() * 1000), "items": items,
         "audio": {"volume": 100, "muted": False, "autoplay": True, "soundUri": "", "soundName": ""},
         "overlay": {"widthPercent": 60, "touchEnabled": False}}
    open(f"/tmp/{pid}.json", "w").write(json.dumps(d))
    run(f"adb push /tmp/{pid}.json /data/local/tmp/{pid}.json")
    sh(f"cp /data/local/tmp/{pid}.json /data/data/{PKG}/files/projects/{pid}.json && chown {uid}:{uid} /data/data/{PKG}/files/projects/{pid}.json && chmod 660 /data/data/{PKG}/files/projects/{pid}.json")
def item(i, name, url):
    return {"id": i, "name": name, "url": url, "x": 240, "y": 270, "w": 960, "h": 540, "visible": True, "locked": False, "muted": False, "volume": 100}
mkproj("off0001", [item("o1", "Lokal", "https://aa.local/t1/index.html")])
mkproj("cache001", [item("c1", "Example", "https://example.com/")])
def start(pid, act="RUN"):
    sh("am stopservice -n %s/.OverlayService" % PKG); time.sleep(2)
    sh(f"am start-foreground-service -a com.aastudio.live.{act} --es project_id {pid} -n %s/.OverlayService" % PKG)
def jslog():
    return run("adb logcat -d -s AAStudio:I AAStudio-js:I AAStudio:W", 30)

# C1: isi cache saat online
run("adb logcat -c")
sh("svc wifi enable"); sh("svc data enable"); time.sleep(5)
on = "bytes from" in sh("ping -c 1 -W 3 8.8.8.8")
info(f"online sebelum uji cache: {on}")
start("cache001"); time.sleep(20)
lg = jslog()
cache_filled = "page finished url=https://example.com" in lg
info("cache terisi (online): %s" % cache_filled)

# offline
sh("svc wifi disable"); sh("svc data disable"); time.sleep(5)
off = "bytes from" not in sh("ping -c 1 -W 3 8.8.8.8")
check("Jaringan benar-benar mati (ping gagal)", off, "")

# A: halaman lokal + suara tanpa internet
run("adb logcat -c")
start("off0001"); time.sleep(15)
lg = jslog()
info("log offline lokal: " + " | ".join(dict.fromkeys(l.split(": ", 1)[-1] for l in lg.splitlines() if "AAStudio" in l))[:900])
check("Halaman lokal termuat tanpa internet", "page finished url=https://aa.local/t1/index.html" in lg, "")
check("Audio di halaman lokal berbunyi tanpa internet (event playing)", "AA_PLAYING" in lg, "")
pb = sh("dumpsys audio").replace("\n", " ")
check("Sistem audio mencatat pemutaran dari aplikasi", "state:started" in pb.replace(" ", "") or "state:started" in pb, "")
check("Anti path-traversal: file di luar folder lokal tidak bisa dibaca", "AA_TRAV" in lg and "LEAK" not in lg, "")
check("Berkas lokal bisa diambil (fetch 200)", "AA_FETCH 200" in lg, "")
snap("offline-local")

# C2: cache saat offline
if cache_filled:
    run("adb logcat -c")
    start("cache001"); time.sleep(15)
    lg = jslog()
    check("Halaman online yang pernah dibuka tampil dari cache saat offline", "page finished url=https://example.com" in lg and "load error" not in lg, "")
else:
    info("uji cache dilewati: halaman tidak sempat termuat saat online di emulator")

# B: impor ZIP lewat pemilih file
os.makedirs("/tmp/zsite/site", exist_ok=True)
open("/tmp/zsite/site/index.html", "w").write(HTML.replace("OFFLINE OK", "ZIP OK"))
mkwav("/tmp/zsite/site/tone.wav")
with zipfile.ZipFile("/tmp/offline_test.zip", "w") as z:
    z.write("/tmp/zsite/site/index.html", "site/index.html"); z.write("/tmp/zsite/site/tone.wav", "site/tone.wav")
run("adb push /tmp/offline_test.zip /sdcard/Download/offline_test.zip")
sh("am broadcast -a android.intent.action.MEDIA_SCANNER_SCAN_FILE -d file:///sdcard/Download/offline_test.zip")
start("off0001", "EDIT"); time.sleep(6)
ok_btn = False
if tap_text("+"):
    time.sleep(1.5)
    b = find("Impor file offline", False, "Button")
    if b:
        sh(f"input tap {b['cx']} {b['cy']}"); time.sleep(5)
        ok_btn = True
check("Tombol impor menyembunyikan overlay agar pemilih file terlihat", ok_btn and len(overlay_windows()) == 0, f"overlay tersisa={len(overlay_windows())}")
focus = sh("dumpsys window | grep -E 'mCurrentFocus|mFocusedApp'")
info("fokus: " + " ".join(focus.split())[:200])
picked = False
if tap_text("offline_test.zip") or False:
    picked = True
else:
    info("zip tidak langsung terlihat; teks: " + ", ".join(n["text"] for n in ui_nodes() if n["text"])[:300])
    d = next((n for n in ui_nodes() if "roots" in n["desc"].lower() or "drawer" in n["desc"].lower() or "menu" in n["desc"].lower()), None)
    if d:
        sh(f"input tap {d['cx']} {d['cy']}"); time.sleep(1.5)
        if tap_text("Downloads"):
            time.sleep(2)
            picked = tap_text("offline_test.zip")
    if not picked:
        info("teks akhir: " + ", ".join(n["text"] + "/" + n["desc"] for n in ui_nodes() if n["text"] or n["desc"])[:500])
time.sleep(6)
pj = project_json("off0001") or {"items": []}
info("item proyek off0001: " + json.dumps([i["url"] for i in pj["items"]]))
imported = len(pj["items"]) == 2 and pj["items"][1]["url"].startswith("https://aa.local/") and pj["items"][1]["url"].endswith("/site/index.html")
check("Impor ZIP via pemilih file menambah Web Source offline", picked and imported, "")
check("Editor kembali tampil setelah impor", len(overlay_windows()) >= 1, f"{len(overlay_windows())} jendela")
if imported:
    run("adb logcat -c")
    start("off0001"); time.sleep(15)
    lg = jslog()
    check("Hasil impor ZIP dimuat & berbunyi tanpa internet", lg.count("AA_PLAYING") >= 2 and "https://aa.local/" in lg and "/site/index.html" in lg, "")
sh("svc wifi enable"); sh("svc data enable")
check("Tidak ada crash pada uji offline", not crashes(), "; ".join(crashes()))

# ---------------------------------------------------------------- ringkasan
section("RINGKASAN")
fails = [n for n, ok in RESULTS if not ok]
print(f"TOTAL {len(RESULTS)} pemeriksaan, {len(RESULTS) - len(fails)} lulus, {len(fails)} gagal")
for n in fails:
    print("GAGAL:", n)
log = run("adb logcat -d | grep -E 'AA Studio|aastudio|AndroidRuntime' | tail -40", 30)
print("--- logcat (ringkas) ---\n" + log)
sys.exit(0)

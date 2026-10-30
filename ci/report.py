import os
p = "emu_out.txt"
txt = open(p, errors="replace").read() if os.path.exists(p) else "emu_out.txt tidak ada (tes tidak berjalan)"
lines = txt.splitlines()
img = [l for l in lines if l.startswith("IMG ")]
other = [l for l in lines if not l.startswith("IMG ") and (l.startswith(("RESULT", "INFO", "=====", "TOTAL", "GAGAL", "Traceback", "  File", "  ")) or "Error" in l or "---" in l or "E AndroidRuntime" in l or "aaotg" in l)]
body = "\n".join(other) + "\n" + "\n".join(img)
def esc(m):
    return m.replace("%", "%25").replace("\r", "%0D").replace("\n", "%0A")
chunks = [body[i:i + 3800] for i in range(0, len(body), 3800)][:40]
for i, c in enumerate(chunks, 1):
    print("::notice title=Hasil tes %d/%d::%s" % (i, len(chunks), esc(c)))

import os
p = "emu_out.txt"
txt = open(p, errors="replace").read() if os.path.exists(p) else "emu_out.txt tidak ada (tes tidak berjalan)"
keep = [l for l in txt.splitlines() if l.startswith(("RESULT", "INFO", "=====", "TOTAL", "GAGAL", "Traceback", "  File", "  ")) or "Error" in l or "---" in l or "E AndroidRuntime" in l or "aastudio" in l]
body = "\n".join(keep) if keep else txt[-6000:]
def esc(m):
    return m.replace("%", "%25").replace("\r", "%0D").replace("\n", "%0A")
chunks = [body[i:i + 3800] for i in range(0, len(body), 3800)][:12]
for i, c in enumerate(chunks, 1):
    print("::notice title=Hasil tes %d/%d::%s" % (i, len(chunks), esc(c)))

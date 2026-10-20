"""Main-thread time per frame from `dumpsys gfxinfo <pkg> framestats` (input + animation + layout + record).
This part doesn't depend on the emulator's software GPU, so it's a fair measure of the app's own work."""
import sys
label, path = sys.argv[1], sys.argv[2]
rows, header = [], None
for line in open(path, errors="ignore"):
    line = line.strip()
    if line.startswith("Flags,"):
        header = line.rstrip(",").split(",")
        continue
    if header and line and line[0].isdigit() and "," in line:
        v = line.rstrip(",").split(",")
        if len(v) >= len(header):
            rows.append(dict(zip(header, v)))
ui = []
for r in rows:
    try:
        if int(r["Flags"]) != 0:
            continue
        start = int(r.get("HandleInputStart") or r["IntendedVsync"])
        end = int(r["SyncQueued"])
        if end > start > 0:
            ui.append((end - start) / 1e6)
    except (KeyError, ValueError):
        pass
if not ui:
    print(f"{label}: no frames")
else:
    ui.sort()
    p = lambda q: ui[min(len(ui) - 1, int(q * len(ui)))]
    slow = sum(1 for x in ui if x > 16)
    print(f"{label}: {len(ui)} frames, main thread p50 {p(.5):.1f} ms, p90 {p(.9):.1f} ms, max {ui[-1]:.1f} ms, over 16 ms: {slow}")

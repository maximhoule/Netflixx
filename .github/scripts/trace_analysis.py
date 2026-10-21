"""Reads the Perfetto traces from trace.sh and writes a plain-text report: per scenario, frame
timings, the slow frames, and what the main thread and RenderThread spent that time on."""
import glob, os, sys
from collections import defaultdict
from perfetto.trace_processor import TraceProcessor

PKG = "com.maxim.marquee"
out = open(sys.argv[2], "w")


def p(*a):
    print(*a)
    print(*a, file=out)


for path in sorted(glob.glob(os.path.join(sys.argv[1], "*.pftrace"))):
    name = os.path.basename(path).replace(".pftrace", "")
    p(f"\n==================== {name}")
    try:
        tp = TraceProcessor(trace=path)
    except Exception as e:
        p(f"could not open: {e}")
        continue
    procs = list(tp.query(f"select upid, pid from process where name = '{PKG}'"))
    if not procs:
        p("app process not in trace")
        continue
    upid, pid = procs[-1].upid, procs[-1].pid
    threads = {r.utid: (r.tid, r.name) for r in tp.query(f"select utid, tid, name from thread where upid = {upid}")}
    main = [u for u, (tid, _) in threads.items() if tid == pid]
    render = [u for u, (_, n) in threads.items() if n == "RenderThread"]

    def tracks(utids):
        if not utids:
            return "(-1)"
        ids = [str(r.id) for r in tp.query(f"select id from thread_track where utid in ({','.join(map(str, utids))})")]
        return "(" + (",".join(ids) or "-1") + ")"

    mt, rt = tracks(main), tracks(render)
    frames = list(tp.query(f"select id, ts, dur from slice where track_id in {mt} and name like 'Choreographer#doFrame%' and depth = 0 order by ts"))
    if frames:
        durs = sorted(f.dur / 1e6 for f in frames)
        q = lambda x: durs[min(len(durs) - 1, int(len(durs) * x))]
        p(f"main-thread frames: {len(frames)}  p50 {q(.5):.1f} ms  p90 {q(.9):.1f}  p99 {q(.99):.1f}  max {durs[-1]:.1f}  >16ms: {sum(d > 16 for d in durs)}  >33ms: {sum(d > 33 for d in durs)}")
    # Jank as Android judged it (actual vs expected frame deadlines).
    try:
        jank = list(tp.query(f"select jank_type, count(*) c from actual_frame_timeline_slice where upid = {upid} group by jank_type order by c desc"))
        p("frame timeline: " + ", ".join(f"{j.jank_type}: {j.c}" for j in jank))
    except Exception as e:
        p(f"frame timeline n/a ({e})")

    # Self time of every slice inside slow main-thread frames, summed by name.
    slow = [f for f in frames if f.dur > 12e6]
    selftime = defaultdict(float)
    count = defaultdict(int)
    for f in slow:
        rows = list(tp.query(
            f"select s.id, s.name, s.dur, s.parent_id from descendant_slice({f.id}) s"))
        child = defaultdict(float)
        for r in rows:
            if r.parent_id is not None:
                child[r.parent_id] += r.dur
        for r in rows:
            nm = r.name
            # Group per-item names (e.g. composables with ids) loosely.
            selftime[nm] += (r.dur - child.get(r.id, 0)) / 1e6
            count[nm] += 1
    if slow:
        p(f"\nslow main-thread frames (>12 ms): {len(slow)}, total {sum(f.dur for f in slow) / 1e6:.0f} ms. Where the time went (self time):")
        for nm, t in sorted(selftime.items(), key=lambda kv: -kv[1])[:45]:
            p(f"  {t:8.1f} ms  x{count[nm]:<5} {nm[:150]}")
        p("\n5 slowest frames, biggest slices inside (depth <= 6):")
        for f in sorted(slow, key=lambda f: -f.dur)[:5]:
            p(f" frame at {(f.ts - frames[0].ts) / 1e9:6.2f}s: {f.dur / 1e6:.1f} ms")
            rows = list(tp.query(f"select name, dur, depth from descendant_slice({f.id}) where depth <= 6 order by dur desc limit 14"))
            for r in rows:
                p(f"     {r.dur / 1e6:7.1f} ms  d{r.depth} {r.name[:140]}")
    # Main thread: work outside frames (input handling, coroutines, binder) longer than 8 ms.
    other = list(tp.query(
        f"select name, dur from slice where track_id in {mt} and depth = 0 and name not like 'Choreographer#doFrame%' and dur > 8e6 order by dur desc limit 15"))
    if other:
        p("\nother long main-thread work:")
        for r in other:
            p(f"  {r.dur / 1e6:7.1f} ms  {r.name[:150]}")
    # Key presses that took long to handle: what inside (focus search, scrolling, building cards...).
    keys = list(tp.query(
        f"select id, dur from slice where track_id in {mt} and name like 'ViewPostImeInputStage%' and dur > 10e6"))
    if keys:
        agg = defaultdict(float); cnt = defaultdict(int)
        for k in keys:
            rows = list(tp.query(f"select s.id, s.name, s.dur, s.parent_id from descendant_slice({k.id}) s"))
            child = defaultdict(float)
            for r in rows:
                if r.parent_id is not None:
                    child[r.parent_id] += r.dur
            own = k.dur - sum(r.dur for r in rows if r.parent_id == k.id)
            agg["(key handling itself, untraced)"] += own / 1e6
            for r in rows:
                agg[r.name] += (r.dur - child.get(r.id, 0)) / 1e6
                cnt[r.name] += 1
        p(f"\nslow key presses (>10 ms): {len(keys)}, total {sum(k.dur for k in keys) / 1e6:.0f} ms. Inside them (self time):")
        for nm, t in sorted(agg.items(), key=lambda kv: -kv[1])[:20]:
            p(f"  {t:8.1f} ms  x{cnt[nm]:<5} {nm[:150]}")
    # Main thread waiting for a CPU (another thread or process had it).
    try:
        st = list(tp.query(
            f"select state, sum(dur) d from thread_state where utid in ({','.join(map(str, main)) or -1}) group by state order by d desc"))
        p("main thread states: " + ", ".join(f"{s.state}: {s.d / 1e6:.0f} ms" for s in st))
    except Exception:
        pass
    # RenderThread.
    rf = list(tp.query(f"select id, dur from slice where track_id in {rt} and name like 'DrawFrame%' and depth = 0"))
    if rf:
        durs = sorted(f.dur / 1e6 for f in rf)
        p(f"\nRenderThread frames: {len(durs)}  p50 {durs[len(durs) // 2]:.1f} ms  p90 {durs[int(len(durs) * .9)]:.1f}  max {durs[-1]:.1f}")
        top = list(tp.query(
            f"select s.name, sum(s.dur) d, count(*) c from slice s where s.track_id in {rt} and s.depth > 0 group by s.name order by d desc limit 12"))
        for r in top:
            p(f"  {r.d / 1e6:8.1f} ms  x{r.c:<5} {r.name[:120]}")
    # Busiest other app threads (image decoding, networking...).
    busy = list(tp.query(
        f"select t.name, sum(ts.dur) d from thread_state ts join thread t using(utid) where t.upid = {upid} and ts.state = 'Running' group by t.utid order by d desc limit 10"))
    p("\nCPU time by app thread: " + ", ".join(f"{b.name}: {b.d / 1e6:.0f} ms" for b in busy))
    tp.close()
out.close()

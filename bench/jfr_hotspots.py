"""
Summarises a JFR profile from perf_compare.py: per-thread self and inclusive (total) sample counts for game methods.

Usage:  python bench/jfr_hotspots.py <profile.jfr> [--threads MainThread,main] [--top 40] [--from-sec N]
                                     [--slow-ms 16.7] [--native]

--from-sec   skip samples from the first N seconds (menu and save loading)
--slow-ms    only count samples that fall inside benchmark frames (pzo.BenchFrame events) at least this long, so the
             report shows what causes the 1% lows rather than the average frame
--native     also count jdk.NativeMethodSample events (time in native code, e.g. OpenGL on the render thread "main")
"""

import argparse
import bisect
import collections
import json
import subprocess
import sys

JFR_TOOL = r"C:\Program Files\Java\jdk-26.0.2.1\bin\jfr.exe"


def frame_name(fr):
    m = fr.get("method", {})
    t = m.get("type", {}).get("name", "?")
    return "%s.%s" % (t.replace("/", "."), m.get("name", "?"))


def thread_key(name, wanted):
    for w in wanted:
        if name == w or (w != "main" and w.lower() in name.lower()):
            return w
    return None


def iso_seconds(ts):
    # "2026-10-22T15:51:02.123456789+08:00" -> seconds since midnight; only differences are used
    t = ts.split("T", 1)[1]
    for sep in ("+", "-", "Z"):
        if sep in t:
            t = t.split(sep, 1)[0]
    h, m, s = t.split(":")
    return int(h) * 3600 + int(m) * 60 + float(s)


def iso_duration(d):
    # "PT0.012345678S", "PT1M2.5S"
    d = d.replace("PT", "")
    total = 0.0
    num = ""
    for ch in d:
        if ch.isdigit() or ch in ".-":
            num += ch
        else:
            v = float(num) if num else 0.0
            total += v * {"H": 3600, "M": 60, "S": 1}.get(ch, 0)
            num = ""
    return total


def jfr_events(path, events):
    raw = subprocess.run([JFR_TOOL, "print", "--json", "--stack-depth", "64", "--events", events, path],
                         capture_output=True, text=True, encoding="utf-8", errors="replace")
    if raw.returncode != 0:
        sys.exit(raw.stderr)
    return json.loads(raw.stdout)["recording"]["events"]


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("jfr")
    ap.add_argument("--threads", default="MainThread,main")
    ap.add_argument("--top", type=int, default=40)
    ap.add_argument("--from-sec", type=float, default=0.0)
    ap.add_argument("--slow-ms", type=float, default=0.0)
    ap.add_argument("--native", action="store_true")
    args = ap.parse_args()
    wanted = [t.strip() for t in args.threads.split(",") if t.strip()]

    kinds = "jdk.ExecutionSample" + (",jdk.NativeMethodSample" if args.native else "")
    events = jfr_events(args.jfr, kinds)
    if not events:
        sys.exit("no samples")
    t0 = min(iso_seconds(e["values"]["startTime"]) for e in events)

    slow = None
    if args.slow_ms > 0:
        frames = jfr_events(args.jfr, "pzo.BenchFrame")
        if not frames:
            sys.exit("no pzo.BenchFrame events in this recording")
        spans = []
        all_ms = []
        for f in frames:
            st = iso_seconds(f["values"]["startTime"])
            dur = iso_duration(f["values"]["duration"])
            all_ms.append(dur * 1000.0)
            if dur * 1000.0 >= args.slow_ms:
                spans.append((st, st + dur))
        spans.sort()
        slow = ([s for s, _ in spans], [e for _, e in spans])
        all_ms.sort()
        n = len(all_ms)
        print("Bench frames: %d, slow (>= %.1f ms): %d (%.1f%%), time in slow frames: %.1f%%, p50 %.2f ms, p99 %.2f ms, max %.1f ms" % (
            n, args.slow_ms, len(spans), 100.0 * len(spans) / n,
            100.0 * sum(e - s for s, e in spans) / max(1e-9, sum(all_ms) / 1000.0),
            all_ms[n // 2], all_ms[min(n - 1, int(n * 0.99))], all_ms[-1]))

    def in_slow(t):
        if slow is None:
            return True
        i = bisect.bisect_right(slow[0], t) - 1
        return i >= 0 and t <= slow[1][i]

    total = collections.Counter()
    self_c = collections.defaultdict(collections.Counter)
    incl_c = collections.defaultdict(collections.Counter)
    callers = collections.defaultdict(collections.Counter)
    all_threads = collections.Counter()
    for e in events:
        v = e["values"]
        ts = iso_seconds(v["startTime"])
        if ts - t0 < args.from_sec or not in_slow(ts):
            continue
        thr = v.get("sampledThread") or {}
        tname = thr.get("javaName") or thr.get("osName") or "?"
        all_threads[tname] += 1
        key = thread_key(tname, wanted)
        if key is None:
            continue
        frames = (v.get("stackTrace") or {}).get("frames") or []
        if not frames:
            continue
        total[key] += 1
        top = frame_name(frames[0])
        self_c[key][top] += 1
        # First game frame below a JDK/library leaf, so HashMap.getNode etc. are attributed to who calls them
        for fr in frames[1:12]:
            n = frame_name(fr)
            if n.startswith("zombie."):
                callers[(key, top)][n] += 1
                break
        seen = set()
        for fr in frames:
            n = frame_name(fr)
            if n not in seen:
                seen.add(n)
                incl_c[key][n] += 1

    print("Samples by thread (top 15):")
    for n, c in all_threads.most_common(15):
        print("  %6d  %s" % (c, n))
    for key in wanted:
        if not total[key]:
            continue
        print("\n=== %s: %d samples ===" % (key, total[key]))
        print("-- self (top %d)" % args.top)
        for n, c in self_c[key].most_common(args.top):
            print("  %5.1f%%  %s" % (100.0 * c / total[key], n))
            if not n.startswith("zombie."):
                for cn, cc in callers[(key, n)].most_common(3):
                    print("            <- %4.1f%%  %s" % (100.0 * cc / total[key], cn))
        print("-- inclusive, zombie.* only (top %d)" % args.top)
        shown = 0
        for n, c in incl_c[key].most_common():
            if not n.startswith("zombie."):
                continue
            print("  %5.1f%%  %s" % (100.0 * c / total[key], n))
            shown += 1
            if shown >= args.top:
                break


if __name__ == "__main__":
    main()

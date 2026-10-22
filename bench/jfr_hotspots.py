"""
Summarises a JFR profile from perf_compare.py: per-thread self and inclusive (total) sample counts for game methods.

Usage:  python bench/jfr_hotspots.py <profile.jfr> [--threads MainThread,RenderThread] [--top 40] [--from-sec N]
--from-sec skips samples from the first N seconds (menu and save loading), so only in-game frames are counted.
"""

import argparse
import collections
import json
import os
import subprocess
import sys

JFR_TOOL = r"C:\Program Files\Java\jdk-26.0.2.1\bin\jfr.exe"


def frame_name(fr):
    m = fr.get("method", {})
    t = m.get("type", {}).get("name", "?")
    return "%s.%s" % (t.replace("/", "."), m.get("name", "?"))


def thread_key(name, wanted):
    for w in wanted:
        if w.lower() in name.lower():
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


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("jfr")
    ap.add_argument("--threads", default="MainThread,RenderThread,PZO-MainSimulationThread")
    ap.add_argument("--top", type=int, default=40)
    ap.add_argument("--from-sec", type=float, default=0.0)
    args = ap.parse_args()
    wanted = [t.strip() for t in args.threads.split(",") if t.strip()]

    raw = subprocess.run([JFR_TOOL, "print", "--json", "--stack-depth", "64", "--events", "jdk.ExecutionSample", args.jfr],
                         capture_output=True, text=True, encoding="utf-8", errors="replace")
    if raw.returncode != 0:
        sys.exit(raw.stderr)
    events = json.loads(raw.stdout)["recording"]["events"]
    if not events:
        sys.exit("no execution samples")
    t0 = min(iso_seconds(e["values"]["startTime"]) for e in events)

    total = collections.Counter()
    self_c = collections.defaultdict(collections.Counter)
    incl_c = collections.defaultdict(collections.Counter)
    callers = collections.defaultdict(collections.Counter)
    all_threads = collections.Counter()
    for e in events:
        v = e["values"]
        if iso_seconds(v["startTime"]) - t0 < args.from_sec:
            continue
        tname = (v.get("sampledThread") or {}).get("javaName") or (v.get("sampledThread") or {}).get("osName") or "?"
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

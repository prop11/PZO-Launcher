"""
Stock vs PZO performance A/B with Java Flight Recorder profiles.

Uses the same in-engine BenchmarkHarness route as fsr_compare.py, run twice on byte-identical copies of a save:

  stock  vanilla JVM flags, no -agentlib; PZOEntrypoint only drives the harness (stock=1 in the bench config),
         so no bytecode patch or governor is active
  pzo    the launcher JSON exactly as installed
  pzo_nocap  as pzo, with the unseen-zombie simulation cap disabled (simcap=0)

Both runs add -XX:StartFlightRecording; BenchmarkHarness dumps it to <run>/profile.jfr before quitting.
ProjectZomboid64.json is backed up and restored around each run, like latestSave.ini and options.ini.

Usage:  python bench/perf_compare.py [--source Mode/SaveName] [--refresh-template] [--zooms 1.0,2.0]
                                     [--route-tiles 40] [--route-speed 8] [--runs stock,pzo] [--repeat 1] [--threat]
                                     [--weather storm] [--time 19] [--route line --route-dir W --route-tiles 1000 --route-speed 33]
"""

import argparse
import datetime
import json
import os
import shutil
import sys

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
import fsr_compare as fc  # noqa: E402

# Vanilla 42.21 launcher flags; -Xmx matches the PZO install so heap size is not what is being compared
STOCK_VM_ARGS = [
    "--enable-native-access=ALL-UNNAMED",
    "--add-exports=java.base/jdk.internal.misc=ALL-UNNAMED",
    "-Xmx8192m",
    "-Dzomboid.steam=1",
    "-Dzomboid.znetlog=1",
    "-Djava.library.path=win64/;.",
    "-XX:-CreateCoredumpOnCrash",
    "-XX:-OmitStackTraceInFastThrow",
    "-XX:+UseZGC",
    # Loads the agent through PZO's native bridge, as the PZO launch does (plain -javaagent does not survive the
    # game launcher). Stock mode then registers only the harness's render thread timer; no game patch is applied
    "-agentlib:pzo_native64",
]


def jfr_arg(run_dir):
    return "-XX:StartFlightRecording=settings=profile,filename=%s" % os.path.join(run_dir, "exit.jfr")


def write_launcher_json(path, original, run, run_dir):
    data = json.loads(json.dumps(original))
    if run == "stock":
        data["vmArgs"] = list(STOCK_VM_ARGS)
    data["vmArgs"] = [a for a in data["vmArgs"] if not a.startswith("-XX:StartFlightRecording")]
    data["vmArgs"].append(jfr_arg(run_dir))
    with open(path, "w", encoding="utf-8") as f:
        json.dump(data, f, indent=2)


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--game-dir", default=r"K:\SteamLibrary\steamapps\common\ProjectZomboid")
    ap.add_argument("--out", default=os.path.join(fc.ZOMBOID, "pzo_bench", "perf_" + datetime.datetime.now().strftime("%Y%m%d_%H%M%S")))
    ap.add_argument("--source", help="Mode/SaveName for the bench template (default: current latest save)")
    ap.add_argument("--refresh-template", action="store_true")
    ap.add_argument("--zooms", default="1.0,2.0")
    ap.add_argument("--route-tiles", type=int, default=40)
    ap.add_argument("--route-speed", type=float, default=8.0)
    ap.add_argument("--timeout", type=int, default=600)
    ap.add_argument("--runs", default="stock,pzo")
    ap.add_argument("--repeat", type=int, default=1)
    ap.add_argument("--threat", action="store_true",
                    help="zombies see and attack the player (death still prevented) so AI/combat cost is measured")
    ap.add_argument("--weather", choices=["clear", "storm"], default="clear",
                    help="storm: forced heavy rain, full fog, wind and periodic lightning")
    ap.add_argument("--time", type=float, default=12.0, help="time of day (hours)")
    ap.add_argument("--route", choices=["square", "line"], default="square",
                    help="line: one straight leg of --route-tiles at --route-speed (fast travel)")
    ap.add_argument("--route-dir", choices=["E", "W", "N", "S"], default="W")
    args = ap.parse_args()

    if fc.game_running():
        sys.exit("Project Zomboid is already running - close it first.")
    if args.source:
        mode, name = args.source.replace("\\", "/").split("/", 1)
        source = (mode, name)
    else:
        source = fc.read_latest_save()
        if source[1] in (fc.WORK_NAME, fc.TEMPLATE_NAME):
            sys.exit("latestSave.ini points at the bench save (an interrupted run?); pass --source Mode/SaveName")
    mode, template = fc.ensure_template(source, args.refresh_template)
    os.makedirs(args.out, exist_ok=True)

    launcher_json = os.path.join(args.game_dir, "ProjectZomboid64.json")
    with open(launcher_json, encoding="utf-8") as f:
        original_text = f.read()
    original = json.loads(original_text)
    shutil.copy2(launcher_json, os.path.join(args.out, "ProjectZomboid64.json.original"))

    summary = []
    try:
        for rep in range(args.repeat):
            for run in [r.strip() for r in args.runs.split(",") if r.strip()]:
                label = run if args.repeat == 1 else "%s_%d" % (run, rep + 1)
                run_dir = os.path.join(args.out, label)
                os.makedirs(run_dir, exist_ok=True)
                print("[%s]" % label)
                write_launcher_json(launcher_json, original, run, run_dir)
                extra = {"stock": 1} if run == "stock" else {}
                if run == "pzo_nocap":
                    extra["simcap"] = 0
                if args.threat:
                    extra["threat"] = 1
                extra.update({"weather": args.weather, "time": args.time, "route": args.route, "route_dir": args.route_dir})
                data = fc.run_game(args, mode, template, label, run_dir, [], extra)
                if data is None:
                    print("  no results.json (see %s)" % run_dir)
                    summary.append((label, None))
                    continue
                if not data.get("ok"):
                    print("  run failed:", "; ".join(data.get("notes", [])))
                for z in data.get("zooms", []):
                    print("  zoom %.2f: %.1f fps (1%% low %.1f, p99 %.2f ms)" % (z["zoom"], z["avg_fps"], z["low1_fps"], z["p99_ms"]))
                summary.append((label, data))
    finally:
        with open(launcher_json, "w", encoding="utf-8") as f:
            f.write(original_text)

    with open(os.path.join(args.out, "summary.json"), "w", encoding="utf-8") as f:
        json.dump([{"label": l, "result": d} for l, d in summary], f, indent=2)
    print("results:", args.out)


if __name__ == "__main__":
    main()

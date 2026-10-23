"""
FSR A/B harness for the PZO engine.

Runs Project Zomboid twice -- native resolution, then -pzo_fsr -- through the in-engine BenchmarkHarness and
compares the runs. Modelled on the PZ_Optimization harness (github.com/xD3I/PZ_Optimization, harness/run-win.ps1):

  1. a bench save template is copied once from a real save (--source, default: the current latestSave.ini entry);
     every run gets a fresh copy of it, so both runs start from byte-identical world data and the player's own
     save is never opened
  2. latestSave.ini is pointed at the copy and ~/Zomboid/Lua/pzo-bench.txt carries the run settings
     (no launch arguments to forward, except -pzo_fsr itself)
  3. the game continues the save, clicks through "Click to Start", walks a square teleport route at a fixed
     speed per zoom level while sampling frame times, takes screenshots paused at the start tile and quits
  4. latestSave.ini, options.ini and the config file are restored and the working copy deleted, whatever happens

Usage:  python bench/fsr_compare.py [--source Mode/SaveName] [--refresh-template] [--zooms 1.0,2.0]
                                    [--route-tiles 40] [--route-speed 8] [--timeout SECONDS]
Requires Steam to be running, the game to be closed, and the game window to keep focus (it pauses otherwise).
"""
import argparse
import datetime
import html
import json
import os
import shutil
import subprocess
import sys
import time

import numpy as np
from PIL import Image

RUNS = [
    ("native", []),
    ("fsr", ["-pzo_fsr"]),
]

ZOMBOID = os.path.join(os.path.expanduser("~"), "Zomboid")
SAVES = os.path.join(ZOMBOID, "Saves")
LATEST_SAVE_INI = os.path.join(ZOMBOID, "latestSave.ini")
OPTIONS_INI = os.path.join(ZOMBOID, "options.ini")
CONFIG_FILE = os.path.join(ZOMBOID, "Lua", "pzo-bench.txt")
TEMPLATE_NAME = "pzo-bench-template"
WORK_NAME = "pzo-bench"


def game_running():
    out = subprocess.run(["tasklist", "/FI", "IMAGENAME eq ProjectZomboid64.exe"], capture_output=True, text=True).stdout
    return "ProjectZomboid64.exe" in out


def read_latest_save():
    with open(LATEST_SAVE_INI, encoding="utf-8") as f:
        lines = [l.strip() for l in f.read().splitlines() if l.strip()]
    return lines[1], lines[0]   # (game mode, save name)


def ensure_template(source, refresh):
    mode, name = source
    src = os.path.join(SAVES, mode, name)
    template = os.path.join(SAVES, mode, TEMPLATE_NAME)
    if refresh and os.path.isdir(template):
        shutil.rmtree(template)
    if not os.path.isdir(template):
        if not os.path.isdir(src):
            sys.exit("source save not found: " + src)
        print("creating bench template from %s/%s" % (mode, name))
        shutil.copytree(src, template)
    return mode, template


class Backups:
    """Byte-exact copies of every file the run touches; restore() puts them back (or removes new ones)."""

    def __init__(self):
        self.files = {}

    def keep(self, path):
        if path not in self.files:
            self.files[path] = open(path, "rb").read() if os.path.exists(path) else None

    def restore(self):
        for path, data in self.files.items():
            if data is None:
                if os.path.exists(path):
                    os.remove(path)
            else:
                with open(path, "wb") as f:
                    f.write(data)


def limit_cores(proc, cores):
    """Pins the game to its first `cores` physical cores (with their SMT siblings) to stand in for a smaller CPU.

    Windows numbers the two logical processors of a core next to each other, so N cores are logical 0 .. 2N-1 on an
    SMT CPU. Threads that later ask for their own affinity can only narrow this mask, never widen it."""
    if not cores or os.name != "nt":
        return
    import ctypes
    logical = os.cpu_count() or 1
    smt = 2 if logical >= 2 * cores else 1
    mask = (1 << min(logical, cores * smt)) - 1
    if not ctypes.windll.kernel32.SetProcessAffinityMask(int(proc._handle), ctypes.c_size_t(mask)):
        print("  could not limit the game to %d cores" % cores)
    else:
        print("  limited to %d cores (affinity mask 0x%X)" % (cores, mask))


def focus_game_window(pid):
    """Brings the game's top-level window to the front. The game opens behind whatever had focus, and an unfocused
    window loses Windows' foreground boost and tends to land on efficiency cores, which skews every number.

    Windows only lets the foreground process hand out focus, so this attaches to the foreground thread's input
    first and falls back to a synthetic Alt press. Returns True when the game window is in front."""
    if os.name != "nt":
        return True
    import ctypes
    from ctypes import wintypes
    user32 = ctypes.windll.user32
    found = []

    @ctypes.WINFUNCTYPE(wintypes.BOOL, wintypes.HWND, wintypes.LPARAM)
    def visit(hwnd, _):
        owner = wintypes.DWORD()
        user32.GetWindowThreadProcessId(hwnd, ctypes.byref(owner))
        if owner.value == pid and user32.IsWindowVisible(hwnd) and user32.GetWindowTextLengthW(hwnd) > 0:
            found.append(hwnd)
            return False
        return True

    user32.EnumWindows(visit, 0)
    if not found:
        return False
    hwnd = found[0]
    fg = user32.GetForegroundWindow()
    if fg == hwnd:
        return True
    if user32.IsIconic(hwnd):
        user32.ShowWindow(hwnd, 9)  # SW_RESTORE
    fg_thread = user32.GetWindowThreadProcessId(fg, None)
    me = ctypes.windll.kernel32.GetCurrentThreadId()
    attached = fg_thread and fg_thread != me and user32.AttachThreadInput(me, fg_thread, True)
    user32.BringWindowToTop(hwnd)
    user32.SetForegroundWindow(hwnd)
    if attached:
        user32.AttachThreadInput(me, fg_thread, False)
    if user32.GetForegroundWindow() != hwnd:
        user32.keybd_event(0x12, 0, 0, 0)  # Alt down/up lifts the foreground lock
        user32.keybd_event(0x12, 0, 2, 0)
        user32.SetForegroundWindow(hwnd)
    return user32.GetForegroundWindow() == hwnd


def run_game(args, mode, template, label, run_dir, extra_args, extra_config=None):
    exe = os.path.join(args.game_dir, "ProjectZomboid64.exe")
    os.makedirs(run_dir, exist_ok=True)
    results = os.path.join(run_dir, "results.json")
    if os.path.exists(results):
        os.remove(results)

    work = os.path.join(SAVES, mode, WORK_NAME)
    backups = Backups()
    try:
        if os.path.isdir(work):
            shutil.rmtree(work)
        shutil.copytree(template, work)
        for path in (LATEST_SAVE_INI, OPTIONS_INI, CONFIG_FILE):
            backups.keep(path)
        with open(LATEST_SAVE_INI, "w", encoding="utf-8", newline="\r\n") as f:
            f.write("%s\n%s\n" % (WORK_NAME, mode))
        with open(CONFIG_FILE, "w", encoding="utf-8") as f:
            f.write("out=%s\nlabel=%s\nzooms=%s\nroute_tiles=%d\nroute_speed=%s\n" % (
                run_dir, label, args.zooms, args.route_tiles, args.route_speed))
            for k, v in (extra_config or {}).items():
                f.write("%s=%s\n" % (k, v))

        cmd = [exe] + extra_args
        print("  launching:", " ".join(cmd))
        proc = subprocess.Popen(cmd, cwd=args.game_dir)
        limit_cores(proc, getattr(args, "cores", 0))
        start = time.time()
        keep_focus = not getattr(args, "no_focus", False)
        focus_reported = False
        while proc.poll() is None:
            if time.time() - start > args.timeout:
                print("  timeout, terminating game")
                subprocess.run(["taskkill", "/F", "/IM", "ProjectZomboid64.exe"], capture_output=True)
                break
            # Only until the window has opened and taken focus once; after that the user's desktop is left alone
            if keep_focus and not focus_reported and focus_game_window(proc.pid):
                print("  game window focused")
                focus_reported = True
            time.sleep(2)
        time.sleep(3)
        if game_running():
            subprocess.run(["taskkill", "/F", "/IM", "ProjectZomboid64.exe"], capture_output=True)
            time.sleep(3)
    finally:
        backups.restore()
        if os.path.isdir(work):
            shutil.rmtree(work, ignore_errors=True)

    for name in ("pzo_engine.log",):
        src = os.path.join(ZOMBOID, "Lua", name)
        if os.path.exists(src):
            shutil.copy2(src, os.path.join(run_dir, name))
    console = os.path.join(ZOMBOID, "console.txt")
    if os.path.exists(console):
        shutil.copy2(console, os.path.join(run_dir, "console.txt"))
    if not os.path.exists(results):
        return None
    with open(results, encoding="utf-8") as f:
        data = json.load(f)
    # Screenshots land in the game's screenshot directory; collect them next to the results
    shot_dir = data.get("screenshot_dir") or os.path.join(ZOMBOID, "Screenshots")
    for z in data.get("zooms", []):
        for key in ("shot_noui", "shot_ui"):
            src = os.path.join(shot_dir, z[key])
            if os.path.exists(src):
                shutil.move(src, os.path.join(run_dir, z[key]))
    return data


def psnr(a, b):
    mse = np.mean((a.astype(np.float64) - b.astype(np.float64)) ** 2)
    return float("inf") if mse == 0 else 10 * np.log10(255.0 ** 2 / mse)


def sharpness(a):
    # Mean gradient magnitude of luma; higher means more fine detail survived
    g = a.astype(np.float64) @ np.array([0.299, 0.587, 0.114])
    return float(np.mean(np.abs(np.diff(g, axis=0))) + np.mean(np.abs(np.diff(g, axis=1))))


def compare_images(path_a, path_b, out_prefix):
    a = np.asarray(Image.open(path_a).convert("RGB"))
    b = np.asarray(Image.open(path_b).convert("RGB"))
    if a.shape != b.shape:
        return {"error": "size mismatch %s vs %s" % (a.shape, b.shape)}
    diff = np.abs(a.astype(np.int16) - b.astype(np.int16)).max(axis=2).astype(np.uint8)
    heat = np.clip(diff.astype(np.float32) * 4.0, 0, 255).astype(np.uint8)
    heat_img = Image.fromarray(np.stack([heat, heat // 3, 255 - heat], axis=2))
    heat_img.save(out_prefix + "_diff.png")
    # Side-by-side 2x crop from the centre so FSR detail is visible
    h, w = a.shape[:2]
    cw, ch = min(480, w // 2), min(270, h // 2)
    x0, y0 = (w - cw) // 2, (h - ch) // 2
    crop_a = Image.fromarray(a[y0:y0 + ch, x0:x0 + cw]).resize((cw * 2, ch * 2), Image.NEAREST)
    crop_b = Image.fromarray(b[y0:y0 + ch, x0:x0 + cw]).resize((cw * 2, ch * 2), Image.NEAREST)
    side = Image.new("RGB", (cw * 4 + 8, ch * 2), (40, 40, 40))
    side.paste(crop_a, (0, 0))
    side.paste(crop_b, (cw * 2 + 8, 0))
    side.save(out_prefix + "_crop.png")
    return {
        "psnr_db": round(psnr(a, b), 2),
        "mean_abs_diff": round(float(np.mean(np.abs(a.astype(np.int16) - b.astype(np.int16)))), 3),
        "pixels_changed_pct": round(float(np.mean(diff > 8)) * 100, 2),
        "sharpness_native": round(sharpness(a), 3),
        "sharpness_fsr": round(sharpness(b), 3),
        "diff_png": os.path.basename(out_prefix + "_diff.png"),
        "crop_png": os.path.basename(out_prefix + "_crop.png"),
    }


def write_report(out_dir, runs, comparisons):
    rows = []
    nat, fsr = runs.get("native"), runs.get("fsr")
    for zn, zf in zip(nat["zooms"], fsr["zooms"]):
        gain = (zf["avg_fps"] / zn["avg_fps"] - 1) * 100 if zn["avg_fps"] else 0
        rows.append("<tr><td>%.2f</td><td>%.1f</td><td>%.1f</td><td>%+.1f%%</td><td>%.1f</td><td>%.1f</td><td>%.2f</td><td>%.2f</td></tr>" % (
            zn["zoom"], zn["avg_fps"], zf["avg_fps"], gain, zn["low1_fps"], zf["low1_fps"], zn["p99_ms"], zf["p99_ms"]))
    shots = []
    for name, c in comparisons:
        if "error" in c:
            shots.append("<h3>%s</h3><p>%s</p>" % (html.escape(name), html.escape(c["error"])))
            continue
        shots.append(
            "<h3>%s</h3><p>PSNR %.2f dB &middot; mean abs diff %.3f &middot; %.2f%% pixels changed &middot; "
            "sharpness native %.3f vs FSR %.3f</p>"
            "<p><b>Centre crop, 2x (left native, right FSR)</b><br><img src='%s'></p>"
            "<p><b>Difference heatmap</b><br><img src='%s' style='max-width:100%%'></p>" % (
                html.escape(name), c["psnr_db"], c["mean_abs_diff"], c["pixels_changed_pct"],
                c["sharpness_native"], c["sharpness_fsr"], c["crop_png"], c["diff_png"]))
    route = fsr.get("route", {})
    page = """<!doctype html><meta charset="utf-8"><title>PZO FSR A/B</title>
<style>body{font-family:system-ui,sans-serif;background:#16181c;color:#e6e6e6;max-width:1400px;margin:auto;padding:16px}
table{border-collapse:collapse}td,th{border:1px solid #444;padding:4px 10px;text-align:right}img{max-width:100%%}</style>
<h1>PZO FSR A/B report</h1><p>%s &middot; screen %s &middot; FSR scale %s &middot; frames upscaled %s &middot; shaders rewritten %s</p>
<p>native notes: %s<br>fsr notes: %s</p>
<h2>Frame rate (square teleport route, %s tiles per side at %s tiles/s, uncapped, vsync off)</h2>
<table><tr><th>zoom</th><th>native fps</th><th>FSR fps</th><th>gain</th><th>native 1%% low</th><th>FSR 1%% low</th><th>native p99 ms</th><th>FSR p99 ms</th></tr>%s</table>
<h2>Image comparison (paused at the start tile)</h2>%s""" % (
        datetime.datetime.now().strftime("%Y-%m-%d %H:%M"), fsr.get("screen"), fsr.get("fsr_render_scale"),
        fsr.get("fsr_frames_upscaled"), fsr.get("fsr_shaders_rewritten"),
        html.escape(", ".join(nat.get("notes", []))), html.escape(", ".join(fsr.get("notes", []))),
        route.get("tiles"), route.get("tiles_per_s"), "".join(rows), "".join(shots))
    with open(os.path.join(out_dir, "report.html"), "w", encoding="utf-8") as f:
        f.write(page)


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--game-dir", default=r"K:\SteamLibrary\steamapps\common\ProjectZomboid")
    ap.add_argument("--out", default=os.path.join(ZOMBOID, "pzo_bench", datetime.datetime.now().strftime("%Y%m%d_%H%M%S")))
    ap.add_argument("--source", help="Mode/SaveName for the bench template (default: current latest save)")
    ap.add_argument("--refresh-template", action="store_true", help="re-copy the template from --source")
    ap.add_argument("--zooms", default="1.0,2.0")
    ap.add_argument("--route-tiles", type=int, default=40)
    ap.add_argument("--route-speed", type=float, default=8.0)
    ap.add_argument("--timeout", type=int, default=600)
    args = ap.parse_args()

    if game_running():
        sys.exit("Project Zomboid is already running - close it first.")
    if args.source:
        mode, name = args.source.replace("\\", "/").split("/", 1)
        source = (mode, name)
    else:
        source = read_latest_save()
        if source[1] in (WORK_NAME, TEMPLATE_NAME):
            sys.exit("latestSave.ini points at the bench save (an interrupted run?); pass --source Mode/SaveName")
    mode, template = ensure_template(source, args.refresh_template)
    os.makedirs(args.out, exist_ok=True)

    runs = {}
    for label, extra in RUNS:
        print("[%s]" % label)
        data = run_game(args, mode, template, label, os.path.join(args.out, label), extra)
        if data is None:
            sys.exit("  run '%s' produced no results.json (see %s)" % (label, os.path.join(args.out, label)))
        if not data.get("ok"):
            print("  run failed:", "; ".join(data.get("notes", [])))
            sys.exit("  aborting: '%s' did not complete (results in %s)" % (label, os.path.join(args.out, label)))
        print("  ok=%s fsr_active=%s frames_upscaled=%s" % (data["ok"], data["fsr_active"], data["fsr_frames_upscaled"]))
        for z in data["zooms"]:
            print("  zoom %.2f: %.1f fps (1%% low %.1f, p99 %.2f ms)" % (z["zoom"], z["avg_fps"], z["low1_fps"], z["p99_ms"]))
        runs[label] = data

    comparisons = []
    for zn, zf in zip(runs["native"]["zooms"], runs["fsr"]["zooms"]):
        for key in ("shot_noui", "shot_ui"):
            a = os.path.join(args.out, "native", zn[key])
            b = os.path.join(args.out, "fsr", zf[key])
            name = "zoom %.2f %s" % (zn["zoom"], "with UI" if key == "shot_ui" else "world only")
            if os.path.exists(a) and os.path.exists(b):
                c = compare_images(a, b, os.path.join(args.out, "z%d_%s" % (round(zn["zoom"] * 100), key)))
            else:
                c = {"error": "missing screenshot(s)"}
            comparisons.append((name, c))
            print("  %s: %s" % (name, c))
    write_report(args.out, runs, comparisons)
    print("report:", os.path.join(args.out, "report.html"))


if __name__ == "__main__":
    main()

"""PZO Setup desktop wizard and terminal entry point."""
import argparse
import base64
import contextlib
import ctypes
import faulthandler
import math
import os
from pathlib import Path
import queue
import sys
import subprocess
import struct
import tempfile
import threading
import time
import traceback

import pzo_install_core as core

BG = "#101513"
PANEL = "#18201c"
EDGE = "#34453d"
TEXT = "#f3f6ed"
MUTED = "#acbcb1"
ACCENT = "#c9f36b"
INK = "#1c2a13"
WARN = "#ffb68c"
# Seconds without a progress message before the screen says it's still waiting, and before every thread's stack
# goes into the log
STALL_NOTICE, STALL_DUMP = 30, 60
LOG_PATH = Path(tempfile.gettempdir()) / "PZO-Setup.log"


def log(text):
    # The windowed exe has no console, so this file is the only trace a stuck setup leaves
    with contextlib.suppress(OSError):
        with open(LOG_PATH, "a", encoding="utf-8") as out:
            out.write(time.strftime("%Y-%m-%d %H:%M:%S ") + text + "\n")


MODE_NAMES = {
    "standard": "Steam / game launcher",
    "both": "Steam and alternative batch",
    "alternative": "Alternative batch only",
}


def terminal(args):
    game = core.identify(args.game) if args.game else None
    if game is None:
        games = core.discover()
        if not games:
            if not sys.stdin.isatty():
                raise ValueError("Project Zomboid was not found. Pass --game PATH.")
            game = core.identify(input("Project Zomboid installation folder: ").strip().strip('"'))
        else:
            for i, item in enumerate(games, 1):
                print(f"  {i}  {item.platform:<8} {item.root}")
            if len(games) == 1:
                game = games[0]
            elif sys.stdin.isatty():
                value = input("Choose an installation or enter a folder [1]: ").strip() or "1"
                game = games[int(value) - 1] if value.isdigit() and 1 <= int(value) <= len(games) else core.identify(value)
            else:
                raise ValueError("Multiple installations found. Pass --game PATH.")
    if args.list:
        print(f"{game.platform}  {game.root}")
        return 0
    action = args.action
    if action is None:
        actions = ("update", "repair", "uninstall") if core.installed(game) else ("install",)
        if not sys.stdin.isatty():
            raise ValueError("Choose an action with --action. Use --help for options.")
        entered = input(f"Action ({'/'.join(actions)}) [{actions[0]}]: ").strip().lower()
        action = entered or actions[0]
        if action not in actions:
            raise ValueError("Choose an action from the displayed list.")
    mode = args.mode or core.previous_mode(game)
    if args.latest and action not in ("install", "update"):
        raise ValueError("Choose Install or Update to select a GitHub release.")
    plan = core.make_plan(game, action, mode, print, release=args.release,
                          collector=args.collector, max_heap=args.max_heap)
    print(f"\n{action.title()} PZO  |  {plan.version}  |  {game.platform}")
    print(game.root)
    print(f"Launch: {MODE_NAMES[plan.mode]}  |  Collector: {plan.gc}  |  Heap: {plan.heap}")
    for note in plan.notes:
        print("  " + note)
    for path, data in plan.files.items():
        print(("  Remove " if data is None else "  Write  ") + str(path))
    if args.dry_run or not plan.files:
        return 0
    if not args.yes:
        if not sys.stdin.isatty():
            raise ValueError("Pass --yes to apply a reviewed plan in a non-interactive shell.")
        if input("Apply these changes? [y/N] ").strip().lower() != "y":
            return 0
    backups = core.apply(plan, print)
    print(f"{action.title()} complete. Backup: {backups}")
    return 0


def setup_icon(tk, root):
    path = Path(__file__).with_name("app.ico")
    if not path.is_file():
        return None
    # app.ico already contains PNG frames, including the 48 px artwork used in the sidebar.
    data = path.read_bytes()
    count = struct.unpack_from("<H", data, 4)[0]
    frames = sorted(struct.iter_unpack("<BBBBHHII", data[6:6 + 16 * count]),
                    key=lambda frame: abs(frame[0] - 48))
    for _, _, _, _, _, _, size, offset in frames:
        frame = data[offset:offset + size]
        if frame.startswith(b"\x89PNG\r\n\x1a\n"):
            return tk.PhotoImage(master=root, data=base64.b64encode(frame), format="png")
    return None


class Wizard:
    def __init__(self, root, tk, ttk, filedialog, game=None):
        self.root, self.tk, self.ttk, self.dialog = root, tk, ttk, filedialog
        root.title("PZO Setup | Project Zomboid Optimizer")
        root.geometry("1010x700")
        root.minsize(850, 610)
        root.configure(bg=BG)
        root.protocol("WM_DELETE_WINDOW", self.close)
        self.icon = setup_icon(tk, root)
        if self.icon is not None:
            root.iconphoto(True, self.icon)
        self.font = "Segoe UI" if sys.platform == "win32" else "Helvetica"
        self.game = game
        self.games = []
        self.action = ""
        self.release = None
        self.releases = None
        self.release_pending = False
        self.release_error = ""
        self.release_widgets = None
        self.mode = "standard"
        self.plan = None
        self.jvm_settings = None
        self.jvm_values = None
        self.busy = False
        self.stage = 0
        self.generation = 0
        self.events = queue.Queue()
        self.step = ""
        self.step_since = 0.0
        self.stall_dumped = False
        self.notice = ""
        self.styles()
        self.sidebar = tk.Canvas(root, width=235, bg="#151d18", highlightthickness=0)
        self.sidebar.pack(side="left", fill="y")
        self.sidebar.bind("<Configure>", lambda event: self.draw_sidebar(event.height))
        self.shell = tk.Frame(root, bg=BG, padx=35, pady=24)
        self.shell.pack(side="left", fill="both", expand=True)
        self.footer = tk.Frame(self.shell, bg=BG)
        self.footer.pack(side="bottom", fill="x", pady=(14, 0))
        self.body = tk.Frame(self.shell, bg=BG)
        self.body.pack(fill="both", expand=True)
        self.detecting()
        self.root.after(50, self.poll)
        self.work(lambda report: core.discover(), self.discovered)

    def styles(self):
        style = self.ttk.Style(self.root)
        style.theme_use("clam")
        style.configure("TCombobox", background=PANEL, fieldbackground=PANEL, foreground=TEXT,
                        arrowcolor=ACCENT, bordercolor=EDGE, padding=8, font=(self.font, 11))
        style.map("TCombobox", fieldbackground=[("readonly", PANEL)],
                  foreground=[("readonly", TEXT)], selectbackground=[("readonly", PANEL)],
                  selectforeground=[("readonly", TEXT)])
        self.root.option_add("*TCombobox*Listbox.background", PANEL)
        self.root.option_add("*TCombobox*Listbox.foreground", TEXT)
        self.root.option_add("*TCombobox*Listbox.selectBackground", EDGE)
        style.configure("Vertical.TScrollbar", background=EDGE, troughcolor=PANEL, bordercolor=PANEL,
                        arrowcolor=MUTED, lightcolor=EDGE, darkcolor=EDGE, arrowsize=12)
        style.map("Vertical.TScrollbar", background=[("active", "#50655a")])
        style.configure("Horizontal.TProgressbar", background=ACCENT, troughcolor=PANEL,
                        bordercolor=BG, lightcolor=ACCENT, darkcolor=ACCENT)

    def label(self, parent, text, size=11, color=TEXT, bg=BG, bold=False, **options):
        return self.tk.Label(parent, text=text, fg=color, bg=bg, anchor="w",
                             font=(self.font, size, "bold" if bold else "normal"), **options)

    def button(self, parent, text, command, primary=False):
        fg, bg = (INK, ACCENT) if primary else (TEXT, PANEL)
        widget = self.tk.Button(parent, text=text, command=command, bd=0, relief="flat",
                                fg=fg, bg=bg, activeforeground=fg,
                                activebackground="#dcff9a" if primary else EDGE,
                                disabledforeground=MUTED, cursor="hand2", padx=16, pady=8,
                                highlightthickness=1, highlightbackground=bg, highlightcolor=ACCENT,
                                font=(self.font, 10, "bold"))
        return widget

    def draw_sidebar(self, height):
        c = self.sidebar
        c.delete("all")
        if self.icon is not None:
            c.create_image(27, 29, anchor="nw", image=self.icon)
        c.create_text(87, 46, text="PZO SETUP", fill=TEXT, font=(self.font, 15, "bold"), anchor="w")
        c.create_text(88, 67, text="PROJECT ZOMBOID", fill=MUTED, font=("TkFixedFont", 8), anchor="w")
        c.create_line(27, 104, 207, 104, fill=EDGE)
        c.create_line(39, 151, 39, 355, fill=EDGE, width=2)
        for i, (title, subtitle) in enumerate((("Detect", "Game installation"),
                                                ("Configure", "Collector and heap"),
                                                ("Review", "Planned changes"),
                                                ("Apply", "Backup and install"))):
            y = 151 + i * 68
            active = i == self.stage
            c.create_oval(26, y - 13, 52, y + 13, outline=ACCENT if active else EDGE,
                          fill=ACCENT if active else "#151d18", width=2)
            c.create_text(39, y, text=str(i + 1), fill=INK if active else MUTED,
                          font=("TkFixedFont", 9, "bold"))
            c.create_text(64, y - 6, text=title, fill=TEXT if i <= self.stage else MUTED,
                          font=(self.font, 12, "bold"), anchor="w")
            c.create_text(64, y + 13, text=subtitle, fill=MUTED, font=(self.font, 9), anchor="w")
        top = max(380, height - 200)
        for row in range(9):
            points = []
            for x in range(0, 240, 5):
                y = top + 13 * row + 11 * math.sin(x / 41 + row / 6) - 0.11 * x
                points.extend((x, y))
            c.create_line(points, fill="#465c36" if row == 3 else "#273b2c", width=1)
        c.create_text(27, height - 27, text="BUILD 42  /  CLIENT", fill=MUTED,
                      font=("TkFixedFont", 9), anchor="w")

    def screen(self, stage, title, description):
        self.stage = stage
        self.draw_sidebar(self.sidebar.winfo_height())
        for frame in (self.body, self.footer):
            for child in frame.winfo_children():
                child.destroy()
        self.label(self.body, title, size=28, bold=True).pack(fill="x")
        self.label(self.body, description, color=MUTED, justify="left", wraplength=530).pack(
            fill="x", pady=(5, 18))

    def detecting(self):
        self.screen(0, "Choose your game", "Looking for Project Zomboid in your Steam libraries.")
        self.progress_bar(self.body)

    def progress_bar(self, parent):
        bar = self.ttk.Progressbar(parent, mode="indeterminate")
        bar.pack(fill="x", pady=(4, 14))
        bar.start(12)

    def discovered(self, games):
        self.games = list(games)
        if self.game is None and self.games:
            self.game = self.games[0]
        if self.game and self.game not in self.games:
            self.games.insert(0, self.game)
        if self.game:
            self.mode = core.previous_mode(self.game)
        self.detect()

    def select_game(self, game):
        self.game = game
        self.jvm_settings = self.jvm_values = None
        self.release = None
        self.mode = core.previous_mode(game)
        self.action = ""
        self.notice = ""
        self.detect()

    def browse(self):
        title = "Choose your game installation or .app bundle"
        chosen = self.dialog.askdirectory(parent=self.root, title=title, mustexist=True)
        if chosen:
            try:
                game = core.identify(chosen)
            except (OSError, ValueError) as error:
                self.notice = str(error)
                self.detect()
                return
            if game not in self.games:
                self.games.insert(0, game)
            self.select_game(game)

    def detect(self):
        self.screen(0, "Choose your game", "Select an installation and what you want PZO Setup to do.")
        card = self.tk.Frame(self.body, bg=PANEL, highlightbackground=EDGE, highlightthickness=1, padx=17, pady=13)
        card.pack(fill="x")
        line = self.tk.Frame(card, bg=PANEL)
        line.pack(fill="x")
        self.label(line, "Project Zomboid", 16, bg=PANEL, bold=True).pack(side="left")
        self.button(line, "Browse...", self.browse).pack(side="right")
        if self.games:
            values = [f"{g.platform}  |  {g.root}" for g in self.games]
            combo = self.ttk.Combobox(card, values=values, state="readonly")
            combo.current(self.games.index(self.game))
            combo.bind("<<ComboboxSelected>>", lambda event: self.select_game(self.games[combo.current()]))
            combo.pack(fill="x", pady=(10, 7))
            version = core.installed_version(self.game.engine)
            state = f"PZO {version} installed" if version else (
                "Existing PZO configuration detected" if core.installed(self.game) else "PZO is ready to install")
            self.label(card, state, color=ACCENT, bg=PANEL).pack(fill="x")
        else:
            self.label(card, "Choose the game folder to continue.", color=MUTED, bg=PANEL).pack(fill="x", pady=(8, 0))
        if self.notice:
            self.label(self.body, self.notice, color=WARN, wraplength=530, justify="left").pack(fill="x", pady=(8, 0))
        if not self.game:
            self.button(self.footer, "Close", self.close).pack(side="right")
            return
        actions = ("update", "repair", "uninstall") if core.installed(self.game) else ("install",)
        self.action = self.action if self.action in actions else actions[0]
        self.label(self.body, "Setup action", bold=True).pack(fill="x", pady=(17, 7))
        strip = self.tk.Frame(self.body, bg=BG)
        strip.pack(fill="x")
        for action in actions:
            button = self.button(strip, action.title(), lambda a=action: self.set_action(a), self.action == action)
            button.pack(side="left", padx=(0, 7))
        descriptions = {
            "install": "Install the engine and configure the game launcher.",
            "update": "Replace the engine and refresh the launcher settings.",
            "repair": "Keep the installed engine and rebuild the launcher settings.",
            "uninstall": "Remove PZO and restore the game's launch settings.",
        }
        self.label(self.body, descriptions[self.action], color=MUTED).pack(fill="x", pady=(6, 0))
        if self.game.platform == "Windows" and self.action != "uninstall":
            self.label(self.body, "Launch method", bold=True).pack(fill="x", pady=(14, 5))
            mode = self.ttk.Combobox(self.body, state="readonly", values=list(MODE_NAMES.values()))
            mode.current(list(MODE_NAMES).index(self.mode))
            mode.bind("<<ComboboxSelected>>", lambda event: self.set_mode(list(MODE_NAMES)[mode.current()]))
            mode.pack(fill="x")
        self.button(self.footer, "Exit", self.close).pack(side="left")
        if self.action == "uninstall":
            self.button(self.footer, "Review changes", self.prepare, True).pack(side="right")
        else:
            self.button(self.footer, "Configure JVM", self.configure_jvm, True).pack(side="right")

    def set_action(self, action):
        self.action = action
        self.notice = ""
        self.detect()

    def set_mode(self, mode):
        self.mode = mode

    def configure_jvm(self):
        if self.jvm_settings is not None:
            self.show_jvm_settings()
            return
        self.screen(1, "Configure JVM", "Checking the game's Java runtime and system memory.")
        self.progress_bar(self.body)
        self.button(self.footer, "Back", self.cancel_detection).pack(side="left")
        game = self.game
        self.work(lambda report: core.detected_settings(game), self.show_jvm_settings)

    def show_jvm_settings(self, settings=None):
        if settings is not None:
            self.jvm_settings = settings
        recommended, heap, supported, saved_gc, saved_heap, memory = self.jvm_settings
        if self.jvm_values is None:
            collector = saved_gc if saved_gc in supported else recommended
            self.jvm_values = (collector, core.gib_text(saved_heap or heap))
        description = ("Choose the PZO release, garbage collector, and maximum heap."
                       if self.action in ("install", "update")
                       else "Choose the garbage collector and maximum heap for this installation.")
        self.screen(1, "Configure JVM", description)
        if self.action in ("install", "update"):
            self.show_release_picker()
        card = self.tk.Frame(self.body, bg=PANEL, highlightbackground=EDGE,
                             highlightthickness=1, padx=17, pady=14)
        card.pack(fill="x")
        self.label(card, "Garbage collector", bg=PANEL, bold=True).pack(fill="x")
        names = [core.COLLECTORS[key][0] for key in supported]
        self.selected_gc = self.ttk.Combobox(card, state="readonly", values=names)
        self.selected_gc.current(supported.index(self.jvm_values[0]))
        self.selected_gc.pack(fill="x", pady=(6, 15))
        self.label(card, "Maximum heap (GiB)", bg=PANEL, bold=True).pack(fill="x")
        self.heap_entry = self.tk.Entry(card, bg=BG, fg=TEXT, insertbackground=ACCENT, bd=0,
                                        highlightthickness=1, highlightbackground=EDGE, highlightcolor=ACCENT,
                                        font=(self.font, 12))
        self.heap_entry.insert(0, self.jvm_values[1])
        self.heap_entry.pack(fill="x", pady=(6, 8), ipady=7)
        detected = f"  |  Detected RAM {memory:.1f} GiB" if memory else ""
        self.label(card, f"Recommended  {core.COLLECTORS[recommended][0]} / {core.gib_text(heap)} GiB{detected}",
                   color=MUTED, bg=PANEL, wraplength=480).pack(fill="x")
        self.jvm_error = self.label(self.body, "", color=WARN, wraplength=530, justify="left")
        self.jvm_error.pack(fill="x", pady=(8, 0))
        self.button(self.footer, "Back", self.remember_and_detect).pack(side="left")
        self.button(self.footer, "Review changes", self.confirm_jvm, True).pack(side="right")
        self.button(self.footer, "Use recommended", self.reset_jvm).pack(side="right", padx=(0, 8))

    def show_release_picker(self):
        card = self.tk.Frame(self.body, bg=PANEL, highlightbackground=EDGE, highlightthickness=1, padx=17, pady=12)
        card.pack(fill="x", pady=(0, 12))
        self.label(card, "PZO version", bg=PANEL, bold=True).pack(fill="x")
        picker = self.ttk.Combobox(card, state="readonly")
        picker.pack(fill="x", pady=(6, 0))
        picker.bind("<<ComboboxSelected>>", lambda event: self.select_release(picker.current()))
        status = self.tk.Frame(card, bg=PANEL)
        text = self.label(status, "", size=10, bg=PANEL, color=MUTED, justify="left", wraplength=425)
        text.pack(side="left", fill="x", expand=True)
        retry = self.button(status, "Retry", self.load_releases)
        self.release_widgets = (picker, status, text, retry)
        if self.releases is None and not self.release_error:
            self.load_releases()
        self.update_release_picker()

    def select_release(self, index):
        self.release = self.releases[index - 1] if index > 0 else None

    def load_releases(self):
        if self.release_pending:
            return
        self.release_pending = True
        self.release_error = ""
        self.update_release_picker()

        def background():
            try:
                releases = core.recent_releases()
            except Exception as error:
                log("release list failed: " + "".join(traceback.format_exception_only(type(error), error)).strip())
                self.events.put((None, "releases", (None, str(error) or type(error).__name__)))
            else:
                self.events.put((None, "releases", (releases, "")))

        threading.Thread(target=background, daemon=True).start()

    def update_release_picker(self):
        if self.release_widgets is None or not self.release_widgets[0].winfo_exists():
            return
        picker, status, text, retry = self.release_widgets
        releases = self.releases or []
        picker.configure(values=["Latest", *(release.label for release in releases)])
        picker.current(releases.index(self.release) + 1 if self.release in releases else 0)
        retry.pack_forget()
        if self.release_pending or self.release_error:
            status.pack(fill="x", pady=(7, 0))
            text.configure(text=self.release_error or "Loading recent GitHub releases...",
                           fg=WARN if self.release_error else MUTED)
            if self.release_error:
                retry.pack(side="right")
        else:
            status.pack_forget()

    def reset_jvm(self):
        recommended, heap, supported, _, _, _ = self.jvm_settings
        self.selected_gc.current(supported.index(recommended))
        self.heap_entry.delete(0, "end")
        self.heap_entry.insert(0, core.gib_text(heap))
        self.jvm_error.configure(text="")

    def remember_jvm(self):
        supported = self.jvm_settings[2]
        self.jvm_values = (supported[self.selected_gc.current()], self.heap_entry.get().strip())

    def remember_and_detect(self):
        self.remember_jvm()
        self.detect()

    def confirm_jvm(self):
        self.remember_jvm()
        try:
            core.heap_mib(self.jvm_values[1], self.jvm_settings[-1])
        except ValueError as error:
            self.jvm_error.configure(text=str(error))
            return
        self.prepare()

    def cancel_detection(self):
        self.generation += 1
        self.busy = False
        self.detect()

    def back_from_review(self):
        self.generation += 1
        self.busy = False
        if self.action == "uninstall" or self.jvm_settings is None:
            self.detect()
        else:
            self.show_jvm_settings()

    def prepare(self):
        self.notice = ""
        self.screen(2, "Review changes", "Checking the engine package and validating the proposed settings.")
        self.progress_bar(self.body)
        self.activity = self.label(self.body, "Reading launcher settings", color=ACCENT, wraplength=530)
        self.activity.pack(fill="x")
        self.button(self.footer, "Back", self.back_from_review).pack(side="left")
        game, action, mode = self.game, self.action, self.mode
        release = self.release if action in ("install", "update") else None
        collector, max_heap = (None, None) if action == "uninstall" else self.jvm_values
        self.work(lambda report: core.make_plan(game, action, mode, report, release=release,
                                               collector=collector, max_heap=max_heap), self.review)

    def review(self, plan):
        self.plan = plan
        self.screen(2, f"{plan.action.title()} PZO", f"{plan.game.platform}  |  {plan.game.root}")
        card = self.tk.Frame(self.body, bg=PANEL, highlightbackground=EDGE, highlightthickness=1, padx=15, pady=12)
        card.pack(fill="x")
        for i, (key, value) in enumerate((
            ("ENGINE", plan.version), ("COLLECTOR", plan.gc), ("MAX HEAP", plan.heap),
        )):
            card.columnconfigure(i, weight=1)
            self.label(card, key, 9, MUTED, PANEL, True).grid(row=0, column=i, sticky="w", padx=(0, 9))
            self.label(card, value, 13, TEXT, PANEL, True, wraplength=190, justify="left").grid(
                row=1, column=i, sticky="nw", padx=(0, 9), pady=(3, 0))
        if plan.action != "uninstall":
            self.label(self.body, MODE_NAMES[plan.mode], color=ACCENT, bold=True).pack(fill="x", pady=(9, 0))
        if plan.files:
            count = len(plan.files)
            self.label(self.body, f"Files to change: {count}", color=TEXT, bold=True).pack(fill="x", pady=(11, 0))
        changes = [("Remove " if data is None else "Write  ") + self.pretty_path(path) for path, data in plan.files.items()]
        details = [*plan.notes, "", *changes] if changes else [*plan.notes, "", "This installation is up to date."]
        self.details(details)
        self.button(self.footer, "Back", self.back_from_review).pack(side="left")
        if plan.files:
            self.button(self.footer, f"Apply {plan.action}", self.apply, True).pack(side="right")
        else:
            self.button(self.footer, "Done", self.close, True).pack(side="right")

    def pretty_path(self, path):
        try:
            return str(path.relative_to(self.game.root))
        except ValueError:
            return str(path)

    def details(self, lines):
        box = self.tk.Frame(self.body, bg=PANEL, highlightbackground=EDGE, highlightthickness=1)
        box.pack(fill="both", expand=True, pady=(12, 0))
        content = self.tk.Text(box, bg=PANEL, fg=MUTED, bd=0, padx=13, pady=9,
                               wrap="word", height=5, font=(self.font, 10), highlightthickness=0)
        scroll = self.ttk.Scrollbar(box, command=content.yview)
        def scrollbar(first, last):
            scroll.set(first, last)
            if float(first) == 0 and float(last) == 1:
                scroll.pack_forget()
            elif not scroll.winfo_manager():
                scroll.pack(side="right", fill="y", before=content)

        content.configure(yscrollcommand=scrollbar)
        content.pack(side="left", fill="both", expand=True)
        content.insert("1.0", "\n".join(lines))
        content.configure(state="disabled")

    def apply(self):
        self.screen(3, f"{self.action.title()} in progress", "Keep this window open while setup applies the reviewed changes.")
        self.progress_bar(self.body)
        self.activity = self.label(self.body, "Preparing the backup", color=ACCENT, wraplength=530)
        self.activity.pack(fill="x")
        self.work(lambda report: core.apply(self.plan, report), self.complete)

    def complete(self, backups):
        self.screen(3, f"{self.action.title()} complete", "The reviewed changes were applied.")
        if self.action == "uninstall":
            instruction = "Start Project Zomboid using its game launcher."
        elif self.mode == "alternative":
            instruction = f"Start the game with {core.SCRIPT} in the game folder."
        else:
            instruction = "Start Project Zomboid from Steam or the game launcher."
        self.label(self.body, instruction, size=13, bold=True, wraplength=530, justify="left").pack(fill="x")
        self.details([f"Backup folder\n{backups}"] if backups else ["The installation already matched the review."])
        self.button(self.footer, "Done", self.close, True).pack(side="right")

    def failure(self, error):
        if self.stage == 0:
            self.notice = str(error)
            self.detect()
            return
        self.screen(self.stage, "Setup could not continue", "Read the error below, then return to the installation screen to retry.")
        self.details(str(error).splitlines() + ["", f"Setup log: {LOG_PATH}"])
        self.button(self.footer, "Back", self.back_from_review).pack(side="left")
        self.button(self.footer, "Close", self.close).pack(side="right")

    def work(self, func, then):
        self.busy = True
        self.generation += 1
        job = self.generation

        self.step = getattr(then, "__name__", "work")
        self.step_since = time.monotonic()
        self.stall_dumped = False
        log(f"job {job} start: {self.step}")

        def report(message):
            log(f"job {job}: {message}")
            self.events.put((job, "message", message))

        def background():
            try:
                result = func(report)
            except BaseException as error:
                log(f"job {job} failed:\n" + "".join(traceback.format_exception(type(error), error, error.__traceback__)))
                self.events.put((job, "error", error))
            else:
                log(f"job {job} done")
                self.events.put((job, "result", (then, result)))

        threading.Thread(target=background, daemon=True).start()

    # Any error in a handler used to stop the polling for good: the window kept its last message (e.g. "Reading
    # launcher settings") while the work behind it had long finished. It always polls again now and shows the error.
    def poll(self):
        try:
            self.handle_events()
            self.watch_stall()
        except Exception as error:
            log("screen update failed:\n" + traceback.format_exc())
            self.busy = False
            with contextlib.suppress(Exception):
                self.failure(error)
        finally:
            self.root.after(50, self.poll)

    def watch_stall(self):
        if not self.busy or self.stage == 0:
            return
        waited = time.monotonic() - self.step_since
        if waited >= STALL_DUMP and not self.stall_dumped:
            self.stall_dumped = True
            log(f"no progress for {waited:.0f}s at: {self.step}; thread stacks follow")
            with contextlib.suppress(OSError):
                with open(LOG_PATH, "a", encoding="utf-8") as out:
                    faulthandler.dump_traceback(out, all_threads=True)
        if waited >= STALL_NOTICE and hasattr(self, "activity") and self.activity.winfo_exists():
            self.activity.configure(text=f"{self.step}  (still working, {waited:.0f}s; log: {LOG_PATH})")

    def handle_events(self):
        while True:
            try:
                job, kind, item = self.events.get_nowait()
            except queue.Empty:
                break
            if kind == "releases":
                self.release_pending = False
                self.releases, self.release_error = item
                self.update_release_picker()
                continue
            if job != self.generation:
                continue
            if kind == "message":
                self.step = item
                self.step_since = time.monotonic()
                if hasattr(self, "activity") and self.activity.winfo_exists():
                    self.activity.configure(text=item)
            else:
                self.busy = False
                if kind == "error":
                    self.failure(item)
                else:
                    then, result = item
                    then(result)

    def close(self):
        if not self.busy or self.stage != 3:
            self.root.destroy()


def main():
    if sys.version_info < (3, 9):
        print("PZO Setup requires Python 3.9 or newer.", file=sys.stderr)
        return 1
    parser = argparse.ArgumentParser(description="Project Zomboid Optimizer setup")
    parser.add_argument("--cli", action="store_true", help="Use the terminal interface.")
    parser.add_argument("--game", type=Path, help="Project Zomboid game directory or app bundle.")
    parser.add_argument("--action", choices=("install", "update", "repair", "uninstall"))
    parser.add_argument("--mode", choices=tuple(MODE_NAMES))
    parser.add_argument("--collector", choices=tuple(core.COLLECTORS), help="Override the garbage collector.")
    parser.add_argument("--max-heap", metavar="GIB", help="Override the maximum heap in GiB.")
    versions = parser.add_mutually_exclusive_group()
    versions.add_argument("--release", metavar="TAG", help="Install a tag from the five most recent GitHub releases.")
    versions.add_argument("--latest", action="store_true", help="Use the latest GitHub release (default).")
    parser.add_argument("--list", action="store_true", help="List detected installations.")
    parser.add_argument("--dry-run", action="store_true", help="Show the review without applying it.")
    parser.add_argument("--yes", action="store_true", help="Apply the reviewed changes without prompting.")
    args = parser.parse_args()
    try:
        if args.list and not args.game:
            for game in core.discover():
                print(f"{game.platform:<8} {game.root}")
            return 0
        if (args.cli or args.action or args.mode or args.latest or args.list or args.dry_run
                or args.release or args.yes or args.collector or args.max_heap):
            return terminal(args)
        try:
            import tkinter as tk
            from tkinter import filedialog, ttk
        except ImportError:
            return terminal(args)
        try:
            root = tk.Tk()
        except tk.TclError:
            return terminal(args)
        if sys.platform == "win32" and getattr(sys, "frozen", False):
            # Tk is loaded. Java and other system tools should use their own DLL search paths.
            ctypes.windll.kernel32.SetDllDirectoryW(None)
        game = core.identify(args.game) if args.game else None
        log(f"PZO Setup started (Python {sys.version.split()[0]}, frozen={getattr(sys, 'frozen', False)}, {os.name})")
        Wizard(root, tk, ttk, filedialog, game)
        root.mainloop()
        return 0
    except (ValueError, OSError, EOFError, SyntaxError, subprocess.SubprocessError) as error:
        if sys.stderr is not None:
            print(f"Setup error: {error}", file=sys.stderr)
        else:
            from tkinter.messagebox import showerror
            showerror("PZO Setup", str(error))
        return 1


if __name__ == "__main__":
    raise SystemExit(main())

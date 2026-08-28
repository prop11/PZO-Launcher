import os
import sys
import json
import shutil
import ctypes
import tkinter as tk
from tkinter import messagebox, filedialog

class MEMORYSTATUSEX(ctypes.Structure):
    _fields_ = [
        ("dwLength", ctypes.c_ulong),
        ("dwMemoryLoad", ctypes.c_ulong),
        ("ullTotalPhys", ctypes.c_ulonglong),
        ("ullAvailPhys", ctypes.c_ulonglong),
        ("ullTotalPageFile", ctypes.c_ulonglong),
        ("ullAvailPageFile", ctypes.c_ulonglong),
        ("ullTotalVirtual", ctypes.c_ulonglong),
        ("ullAvailVirtual", ctypes.c_ulonglong),
        ("sullAvailExtendedVirtual", ctypes.c_ulonglong),
    ]

def get_system_ram_gb():
    try:
        stat = MEMORYSTATUSEX()
        stat.dwLength = ctypes.sizeof(MEMORYSTATUSEX)
        ctypes.windll.kernel32.GlobalMemoryStatusEx(ctypes.byref(stat))
        return round(stat.ullTotalPhys / (1024 ** 3))
    except Exception:
        return 16

def detect_pz_path():
    paths = [
        r"C:\Program Files (x86)\Steam\steamapps\common\ProjectZomboid",
        r"D:\SteamLibrary\steamapps\common\ProjectZomboid",
        r"E:\SteamLibrary\steamapps\common\ProjectZomboid",
        r"K:\SteamLibrary\steamapps\common\ProjectZomboid",
        r"C:\SteamLibrary\steamapps\common\ProjectZomboid"
    ]
    for p in paths:
        if os.path.exists(os.path.join(p, "ProjectZomboid64.json")):
            return p
    return paths[0]

class OptimizerApp(tk.Tk):
    def __init__(self):
        super().__init__()
        self.title("Project Zomboid - Config & Engine Optimizer")
        self.geometry("740x560")
        self.minsize(700, 520)
        self.configure(bg="#0f111a")

        self.total_ram = get_system_ram_gb()
        self.pz_path = detect_pz_path()
        self.jar_path = os.path.join(os.path.dirname(os.path.abspath(__file__)), "dist", "PZOptimEngine.jar")

        self.build_ui()

    def build_ui(self):
        header = tk.Frame(self, bg="#161926", padx=20, pady=12)
        header.pack(fill="x")
        
        tk.Label(header, text="PROJECT ZOMBOID CONFIG & ENGINE OPTIMIZER", font=("Segoe UI", 13, "bold"), fg="#38bdf8", bg="#161926").pack(anchor="w")
        tk.Label(header, text=f"Detected System: {self.total_ram} GB RAM | {os.cpu_count() or 8} Cores", font=("Segoe UI", 9), fg="#94a3b8", bg="#161926").pack(anchor="w")

        main = tk.Frame(self, bg="#0f111a", padx=16, pady=10)
        main.pack(fill="both", expand=True)

        card1 = tk.Frame(main, bg="#161926", padx=14, pady=10, relief="flat")
        card1.pack(fill="x", pady=5)
        
        tk.Label(card1, text="Project Zomboid Directory", font=("Segoe UI", 10, "bold"), fg="#e2e8f0", bg="#161926").pack(anchor="w")
        p_frame = tk.Frame(card1, bg="#161926")
        p_frame.pack(fill="x", pady=4)
        
        self.path_entry = tk.Entry(p_frame, bg="#0b0d14", fg="#f8fafc", font=("Segoe UI", 9), relief="flat", insertbackground="white")
        self.path_entry.insert(0, self.pz_path)
        self.path_entry.pack(side="left", fill="x", expand=True, ipady=4, padx=(0, 6))
        
        tk.Button(p_frame, text="Browse", bg="#242938", fg="#f8fafc", relief="flat", padx=10, command=self.browse).pack(side="right")

        card2 = tk.Frame(main, bg="#161926", padx=14, pady=10, relief="flat")
        card2.pack(fill="x", pady=5)
        
        tk.Label(card2, text="Memory & JVM Optimizations", font=("Segoe UI", 10, "bold"), fg="#e2e8f0", bg="#161926").pack(anchor="w")
        
        default_ram = 8 if self.total_ram >= 16 else 4
        if self.total_ram >= 32:
            default_ram = 12
            
        self.ram_lbl = tk.Label(card2, text=f"Allocated RAM: {default_ram} GB (-Xmx{default_ram*1024}m)", font=("Segoe UI", 9, "bold"), fg="#4ade80", bg="#161926")
        self.ram_lbl.pack(anchor="w", pady=(4, 0))
        
        self.ram_slider = tk.Scale(card2, from_=3, to=min(24, max(8, self.total_ram - 4)), orient="horizontal", bg="#161926", fg="#f8fafc", highlightthickness=0, troughcolor="#0b0d14", activebackground="#38bdf8", command=self.on_ram_slider)
        self.ram_slider.set(default_ram)
        self.ram_slider.pack(fill="x", pady=2)

        self.opt_agent = tk.BooleanVar(value=True)
        self.opt_g1gc = tk.BooleanVar(value=True)
        self.opt_pretouch = tk.BooleanVar(value=True)

        tk.Checkbutton(card2, text="Inject PZOptimEngine Agent (Runtime Bytecode & Class Hooks)", variable=self.opt_agent, bg="#161926", fg="#f8fafc", selectcolor="#0b0d14", activebackground="#161926", activeforeground="#ffffff", font=("Segoe UI", 9)).pack(anchor="w", pady=2)
        tk.Checkbutton(card2, text="Low-Latency G1GC Tuning (-XX:+UseG1GC -XX:InitiatingHeapOccupancyPercent=45)", variable=self.opt_g1gc, bg="#161926", fg="#f8fafc", selectcolor="#0b0d14", activebackground="#161926", activeforeground="#ffffff", font=("Segoe UI", 9)).pack(anchor="w", pady=2)
        tk.Checkbutton(card2, text="Heap Pre-allocation (-XX:+AlwaysPreTouch)", variable=self.opt_pretouch, bg="#161926", fg="#f8fafc", selectcolor="#0b0d14", activebackground="#161926", activeforeground="#ffffff", font=("Segoe UI", 9)).pack(anchor="w", pady=2)

        btn_row = tk.Frame(main, bg="#0f111a")
        btn_row.pack(fill="x", pady=12)

        tk.Button(btn_row, text="Optimize Game", font=("Segoe UI", 10, "bold"), bg="#0284c7", fg="#ffffff", activebackground="#0369a1", activeforeground="#ffffff", relief="flat", padx=16, pady=8, command=self.optimize).pack(side="left", fill="x", expand=True, padx=(0, 4))
        tk.Button(btn_row, text="Restore Default", font=("Segoe UI", 9), bg="#242938", fg="#f87171", activebackground="#334155", activeforeground="#f87171", relief="flat", padx=12, pady=8, command=self.restore).pack(side="left", padx=3)
        tk.Button(btn_row, text="Clear Logs", font=("Segoe UI", 9), bg="#242938", fg="#fbbf24", activebackground="#334155", activeforeground="#fbbf24", relief="flat", padx=12, pady=8, command=self.clear_cache).pack(side="left", padx=(3, 0))

    def on_ram_slider(self, val):
        v = int(val)
        self.ram_lbl.config(text=f"Allocated RAM: {v} GB (-Xmx{v*1024}m)")

    def browse(self):
        d = filedialog.askdirectory(initialdir=self.path_entry.get())
        if d:
            self.path_entry.delete(0, "end")
            self.path_entry.insert(0, d)

    def write_json(self):
        target = self.path_entry.get()
        cfg_path = os.path.join(target, "ProjectZomboid64.json")
        if not os.path.exists(cfg_path):
            messagebox.showerror("Error", f"ProjectZomboid64.json not found in:\n{target}")
            return None

        bak = os.path.join(target, "ProjectZomboid64.json.bak")
        if not os.path.exists(bak):
            shutil.copy2(cfg_path, bak)

        ram_mb = int(self.ram_slider.get()) * 1024
        args = [
            "-Djava.awt.headless=true",
            "--enable-native-access=ALL-UNNAMED",
            "--add-exports=java.base/jdk.internal.misc=ALL-UNNAMED",
            f"-Xmx{ram_mb}m",
            "-Dzomboid.steam=1",
            "-Dzomboid.znetlog=1",
            "-Djava.library.path=win64/;.",
            "-XX:-CreateCoredumpOnCrash",
            "-XX:-OmitStackTraceInFastThrow"
        ]

        if self.opt_g1gc.get():
            args.extend([
                "-XX:+UseG1GC",
                "-XX:InitiatingHeapOccupancyPercent=45",
                "-XX:G1ReservePercent=15"
            ])

        if self.opt_pretouch.get():
            args.append("-XX:+AlwaysPreTouch")

        if self.opt_agent.get() and os.path.exists(self.jar_path):
            dest_jar = os.path.join(target, "PZOptimEngine.jar")
            shutil.copy2(self.jar_path, dest_jar)
            args.append("-javaagent:PZOptimEngine.jar")

        data = {
            "mainClass": "zombie/gameStates/MainScreenState",
            "classpath": [".", "projectzomboid.jar"],
            "vmArgs": args
        }

        with open(cfg_path, "w", encoding="utf-8") as f:
            json.dump(data, f, indent=4)

        return target

    def optimize(self):
        if self.write_json():
            messagebox.showinfo("Success", f"Project Zomboid optimized successfully!\n\n- Allocated RAM: {self.ram_slider.get()} GB\n- Low-Latency G1GC: Active\n- Engine Agent: {self.opt_agent.get()}\n\nYou can now launch the game normally through Steam.")

    def restore(self):
        target = self.path_entry.get()
        cfg_path = os.path.join(target, "ProjectZomboid64.json")
        bak = os.path.join(target, "ProjectZomboid64.json.bak")

        if os.path.exists(bak):
            shutil.copy2(bak, cfg_path)
            messagebox.showinfo("Restored", "Default configuration restored from backup.")
        else:
            default_data = {
                "mainClass": "zombie/gameStates/MainScreenState",
                "classpath": [".", "projectzomboid.jar"],
                "vmArgs": [
                    "-Djava.awt.headless=true",
                    "--enable-native-access=ALL-UNNAMED",
                    "--add-exports=java.base/jdk.internal.misc=ALL-UNNAMED",
                    "-Xmx3072m",
                    "-Dzomboid.steam=1",
                    "-Dzomboid.znetlog=1",
                    "-Djava.library.path=win64/;.",
                    "-XX:-CreateCoredumpOnCrash",
                    "-XX:-OmitStackTraceInFastThrow"
                ]
            }
            with open(cfg_path, "w", encoding="utf-8") as f:
                json.dump(default_data, f, indent=4)
            messagebox.showinfo("Restored", "Default 3GB configuration written.")

    def clear_cache(self):
        z_dir = os.path.expanduser(r"~\Zomboid")
        count = 0
        for sub in ["Lua", "logs"]:
            p = os.path.join(z_dir, sub)
            if os.path.exists(p):
                for f in os.listdir(p):
                    fp = os.path.join(p, f)
                    if os.path.isfile(fp) and (f.endswith(".log") or f.endswith(".cache") or f.endswith(".txt")):
                        try:
                            os.remove(fp)
                            count += 1
                        except Exception:
                            pass
        messagebox.showinfo("Cleaned", f"Removed {count} cache and log files.")

if __name__ == "__main__":
    app = OptimizerApp()
    app.mainloop()

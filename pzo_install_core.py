"""Detection, review, and transactional application of PZO installer changes."""
import contextlib
import hashlib
from http.client import HTTPException
import ctypes
from dataclasses import dataclass
from datetime import datetime
from decimal import Decimal, InvalidOperation, ROUND_HALF_UP
import json
from io import BytesIO
import os
from pathlib import Path
import re
import shutil
import stat
import subprocess
import sys
import tempfile
import threading
from urllib.error import HTTPError
from urllib.parse import quote
from urllib.request import Request, urlopen
from zipfile import BadZipFile, ZipFile

import pzo_launcher_config as launcher

ASSETS = Path(__file__).resolve().parent
RELEASES = "https://github.com/prop11/PZO-Launcher/releases"
RELEASE_API = "https://api.github.com/repos/prop11/PZO-Launcher/releases"
RELEASE_COUNT = 5
NATIVE_VERSION = "1.9.7"
LINUX_NATIVE_SHA256 = "f5333959f6843e3294d3328dc7b638ae074f56ef125ad4f8793da86c6743aa67"
SCRIPT = "ProjectZomboid64_PZO.bat"
JSON_FILE = "ProjectZomboid64.json"
HOME_DIR = Path.home()
COLLECTORS = {
    "g1": ("G1", launcher.G1),
    "shenandoah": ("Generational Shenandoah", launcher.SHENANDOAH),
    "zgc": ("ZGC", ("-XX:+UseZGC",)),
    "parallel": ("Parallel GC", ("-XX:+UseParallelGC",)),
    "serial": ("Serial GC", ("-XX:+UseSerialGC",)),
}
PORTABLE_COLLECTORS = {"g1", "parallel", "serial"}


@dataclass(frozen=True)
class Game:
    root: Path
    platform: str
    configs: tuple
    jar_dirs: tuple
    java: object
    user_data: tuple

    @property
    def backup(self):
        if self.platform == "macOS":
            return self.root.with_name(self.root.name + ".PZO-Backups")
        return self.root / "Installer_Backups"

    @property
    def engine(self):
        return self.jar_dirs[0] / launcher.ENGINE


@dataclass(frozen=True)
class EngineRelease:
    tag: str
    published: str
    prerelease: bool
    size: int
    digest: str

    @property
    def label(self):
        return self.tag + (" (prerelease)" if self.prerelease else "")


@dataclass
class Plan:
    game: Game
    action: str
    version: str
    mode: str
    gc: str
    heap: str
    files: dict
    originals: dict
    notes: list


def run(args, timeout=15):
    env = dict(os.environ)
    for key in ("_JAVA_OPTIONS", "JAVA_TOOL_OPTIONS", "JDK_JAVA_OPTIONS"):
        env.pop(key, None)
    return subprocess.run([str(a) for a in args], capture_output=True, text=True,
                          errors="replace", timeout=timeout, env=env,
                          creationflags=getattr(subprocess, "CREATE_NO_WINDOW", 0))


def file_bytes(path):
    try:
        return path.read_bytes()
    except FileNotFoundError:
        return None


def steam_roots():
    roots = []
    if sys.platform == "win32":
        import winreg
        for key, value in ((winreg.HKEY_CURRENT_USER, "SteamPath"), (winreg.HKEY_LOCAL_MACHINE, "InstallPath")):
            for name in (r"Software\Valve\Steam", r"Software\WOW6432Node\Valve\Steam"):
                with contextlib.suppress(OSError):
                    with winreg.OpenKey(key, name) as registry:
                        roots.append(Path(winreg.QueryValueEx(registry, value)[0]))
        for var in ("ProgramFiles(x86)", "ProgramFiles"):
            if os.environ.get(var):
                roots.append(Path(os.environ[var]) / "Steam")
    elif sys.platform == "darwin":
        roots.append(HOME_DIR / "Library/Application Support/Steam")
    else:
        roots.extend(HOME_DIR / p for p in (
            ".local/share/Steam", ".steam/steam", ".steam/root",
            ".var/app/com.valvesoftware.Steam/.local/share/Steam",
        ))
        roots.extend(Path("/run/media").glob("*/*"))
        roots.extend(Path("/media").glob("*/*"))
        roots.append(Path("/run/media/mmcblk0p1"))
    return list(dict.fromkeys(p.resolve() for p in roots if p.is_dir()))


def steam_libraries():
    libraries = []
    for root in steam_roots():
        apps = root / "steamapps"
        libraries.append(apps)
        try:
            vdf = (apps / "libraryfolders.vdf").read_text(encoding="utf-8", errors="replace")
        except OSError:
            continue
        libraries.extend(Path(p.replace("\\\\", "\\")) / "steamapps"
                         for p in re.findall(r'"path"\s+"([^"]+)"', vdf))
    return list(dict.fromkeys(p.resolve() for p in libraries if p.is_dir()))


def steam_zombie_agent():
    option = re.compile(r'"LaunchOptions"\s+"(?:\\.|[^"\r\n])*(?:zbNative|ZombieBuddy)', re.I)
    for folder in steam_roots():
        for path in folder.glob("userdata/*/config/localconfig.vdf"):
            try:
                if option.search(path.read_text(encoding="utf-8", errors="replace")):
                    return True
            except OSError:
                continue
    return False


def identify(path):
    root = Path(path).expanduser().resolve()
    userdata = [HOME_DIR / "Zomboid"]
    if sys.platform == "darwin":
        if root.suffix != ".app":
            candidates = sorted(root.glob("*.app"))
            for candidate in candidates:
                with contextlib.suppress(ValueError):
                    return identify(candidate)
            raise ValueError("Choose the Project Zomboid application or its Steam folder.")
        contents = root / "Contents"
        config = contents / "Info.plist"
        java_dir = contents / "Java"
        if not config.is_file() or not (java_dir / "projectzomboid.jar").is_file():
            raise ValueError("The application does not contain the Project Zomboid launcher and game JAR.")
        java = next((p for p in [contents / "Home/bin/java", *contents.glob("PlugIns/*/Contents/Home/bin/java")]
                     if p.is_file()), None)
        return Game(root, "macOS", (config,), (java_dir,), java, tuple(userdata))

    if root.name.lower() == "projectzomboid" and root.parent.name.lower() in ("projectzomboid", "project zomboid"):
        root = root.parent
    dirs = [root, root / "projectzomboid"]
    configs = tuple(p / JSON_FILE for p in dirs if (p / JSON_FILE).is_file())
    if not configs or not any((p / "projectzomboid.jar").is_file() for p in dirs):
        raise ValueError(f"Choose a game folder containing {JSON_FILE} and projectzomboid.jar.")
    windows = any((p / "ProjectZomboid64.exe").is_file() for p in dirs)
    if sys.platform == "win32" and not windows:
        raise ValueError("The 64-bit Windows launcher is missing from this game folder.")
    platform = "Windows" if sys.platform == "win32" else "Proton" if windows else "Linux"
    jar_dirs = tuple(dict.fromkeys((*[p.parent for p in configs],
                                   *[p for p in dirs if (p / "projectzomboid.jar").is_file()])))
    executable = "java.exe" if windows else "java"
    java = next((p / home / "bin" / executable for p in dirs for home in ("jre64", "jre")
                 if (p / home / "bin" / executable).is_file()), None)
    if platform == "Proton":
        prefix = root.parent.parent / "compatdata/108600/pfx/drive_c/users/steamuser"
        if prefix.is_dir():
            userdata.append(prefix / "Zomboid")
    return Game(root, platform, configs, jar_dirs, java, tuple(userdata))


def discover():
    games = {}
    roots = [lib / "common" / name for lib in steam_libraries()
             for name in ("ProjectZomboid", "Project Zomboid")]
    if sys.platform == "win32":
        mask = ctypes.windll.kernel32.GetLogicalDrives()
        for i in range(26):
            if mask & (1 << i):
                drive = Path(chr(65 + i) + ":/")
                roots.extend((drive / "SteamLibrary/steamapps/common/ProjectZomboid",
                              drive / "GOG Games/Project Zomboid"))
    for path in roots:
        if path.is_dir():
            with contextlib.suppress(ValueError, OSError):
                game = identify(path)
                games[game.root] = game
    return list(games.values())


def installed_version(source):
    try:
        with ZipFile(source) as jar:
            manifest = jar.read("META-INF/MANIFEST.MF").decode("utf-8", errors="replace")
            if "com/pzoptimizer/PZOEntrypoint.class" not in jar.namelist():
                return None
            version = re.search(r"^Implementation-Version:\s*([^\r\n]+)", manifest, re.MULTILINE)
            return version[1].strip() if version else "Unknown version"
    except (BadZipFile, KeyError, OSError):
        return None


def source_file(name):
    for folder in (ASSETS, ASSETS / "dist", ASSETS / "native"):
        path = folder / name
        if path.is_file():
            return path
    return None


# The socket timeout covers each read, not a DNS lookup or a connection that trickles bytes, so a broken network
# could leave setup waiting with no end. Each request gets a hard deadline on top.
FETCH_DEADLINE = 60


def fetch(url, limit=100 * 1024 * 1024, *, accept=None):
    headers = {"User-Agent": "PZO-Installer"}
    if accept:
        headers["Accept"] = accept
    outcome = {}

    def request():
        try:
            with urlopen(Request(url, headers=headers), timeout=15) as response:
                outcome["data"] = response.read(limit + 1)
        except BaseException as error:
            outcome["error"] = error

    worker = threading.Thread(target=request, daemon=True)
    worker.start()
    worker.join(FETCH_DEADLINE)
    if worker.is_alive():
        raise TimeoutError(f"GitHub did not answer within {FETCH_DEADLINE} seconds.")
    if "error" in outcome:
        raise outcome["error"]
    data = outcome.get("data")
    if not data or len(data) > limit:
        raise ValueError("The GitHub response was empty or exceeded the expected size.")
    return data


def release_json(suffix):
    try:
        return json.loads(fetch(RELEASE_API + suffix, limit=4 * 1024 * 1024,
                                accept="application/vnd.github+json"))
    except HTTPError as error:
        if error.code in (403, 429) and error.headers and (
                error.headers.get("X-RateLimit-Remaining") == "0" or error.code == 429):
            raise ValueError("GitHub is rate limiting release requests. Try again later.") from error
        raise ValueError(f"GitHub could not return PZO releases (HTTP {error.code}). Try again.") from error
    except (OSError, HTTPException, ValueError) as error:
        raise ValueError("Could not read PZO releases from GitHub. Check your connection and try again.") from error


def parse_engine_release(record):
    if not isinstance(record, dict) or record.get("draft"):
        return None
    tag, published, assets = record.get("tag_name"), record.get("published_at"), record.get("assets")
    if not isinstance(tag, str) or not tag or not isinstance(published, str) or not isinstance(assets, list):
        return None
    for asset in assets:
        if not isinstance(asset, dict) or asset.get("name") != launcher.ENGINE or asset.get("state") != "uploaded":
            continue
        size = asset.get("size")
        if not isinstance(size, int) or not 0 < size <= 100 * 1024 * 1024:
            continue
        digest = asset.get("digest") or ""
        if not isinstance(digest, str) or (digest and not re.fullmatch("sha256:[a-fA-F0-9]{64}", digest)):
            raise ValueError(f"GitHub returned an invalid engine checksum for {tag}.")
        return EngineRelease(tag, published, bool(record.get("prerelease")), size, digest.lower())
    return None


def recent_releases():
    releases = {}
    page = 1
    while len(releases) < RELEASE_COUNT:
        records = release_json(f"?per_page=30&page={page}")
        if not isinstance(records, list):
            raise ValueError("GitHub returned an invalid PZO release list. Try again.")
        for record in records:
            release = parse_engine_release(record)
            if release:
                releases.setdefault(release.tag, release)
        if len(records) < 30:
            break
        page += 1
    if not releases:
        raise ValueError("GitHub has no published PZO releases with an engine JAR.")
    return sorted(releases.values(), key=lambda release: release.published, reverse=True)[:RELEASE_COUNT]


def latest_release():
    release = parse_engine_release(release_json("/latest"))
    if release is None:
        raise ValueError("GitHub's latest PZO release has no engine JAR. Choose another release.")
    return release


def download_engine(release):
    url = f"{RELEASES}/download/{quote(release.tag, safe='')}/{launcher.ENGINE}"
    try:
        data = fetch(url, limit=release.size)
    except (OSError, HTTPException, ValueError) as error:
        raise ValueError(f"Could not download PZO {release.tag} from GitHub. Try again.") from error
    if len(data) != release.size:
        raise ValueError(f"The PZO {release.tag} download was incomplete. Try again.")
    if release.digest and f"sha256:{hashlib.sha256(data).hexdigest()}" != release.digest:
        raise ValueError(f"The PZO {release.tag} download failed its GitHub checksum. Try again.")
    return data


def ram_gib():
    if sys.platform == "win32":
        class Memory(ctypes.Structure):
            _fields_ = [("length", ctypes.c_ulong), ("load", ctypes.c_ulong)] + [
                (name, ctypes.c_ulonglong) for name in ("total", "available", "page", "page_free",
                                                       "virtual", "virtual_free", "extended")
            ]
        memory = Memory()
        memory.length = ctypes.sizeof(memory)
        return memory.total / 2**30 if ctypes.windll.kernel32.GlobalMemoryStatusEx(ctypes.byref(memory)) else 0
    if sys.platform == "darwin":
        return int(run(["sysctl", "-n", "hw.memsize"]).stdout.strip() or 0) / 2**30
    return os.sysconf("SC_PHYS_PAGES") * os.sysconf("SC_PAGE_SIZE") / 2**30


def effective_args(config, game):
    args = list(launcher.arguments(config.get("vmArgs", [])))
    block = config.get("windows", {})
    if game.platform == "Windows" and isinstance(block, dict):
        win = sys.getwindowsversion()
        current = (win.major, win.minor, win.build, 0)
        candidates = {}
        for key, value in block.items():
            if re.fullmatch(r"\d+(?:\.\d+){1,3}", key) and isinstance(value, dict):
                ver = tuple(map(int, key.split(".")))
                ver += (0,) * (4 - len(ver))
                if ver <= current:
                    candidates[ver] = value
        if candidates:
            args.extend(launcher.arguments(candidates[max(candidates)].get("vmArgs", [])))
    return args


def java_result(game, flags):
    if game.java is None or game.platform == "Proton":
        return None
    return run([game.java, *flags, "-version"])


def java_profile(game):
    result = java_result(game, [])
    if result is not None:
        if result.returncode:
            raise ValueError("The bundled Java runtime could not start. Verify game files and try again.")
        match = re.search(r'version "(?:1\.)?(\d+)', result.stderr + result.stdout)
        if not match:
            raise ValueError("Could not read the bundled Java version.")
        major = int(match[1])
    else:
        major = 17
        if game.java:
            release = game.java.parent.parent / "release"
            if release.is_file():
                match = re.search(r'JAVA_VERSION="(?:1\.)?(\d+)', release.read_text(errors="replace"))
                if match:
                    major = int(match[1])
    if major < 17:
        raise ValueError("PZO requires a Java 17 or newer game runtime.")
    memory = ram_gib()
    compat = 0 < memory < 7.5 or 0 < (os.cpu_count() or 0) <= 4
    if game.platform == "Windows" and os.environ.get(
            "PROCESSOR_ARCHITEW6432", os.environ.get("PROCESSOR_ARCHITECTURE")) != "ARM64":
        compat |= not ctypes.windll.kernel32.IsProcessorFeaturePresent(40)
    for home in reversed(game.user_data):
        try:
            override = json.loads((home / "Lua/pzo_config.json").read_bytes()).get("compat_mode")
        except (OSError, ValueError, AttributeError):
            continue
        if override in ("on", "off"):
            compat = override == "on"
        break
    capacity = round(memory) or 8
    if game.platform == "macOS":
        heap = 8192 if capacity >= 32 else 6144 if capacity >= 16 else 4096 if capacity >= 8 else 3072
    else:
        heap = 16384 if capacity > 20 else 8192 if capacity > 12 else 6144 if capacity > 5 else 4096
    if 0 < memory < 6:
        heap = min(heap, max(1024, int(memory * 512)))
    gc = launcher.G1
    if game.platform != "macOS" and major >= 25 and memory >= 15 and not compat:
        probe = java_result(game, launcher.SHENANDOAH)
        if probe is not None and probe.returncode == 0:
            gc = launcher.SHENANDOAH
    return major, gc, heap, compat, result is not None


def collector_key(args):
    return next(key for key, (_, flags) in COLLECTORS.items() if flags == args)


def supports_collector(game, key):
    result = java_result(game, COLLECTORS[key][1])
    return result.returncode == 0 if result is not None else key in PORTABLE_COLLECTORS


def heap_mib(text, memory):
    try:
        amount = Decimal(text)
        if not amount.is_finite() or not (Decimal("0.5") <= amount < Decimal(2**43)):
            raise ValueError
        if memory > 0 and amount > Decimal(str(round(memory, 2))):
            raise ValueError
        return int((amount * 1024).to_integral_value(rounding=ROUND_HALF_UP))
    except (InvalidOperation, ValueError):
        pass
    limit = f" up to {memory:.2f} GiB" if memory > 0 else ""
    raise ValueError(f"Enter a maximum heap of at least 0.5 GiB{limit}.")


def gib_text(mib):
    return format(Decimal(mib) / 1024, "f")


def preferences_path(home):
    return home / "Lua/pzo_config.json"


def read_preferences(path, data):
    if data is None:
        return {}
    try:
        settings = json.loads(data.decode("utf-8-sig"))
        if isinstance(settings, dict):
            return settings
    except ValueError:
        pass
    raise ValueError(f"Invalid PZO engine settings in {path}. Repair that JSON before applying setup.")


def retained_jvm_settings(game, preference_files=None):
    preserve = False
    for home in reversed(game.user_data):
        path = preferences_path(home)
        data = file_bytes(path) if preference_files is None else preference_files[path]
        config = read_preferences(path, data)
        if "preserve_gc" in config:
            preserve = config["preserve_gc"] is True
            break
    if not preserve:
        return None, None
    try:
        if game.platform == "Windows" and previous_mode(game) == "alternative":
            argfile = (game.root / SCRIPT).with_suffix(".args")
            args = [json.loads(line) for line in argfile.read_text(encoding="utf-8").splitlines()]
        else:
            config = launcher.read_config(game.configs[0])
            if game.platform == "macOS":
                holder, key = launcher.plist_primary(config)
                args = list(launcher.arguments(holder[key]))
            else:
                args = effective_args(config, game)
    except (OSError, ValueError, TypeError):
        return None, None
    selected = [key for key, (_, flags) in COLLECTORS.items() if set(flags).issubset(args)]
    maximum = next((launcher.heap_bytes(a) // 2**20 for a in reversed(args)
                    if a.startswith("-Xmx") and launcher.heap_bytes(a)), None)
    return (selected[0] if len(selected) == 1 else None), maximum


def detected_settings(game):
    profile = java_profile(game)
    recommended = collector_key(profile[1])
    supported = [key for key in COLLECTORS
                 if key == recommended or supports_collector(game, key)]
    saved_gc, saved_heap = retained_jvm_settings(game)
    memory = ram_gib()
    if saved_heap is None and game.platform == "Windows":
        try:
            config = launcher.read_config(game.configs[0])
            _, maximum = launcher.windows_heap(config.get("vmArgs", []), f"-Xmx{profile[2]}m", profile[3])
            saved_heap = launcher.heap_bytes(maximum) // 2**20
        except (OSError, ValueError, TypeError):
            pass
    if saved_heap and (saved_heap < 512 or (memory and saved_heap / 1024 > memory)):
        saved_heap = None
    return recommended, profile[2], supported, saved_gc, saved_heap, memory


def marked(data):
    return bool(data and any(token in data.lower() for token in (
        b"pzoptimengine.jar", b"pzoentrypoint", b"pzo_native64",
    )))


def installed(game):
    return any((p / launcher.ENGINE).is_file() for p in game.jar_dirs) or any(
        marked(file_bytes(p)) for p in game.configs
    ) or (game.root / SCRIPT).is_file()


def previous_mode(game):
    if game.platform == "Windows" and (game.root / SCRIPT).is_file():
        return "both" if marked(file_bytes(game.configs[0])) else "alternative"
    return "standard"


def backup_location(game, path):
    return game.backup / "PZO_Stock" / path.relative_to(game.root)


def pristine(game, path, current):
    candidates = [backup_location(game, path), path.with_name(path.name + ".bak")]
    if path == game.configs[0]:
        candidates.extend(sorted(game.backup.glob(path.name + "_*.bak")))
    for data in [current, *(file_bytes(p) for p in candidates)]:
        try:
            if data is not None and not marked(data) and not launcher.stock_conflicts(
                    launcher.read_config(path, data), game.platform == "macOS"):
                return data
        except (ValueError, TypeError):
            continue
    return None


def load_config(game, path, current, stock, notes):
    """The current launcher config, recovered when it is malformed (a cut-off write or stray commas)."""
    try:
        return launcher.read_config(path, current)
    except (ValueError, TypeError) as error:
        failure = error
    if path.suffix == ".json":
        repaired = launcher.repair_json(current) if current else None
        if repaired is not None:
            notes.append(f"Repaired malformed {path.name} (it was cut off or had broken formatting).")
            return repaired
    if stock:
        notes.append(f"Rebuilding malformed {path.name} from its stock backup.")
        return launcher.read_config(path, stock)
    if path.suffix == ".json" and game.platform == "Windows" and path == game.configs[0]:
        notes.append(f"Rebuilding malformed {path.name} from the game's default settings.")
        return launcher.stock_windows()
    raise ValueError(f"Invalid {path.name}. Verify game files in Steam, then retry.") from failure


def check_class_path(config, game, path):
    """The class path the JVM ends up with must hold the engine, or the game can't find PZOEntrypoint."""
    if game.platform == "macOS" or config.get("mainClass") != launcher.ENTRY:
        return
    args = effective_args(config, game)
    pins = [a[len(launcher.CLASS_PATH):] for a in args if a.startswith(launcher.CLASS_PATH)]
    if pins:
        entries = pins[-1].split(";" if game.platform in ("Windows", "Proton") else ":")
    else:
        entries = config.get("classpath", [])
    names = {re.split(r"[\\/]", e.strip())[-1].lower() for e in entries}
    for needed in (launcher.ENGINE, "projectzomboid.jar"):
        if needed.lower() not in names:
            raise ValueError(f"{path.name} would start the game without {needed} on its class path.")


def uninstall_config(game, path, current):
    stock = pristine(game, path, current)
    if stock is not None:
        return stock
    config = load_config(game, path, current, None, [])
    separator = ";" if game.platform in ("Windows", "Proton") else ":"
    for holder, key, _ in launcher.option_lists(config, {"vmArgs"}):
        holder[key] = [
            launcher.CLASS_PATH + separator.join(e for e in a[len(launcher.CLASS_PATH):].split(separator)
                                                 if launcher.ENGINE.lower() not in e.lower())
            if a.startswith(launcher.CLASS_PATH) else a
            for a in launcher.arguments(holder[key])
        ]
    keys = {"JVMOptions", "VMOptions"} if game.platform == "macOS" else {"vmArgs"}
    compat_flags = ("-XX:+EnableDynamicAgentLoading", "-XX:+UseCompactObjectHeaders")
    managed = {launcher.name_of(a) for a in (*launcher.TECHNICAL, *compat_flags, "-Xms")}
    for holder, key, _ in launcher.option_lists(config, keys):
        holder[key] = launcher.maintenance_args(holder[key], managed)
        if not holder[key] and holder is not config:
            del holder[key]
    if game.platform != "macOS":
        launcher.prune_platform_options(config)
    if game.platform == "macOS":
        for holder in (config, config.get("Java"), config.get("JVMOptions")):
            if isinstance(holder, dict):
                for key in launcher.MAIN_KEYS:
                    if "pzoentrypoint" in str(holder.get(key, "")).lower():
                        holder[key] = launcher.STOCK_ENTRY
                for key in ("ClassPath", "JVMClassPath"):
                    value = holder.get(key)
                    if isinstance(value, list):
                        holder[key] = [entry for entry in value if launcher.ENGINE.lower() not in str(entry).lower()]
                    elif isinstance(value, str):
                        holder[key] = ":".join(entry for entry in value.split(":")
                                               if launcher.ENGINE.lower() not in entry.lower())
    else:
        if "pzoentrypoint" in str(config.get("mainClass", "")).lower():
            config["mainClass"] = launcher.STOCK_ENTRY
        if isinstance(config.get("classpath"), list):
            config["classpath"] = [p for p in config["classpath"] if launcher.ENGINE.lower() not in str(p).lower()]
    return launcher.encode(path, config)


def alt_launcher(config, game, file):
    if game.java is None:
        raise ValueError("The bundled Java executable is required for the alternative launcher.")
    # -cp below sets the class path, so a pinned -Djava.class.path would only fight it
    args = [a for a in effective_args(config, game) if not a.startswith(launcher.CLASS_PATH)]
    if sum(bool(launcher.SELECTOR.fullmatch(a)) for a in args) != 1:
        raise ValueError("The alternative launcher must use one collector.")
    # Store arguments in a Java argfile so cmd never interprets paths or JVM property values.
    values = [*args, "-cp", ";".join(config["classpath"]), launcher.ENTRY.replace("/", ".")]
    escaped = ['"' + value.replace("\\", "\\\\").replace('"', '\\"') + '"' for value in values]
    argfile = file.with_suffix(".args")
    batch = [
        "@echo off", "setlocal DisableDelayedExpansion", 'cd /d "%~dp0"',
        "set _JAVA_OPTIONS=", "set JAVA_TOOL_OPTIONS=", "set JDK_JAVA_OPTIONS=",
        f'"{game.java.relative_to(file.parent)}" @"%~dp0{argfile.name}" %*', "exit /b %ERRORLEVEL%",
    ]
    return {file: ("\r\n".join(batch) + "\r\n").encode("ascii"),
            argfile: ("\n".join(escaped) + "\n").encode("utf-8")}


def native_files(game, notes, allow_download):
    if game.platform in ("Windows", "Proton"):
        names = ("pzo_native64.dll", "win64/pzo_native64.dll")
        return {p / name: None for p in game.jar_dirs for name in names}, False
    mac = game.platform == "macOS"
    name = "libpzo_native64.dylib" if mac else "libpzo_native64.so"
    source = source_file(name)
    targets = ((game.jar_dirs[0] / name, game.root / "Contents/MacOS" / name) if mac else tuple(
        p / relative for p in game.jar_dirs for relative in (name, "linux64/" + name, "natives/" + name)
    ))
    if source:
        data = source.read_bytes()
    elif any(p.is_file() for p in targets):
        data = next(p.read_bytes() for p in targets if p.is_file())
    elif allow_download:
        try:
            data = fetch(f"{RELEASES}/download/V{NATIVE_VERSION}/{name}", limit=8 * 1024 * 1024)
        except (OSError, ValueError) as error:
            notes.append(f"Native companion unavailable. {error}")
            return {}, False
    else:
        return {}, False
    if mac:
        if not source:
            notes.append("The macOS native companion is optional. The existing library is left in place.")
            return {}, False
        executables = game.root / "Contents/MacOS"
        binary = next((p for p in executables.glob("*") if p.is_file() and os.access(p, os.X_OK)
                       and not p.name.endswith(".dylib")), None)
        game_arch = run(["lipo", "-archs", binary]) if binary else None
        native_arch = run(["lipo", "-archs", source])
        if not game_arch or game_arch.returncode or native_arch.returncode or not (
            set(game_arch.stdout.split()) & set(native_arch.stdout.split())
        ):
            notes.append("The optional macOS native companion does not match the game architecture.")
            return {}, False
    elif hashlib.sha256(data).hexdigest() != LINUX_NATIVE_SHA256:
        notes.append("The optional native companion does not match the pinned release and will be skipped.")
        return {}, False
    return dict.fromkeys(targets, data), True


def removal_files(game):
    names = (launcher.ENGINE, "pzo_native64.dll", "win64/pzo_native64.dll",
             "libpzo_native64.so", "linux64/libpzo_native64.so", "natives/libpzo_native64.so",
             "libpzo_native64.dylib", "pzo_engine.log", "pzo_telemetry.json", "pzo_status.json")
    result = {p / name: None for p in game.jar_dirs for name in names}
    if game.platform == "macOS":
        result[game.root / "Contents/MacOS/libpzo_native64.dylib"] = None
    return result


def user_data_files(game, status=None, *, preference_files=None):
    result = {}
    reset = ("FBORenderChunk.CorpsesInChunkTexture", "FBORenderChunk.ItemsInChunkTexture",
             "Lighting.SplitUpdate", "Threading.Lighting")
    for home in game.user_data:
        if status is None:
            for folder in (home, home / "Lua"):
                result.update({p: None for p in folder.glob("pzo_*") if p.is_file()})
        else:
            settings_path = preferences_path(home)
            settings = read_preferences(settings_path, preference_files[settings_path])
            if settings.get("preserve_gc") is not True:
                settings["preserve_gc"] = True
                result[settings_path] = (json.dumps(settings, indent=2, ensure_ascii=True) + "\n").encode("ascii")
            if game.platform != "macOS":
                result[home / "Lua/pzo_status.json"] = json.dumps(status, ensure_ascii=True).encode("ascii")
        if game.platform == "macOS" or status is None:
            path = home / "debug-options.ini"
            data = file_bytes(path)
            if data:
                for key in reset:
                    data = data.replace((key + "=true").encode(), (key + "=false").encode())
                result[path] = data
    return result


def game_running(game):
    if game.platform == "Windows":
        for name in ("ProjectZomboid64.exe", "ProjectZomboid32.exe"):
            result = run(["tasklist", "/NH", "/FO", "CSV", "/FI", f"IMAGENAME eq {name}"])
            if f'"{name}"'.lower() in result.stdout.lower():
                return True
        # Both the stock and alternative batch launchers can appear as java.exe.
        query = ("Get-CimInstance Win32_Process -Filter \"Name = 'java.exe' OR Name = 'javaw.exe'\" | "
                 "ForEach-Object { $_.CommandLine }")
        result = run(["powershell", "-NoProfile", "-NonInteractive", "-Command", query])
        return any("projectzomboid" in line.lower() or "pzoentrypoint" in line.lower()
                   for line in result.stdout.splitlines())
    result = run(["ps", "-A", "-o", "command="])
    return any((str(game.root).lower() in line.lower() and
                ("projectzomboid" in line.lower() or "pzoentrypoint" in line.lower()))
               or "zombie.gamestates.mainscreenstate" in line.lower()
               for line in result.stdout.splitlines())


def probe_config(config, game):
    if game.platform == "Proton" or not game.java:
        return
    if game.platform == "macOS":
        args = [a for holder, key, _ in launcher.option_lists(config, {"JVMOptions", "VMOptions"})
                for a in launcher.arguments(holder[key])]
    else:
        args = effective_args(config, game)
    # Probe VM flags without loading agents, starting the game, or pre-touching the configured heap. AOT flags
    # stay out: -XX:AOTCacheOutput would make the probe write another mod's AOT cache.
    safe = [a for a in args if a.startswith(("-XX:", "-Xm", "--add-exports=", "--enable-native-access="))
            and not a.startswith(("-XX:On", "-XX:+CrashOn", "-XX:LogFile", "-XX:HeapDumpPath",
                                  "-XX:ErrorFile", "-XX:ReplayDataFile", "-XX:VMOptionsFile",
                                  "-XX:Flags")) and a != "-XX:+AlwaysPreTouch"
            and not re.match(r"-XX:[+-]?AOT", a)]
    result = java_result(game, safe)
    if result and result.returncode:
        details = (result.stderr or result.stdout).strip()[:600]
        raise ValueError("The bundled Java runtime rejected the reviewed JVM flags. " + details)


def make_plan(game, action, mode, progress=lambda message: None, *,
              release=None, collector=None, max_heap=None):
    if mode not in ("standard", "both", "alternative") or action not in (
            "install", "update", "repair", "uninstall"):
        raise ValueError("Invalid installer action or launch mode.")
    if release is not None and action not in ("install", "update"):
        raise ValueError("Choose Install or Update to select a GitHub release.")
    if game.platform != "Windows" and mode != "standard":
        raise ValueError("The alternative batch launcher is available on Windows.")
    progress("Reading launcher settings")
    current = {p: file_bytes(p) for p in game.configs}
    preference_files = {preferences_path(home): file_bytes(preferences_path(home)) for home in game.user_data}
    version = next((v for p in game.jar_dirs if (v := installed_version(p / launcher.ENGINE))), "Unknown version")
    notes = []
    files = {}
    bat = game.root / SCRIPT
    files.update({bat: None, bat.with_suffix(".args"): None})
    if action != "install" and not installed(game):
        raise ValueError("No PZO installation was found. Choose Install.")
    if action == "uninstall":
        for p, data in current.items():
            if pristine(game, p, data) is None:
                notes.append(f"No usable stock backup for {p.name}. PZO settings will be removed from the current file.")
            files[p] = uninstall_config(game, p, data)
        files.update(removal_files(game))
        files.update(user_data_files(game))
        gc_name, heap_name = "Game settings", "Game settings"
        mode = "standard"
    else:
        if ((game.root / "pzopt/agent/pzopt-agent.jar").is_file() or any(
                b"pzopt-agent.jar" in (data or b"").lower() for data in current.values())):
            raise ValueError("PZ_Optimization is installed or configured. Uninstall it and clear its Steam launch option first.")
        if action == "repair":
            progress("Checking the installed engine")
            source = next((p / launcher.ENGINE for p in game.jar_dirs if installed_version(p / launcher.ENGINE)), None)
            if source is None:
                raise ValueError("No usable installed engine was found. Choose Update to replace it.")
            data = source.read_bytes()
            notes.append("Engine package from the installed PZOptimEngine.jar.")
        else:
            if release is None:
                progress("Checking the latest release on GitHub")
                release = latest_release()
            elif isinstance(release, str):
                tag = release
                release = next((item for item in recent_releases() if item.tag.casefold() == tag.casefold()), None)
                if release is None:
                    raise ValueError(f"PZO {tag} is not among the five most recent GitHub releases with an engine JAR.")
            progress(f"Downloading PZO {release.tag} from GitHub")
            data = download_engine(release)
            notes.append(f"Engine package from GitHub release {release.tag}.")
        package_version = installed_version(BytesIO(data))
        if package_version is None:
            raise ValueError("The engine package is not a valid PZO client JAR.")
        if action == "update" and package_version != version:
            notes.append(f"Engine version will change from {version} to {package_version}.")
        version = package_version
        files.update({p / launcher.ENGINE: data for p in game.jar_dirs})
        progress("Selecting a collector and heap")
        major, gc, heap, compat, probe = java_profile(game)
        retained_gc, retained_heap = retained_jvm_settings(game, preference_files)
        if collector is not None and (collector not in COLLECTORS or not supports_collector(game, collector)):
            raise ValueError("The selected collector is unavailable for the detected game JVM.")
        if collector is None and retained_gc and supports_collector(game, retained_gc):
            collector = retained_gc
        gc_name = COLLECTORS[collector_key(gc)][0]
        if collector is not None:
            gc_name, gc = COLLECTORS[collector]
        if max_heap is not None:
            heap = heap_mib(max_heap, ram_gib())
        elif retained_heap:
            heap = retained_heap
        explicit_heap = max_heap is not None or retained_heap is not None
        notes.append("Setup sets preserve_gc in the PZO engine settings.")
        with ZipFile(BytesIO(data)) as jar:
            config_class = "com/pzoptimizer/PZOConfig.class"
            if config_class not in jar.namelist() or b"preserve_gc" not in jar.read(config_class):
                notes.append("This engine predates preserve_gc and may change the collector on startup. "
                             "Update to a version that supports preserve_gc to keep this choice.")
        heaps = set()
        if not probe:
            notes.append("Using conservative flags. The game JVM could not be probed from this environment.")
        if compat:
            notes.append("Using compatibility settings for this machine.")
        companion, native = native_files(game, notes, allow_download=action != "repair")
        files.update(companion)
        zombie_jar = any((p / "ZombieBuddy.jar").is_file() for p in game.jar_dirs)
        external_zombie_agent = game.platform in ("Windows", "Proton") and steam_zombie_agent()
        zombie_agent = external_zombie_agent
        if external_zombie_agent:
            notes.append("Steam launch options supply the ZombieBuddy agent.")
        # Its name looks like PZO's, so a lot of people add it by mistake
        if (HOME_DIR / ".zombie_buddy" / "PZOptPreloader").is_dir() or any(
                b"pzoptpreloader" in (data or b"").lower() for data in current.values()):
            notes.append("PZOptPreloader is installed. It is a separate Steam Workshop mod, not part of PZO, and PZO "
                         "does not need it. If you added it thinking it was part of PZO, unsubscribe from it on the "
                         "Workshop. If you use it on purpose, PZO keeps working alongside it.")
        for index, (p, existing) in enumerate(current.items()):
            stock = pristine(game, p, existing)
            config = load_config(game, p, existing, stock, notes)
            # Use the equivalent GiB spelling for a manually selected 3 GiB heap. Older
            # engines treat the stock spelling (-Xmx3072m) as an unset maximum.
            heap_arg = f"-Xmx{heap // 1024}g" if explicit_heap and heap == 3072 else f"-Xmx{heap}m"
            start_arg = f"-Xms{min(1024, heap)}m"
            if game.platform == "Windows":
                initial, previous_max = launcher.windows_heap(config.get("vmArgs", []), heap_arg, compat)
                if not explicit_heap:
                    start_arg, heap_arg = initial, previous_max
                elif launcher.heap_bytes(initial) <= launcher.heap_bytes(heap_arg):
                    start_arg = initial
            heaps.add(launcher.heap_label(heap_arg))
            if game.platform == "macOS":
                original = launcher.read_config(p, stock) if stock else {}
                launcher.patch_plist(config, original, gc, heap, major, game.engine)
            else:
                library = ("zbNative.dll", "win64/zbNative.dll") if game.platform in ("Windows", "Proton") else (
                    "libzbNative.so", "linux64/libzbNative.so", "natives/libzbNative.so"
                )
                has_zombie_agent = any((p.parent / suffix).is_file() for suffix in library)
                zombie_agent |= has_zombie_agent
                if launcher.patch_json(config, game.platform, gc, start_arg, heap_arg, major, native, compat,
                                       zombie_jar, has_zombie_agent and not external_zombie_agent, p.parent):
                    notes.append(f"Another mod pinned the class path in {p.name} to the "
                                 "game jar alone. Setup adds PZO's jars to it so the game can start, and PZO "
                                 "checks it again each time the game closes.")
            progress(f"Validating {p.name}")
            output = launcher.encode(p, config)
            roundtrip = launcher.read_config(p, output)
            if game.platform == "macOS":
                primary = launcher.plist_primary(roundtrip)
                launcher.validate_gc(roundtrip, {"JVMOptions", "VMOptions"}, primary, gc[0])
            else:
                launcher.validate_gc(roundtrip, {"vmArgs"}, (roundtrip, "vmArgs"), gc[0])
                check_class_path(roundtrip, game, p)
            probe_config(roundtrip, game)
            if stock and not backup_location(game, p).exists():
                files[backup_location(game, p)] = stock
            files[p] = output if mode != "alternative" else uninstall_config(game, p, existing)
            if index == 0 and mode in ("both", "alternative"):
                files.update(alt_launcher(roundtrip, game, bat))
        heap_name = " / ".join(sorted(heaps))
        files.update(user_data_files(game, {
            "optimized": True, "ram_gb": round(ram_gib()), "g1gc": gc == launcher.G1,
            "pretouch": not compat and game.platform != "macOS", "compat_mode": compat,
            "zombiebuddy": zombie_jar or zombie_agent, "version": version,
        }, preference_files=preference_files))
    originals = current | preference_files
    originals.update({p: file_bytes(p) for p in files if p not in originals})
    files = {p: data for p, data in files.items() if originals[p] != data}
    return Plan(game, action, version, mode, gc_name, heap_name, files, originals, notes)


def write_atomic(path, data, mode=None):
    if data is None:
        path.unlink(missing_ok=True)
        return
    path.parent.mkdir(parents=True, exist_ok=True)
    fd, name = tempfile.mkstemp(prefix=".pzo-", dir=path.parent)
    temp = Path(name)
    try:
        with os.fdopen(fd, "wb") as stream:
            stream.write(data)
            stream.flush()
            os.fsync(stream.fileno())
        if mode is not None:
            temp.chmod(mode)
        temp.replace(path)
    finally:
        temp.unlink(missing_ok=True)


def sign(game):
    result = run(["codesign", "--force", "--deep", "--sign", "-", game.root], timeout=60)
    if result.returncode:
        raise OSError("macOS could not re-sign the application. " + result.stderr.strip())
    result = run(["codesign", "--verify", "--deep", game.root], timeout=60)
    if result.returncode:
        raise OSError("macOS could not verify the game signature. " + result.stderr.strip())


def apply(plan, progress=lambda message: None):
    if not plan.files:
        return None
    game = plan.game
    if game_running(game):
        raise ValueError("Project Zomboid is running. Close it, then retry.")
    game.backup.mkdir(parents=True, exist_ok=True)
    lock = game.backup / ".installer.lock"
    try:
        claim = lock.open("x")
    except FileExistsError as error:
        raise ValueError(f"Another installer may be active. Close it before retrying. Lock: {lock}") from error
    backups = game.backup / datetime.now().strftime("PZO_%Y%m%d_%H%M%S_%f")
    applied = []
    modes = {}
    try:
        with claim:
            claim.write(f"PID {os.getpid()}\n")
        for path, snapshot in plan.originals.items():
            if file_bytes(path) != snapshot:
                raise ValueError(f"{path} changed after review. Go back and review the installation again.")
        progress("Backing up the current files")
        records = []
        for index, path in enumerate(plan.files):
            previous = plan.originals[path]
            mode = stat.S_IMODE(path.stat().st_mode) if previous is not None else None
            modes[path] = mode
            name = None
            if previous is not None:
                name = f"{index:03d}_{path.name}.bak"
                write_atomic(backups / name, previous)
            records.append({"path": str(path), "backup": name, "mode": mode})
        write_atomic(backups / "files.json", json.dumps(records, indent=2, ensure_ascii=True).encode("ascii"))
        try:
            for path, data in plan.files.items():
                progress(("Removing " if data is None else "Writing ") + path.name)
                write_atomic(path, data, modes[path])
                applied.append(path)
            if game.platform == "macOS":
                progress("Re-signing the game bundle")
                sign(game)
        except BaseException:
            failures = []
            for path in reversed(applied):
                try:
                    write_atomic(path, plan.originals[path], modes[path])
                except OSError:
                    failures.append(str(path))
            if game.platform == "macOS" and applied:
                try:
                    sign(game)
                except (OSError, subprocess.SubprocessError):
                    failures.append("macOS bundle signature")
            if failures:
                raise OSError("Rollback could not restore " + ", ".join(failures) + f". Backups: {backups}")
            raise
        return backups
    finally:
        lock.unlink(missing_ok=True)

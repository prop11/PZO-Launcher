"""Launcher configuration shared by the installer and repair workflows."""
import json
import plistlib
import re

ENGINE = "PZOptimEngine.jar"
ENTRY = "com/pzoptimizer/PZOEntrypoint"
STOCK_ENTRY = "zombie/gameStates/MainScreenState"
G1 = ("-XX:+UseG1GC",)
SHENANDOAH = ("-XX:+UseShenandoahGC", "-XX:ShenandoahGCMode=generational")
SELECTOR = re.compile(
    r"-XX:[+-]?Use(?:G1|Parallel(?:Old)?|Serial|ParNew|ConcMarkSweep|Z|Shenandoah|Epsilon|Graal)GC(?:=.*)?",
    re.IGNORECASE,
)
TOKEN = re.compile(r"""(?:[^\s"']+|"[^"]*"|'[^']*')+""")
TECHNICAL = (
    "-XX:-CreateCoredumpOnCrash", "-XX:-OmitStackTraceInFastThrow",
    "-XX:+PerfDisableSharedMem", "-XX:+UnlockExperimentalVMOptions",
    "-XX:+UseSuperWord", "-XX:MaxInlineLevel=15", "-XX:InlineSmallCode=2500",
)
GC_TUNING = re.compile(
    r"-XX:[+-]?(?:Z[A-Z]\w*|Shenandoah\w*|G1\w*|SoftMaxHeapSize|"
    r"InitiatingHeapOccupancyPercent)(?:=.*)?"
)
MAIN_KEYS = ("JVMMainClassName", "MainClass", "JVMEntrypoint")


def arguments(values):
    if not isinstance(values, (list, tuple)) or any(not isinstance(a, str) for a in values):
        raise ValueError("Expected a list of JVM arguments.")
    for value in values:
        value = value.strip()
        if value:
            # Launcher entries can contain paths with unquoted spaces. Split only at option boundaries.
            start = 0
            for part in TOKEN.finditer(value):
                if part.start() and part.group().startswith("-"):
                    yield value[start:part.start()].strip()
                    start = part.start()
            yield value[start:]


def option_lists(tree, keys, path=()):
    if isinstance(tree, dict):
        for key, value in list(tree.items()):
            if key in keys and isinstance(value, (list, tuple)):
                yield tree, key, path
            elif key == "vmArgs" and key in keys:
                raise ValueError("Invalid vmArgs at " + ".".join((*path, key)))
            elif isinstance(value, (dict, list)):
                yield from option_lists(value, keys, (*path, key))
    elif isinstance(tree, list):
        for index, item in enumerate(tree):
            yield from option_lists(item, keys, (*path, str(index)))


def gc_option(arg):
    return (SELECTOR.fullmatch(arg) or GC_TUNING.fullmatch(arg)
            or arg == "-XX:MaxGCPauseMillis=16")


def pzo_agent(arg):
    return arg.startswith("-agent") and any(
        name in arg.lower() for name in ("pzoptimengine.jar", "pzo_native64")
    )


def name_of(arg):
    if arg.startswith("-XX:"):
        return arg.split("=", 1)[0].replace("+", "").replace("-", "", 2)
    if arg.startswith(("-Xms", "-Xmx")):
        return arg[:4]
    return arg.split("=", 1)[0]


def read_config(path, data=None):
    data = path.read_bytes() if data is None else data
    result = plistlib.loads(data) if path.suffix == ".plist" else json.loads(data.decode("utf-8-sig"))
    if not isinstance(result, dict):
        raise ValueError(f"{path.name} must contain a configuration object.")
    return result


def encode(path, config):
    if path.suffix == ".plist":
        return plistlib.dumps(config, sort_keys=False)
    return (json.dumps(config, indent=4, ensure_ascii=True, allow_nan=False) + "\n").encode("ascii")


def maintenance_args(args, managed):
    return [a for a in arguments(args) if not (gc_option(a) or pzo_agent(a) or name_of(a) in managed)]


def prune_platform_options(config):
    for platform_key in ("windows", "linux", "macos", "osx"):
        block = config.get(platform_key)
        if isinstance(block, dict):
            for version in list(block):
                if block[version] == {}:
                    del block[version]
            if not block:
                del config[platform_key]


def heap_bytes(arg):
    match = re.fullmatch(r"-Xm[sx](\d+)([kKmMgG]?)", arg)
    if not match:
        return 0
    return int(match[1]) * {"": 1, "k": 2**10, "m": 2**20, "g": 2**30}[match[2].lower()]


def heap_label(arg):
    size = heap_bytes(arg)
    return f"{size / 2**30:g} GiB" if size >= 2**30 else f"{size / 2**20:g} MiB"


def windows_heap(values, default_max, compat):
    args = list(arguments(values))
    start = next((a for a in reversed(args) if a.startswith("-Xms") and heap_bytes(a)), "-Xms1024m")
    maximum = next((a for a in reversed(args) if a.startswith("-Xmx") and heap_bytes(a)), default_max)
    if maximum == "-Xmx3072m":
        maximum = default_max
    legacy = {"2048m 4096m", "3072m 6144m", "4096m 8192m",
              "4096m 12288m", "6144m 12288m", "8192m 16384m"}
    if compat or ("-XX:+AlwaysPreTouch" in args and f"{start[4:]} {maximum[4:]}" in legacy):
        start = "-Xms1024m"
    if heap_bytes(start) > heap_bytes(maximum):
        start = "-Xms" + maximum[4:]
    return start, maximum


def patch_json(config, platform, gc, start_arg, heap_arg, java_major, native, compat, zombie_jar, zombie_agent):
    windows = platform in ("Windows", "Proton")
    args = list(arguments(config.get("vmArgs", [])))
    additions = [*gc, *TECHNICAL, start_arg, heap_arg, "-Dzomboid.steam=1", "-Dzomboid.znetlog=1"]
    if not compat:
        additions.extend(("-XX:+AlwaysPreTouch", "-XX:+UseNUMA"))
    if windows and java_major >= 21:
        additions.append("-XX:+EnableDynamicAgentLoading")
    if gc == G1:
        additions.extend(("-XX:InitiatingHeapOccupancyPercent=45", "-XX:G1ReservePercent=15"))
    if platform == "Linux" and native:
        additions.append("-agentlib:pzo_native64")
    if zombie_agent:
        additions.append(next((a for a in args if name_of(a) == "-agentlib:zbNative"), "-agentlib:zbNative"))
    if java_major >= 17:
        additions.append("--add-exports=java.base/jdk.internal.misc=ALL-UNNAMED")
    if java_major >= 22:
        additions.append("--enable-native-access=ALL-UNNAMED")
    cp = config.get("classpath", [])
    if not isinstance(cp, list) or any(not isinstance(entry, str) for entry in cp):
        raise ValueError("Expected a list of launcher classpath entries.")
    cp = list(dict.fromkeys(entry for entry in cp if entry.lower() != ENGINE.lower()))
    if "." not in cp:
        cp.insert(0, ".")
    cp.insert(cp.index(".") + 1, ENGINE)
    if zombie_jar and "ZombieBuddy.jar" not in cp:
        cp.insert(cp.index(ENGINE) + 1, "ZombieBuddy.jar")
    if "projectzomboid.jar" not in cp:
        cp.append("projectzomboid.jar")
    config["classpath"] = cp
    config["mainClass"] = ENTRY

    config.setdefault("vmArgs", [])
    managed = {name_of(a) for a in additions}
    managed.update(name_of(a) for a in (
        "-XX:+AlwaysPreTouch", "-XX:+UseNUMA", "-XX:+EnableDynamicAgentLoading",
        "-XX:+UseCompactObjectHeaders", "-agentlib:zbNative", "-Djava.awt.headless",
    ))
    for holder, key, _ in option_lists(config, {"vmArgs"}):
        holder[key] = maintenance_args(holder[key], managed)
        if holder is not config and not holder[key]:
            del holder[key]
    prune_platform_options(config)
    library_prefix = "-Djava.library.path="
    separator = ";" if windows else ":"
    defaults = ["win64/" if windows else "linux64/", "natives/", "."]
    entries = []
    for arg in config["vmArgs"]:
        if arg.startswith(library_prefix):
            value = arg[len(library_prefix):]
            entries.extend(value.split(separator))
    config["vmArgs"] = [a for a in config["vmArgs"] if not a.startswith(library_prefix)]
    additions.append(library_prefix + separator.join(dict.fromkeys(
        p for p in (*defaults, *entries) if p
    )))
    config["vmArgs"].extend(additions)
    validate_gc(config, {"vmArgs"}, (config, "vmArgs"), gc[0])


def plist_primary(config):
    # JavaAppLauncher uses JVMOptions at the root on recent builds and Java/VMOptions on older bundles.
    legacy = config.get("Java")
    holders = [config]
    if isinstance(legacy, dict):
        holders.append(legacy)
    for holder, keys in zip(holders, (("JVMOptions", "VMOptions"), ("VMOptions", "JVMOptions"))):
        for key in keys:
            if isinstance(holder.get(key), list):
                return holder, key
    config["JVMOptions"] = []
    return config, "JVMOptions"


def patch_plist(config, stock, gc, heap, java_major, jar):
    for holder, original in ((config, stock), (config.get("Java"), stock.get("Java")),
                             (config.get("JVMOptions"), stock.get("JVMOptions"))):
        if not isinstance(holder, dict):
            continue
        for key in MAIN_KEYS:
            if "pzoptimizer" in str(holder.get(key, "")).lower():
                holder[key] = (original or {}).get(key, STOCK_ENTRY) if isinstance(original, dict) else STOCK_ENTRY
        for key in ("JVMClassPath", "ClassPath"):
            value = holder.get(key)
            if isinstance(value, list):
                holder[key] = [a for a in value if ENGINE.lower() not in str(a).lower()]
            elif isinstance(value, str):
                holder[key] = ":".join(p for p in value.split(":") if ENGINE.lower() not in p.lower())
    primary, primary_key = plist_primary(config)
    additions = [*gc, f"-javaagent:{jar}", f"-Xmx{heap}m", "-XX:+PerfDisableSharedMem",
                 "-XX:MaxInlineLevel=15", "-XX:InlineSmallCode=2500",
                 "-Djava.awt.headless=true", "-Dzomboid.steam=1"]
    if java_major >= 17:
        additions.append("--add-exports=java.base/jdk.internal.misc=ALL-UNNAMED")
    if java_major >= 22:
        additions.append("--enable-native-access=ALL-UNNAMED")
    keys = {"JVMOptions", "VMOptions"}
    managed = {name_of(a) for a in additions}
    managed.update(name_of(a) for a in ("-Xms", "-XX:+AlwaysPreTouch", "-XX:+UseNUMA",
                                      "-XX:+UseCompactObjectHeaders"))
    for holder, key, _ in option_lists(config, keys):
        holder[key] = maintenance_args(holder[key], managed)
    primary[primary_key].extend(additions)
    validate_gc(config, keys, (primary, primary_key), gc[0])


def validate_gc(config, keys, primary, selected):
    found = []
    for holder, key, _ in option_lists(config, keys):
        for arg in arguments(holder[key]):
            if SELECTOR.fullmatch(arg):
                if holder is not primary[0] or key != primary[1]:
                    raise ValueError(f"Collector selector remains in platform options ({arg}).")
                found.append(arg)
    if found != [selected]:
        raise ValueError(f"Expected exactly one collector ({selected}). Found {found}.")


def stock_conflicts(config, is_mac):
    keys = {"VMOptions", "JVMOptions"} if is_mac else {"vmArgs"}
    paths = [(path, {a for a in arguments(holder[key]) if SELECTOR.fullmatch(a) and a.startswith("-XX:+")})
             for holder, key, path in option_lists(config, keys)]
    for left, lhs in paths:
        for right, rhs in paths:
            if (is_mac or left == right[:len(left)] or right == left[:len(right)]) and len(lhs | rhs) > 1:
                return True
    return False

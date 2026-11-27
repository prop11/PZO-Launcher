#!/usr/bin/env python3
"""
PZO Release Packaging Utility
Packages client and server distributions and staging binaries for direct auto-updater downloads.
Supports local builds as well as multi-platform GitHub Actions CI/CD workflows.
"""

import os
import sys
import shutil
import zipfile
import hashlib
import importlib.util
import subprocess

# The V1.9.7 release natives for macOS/Linux (Windows ships none: WinApi makes those calls from Java). A rebuilt native
# must never ship by accident. When pzo_native.c really changes: rebuild in CI and take it with PZO_TAKE_CI_NATIVES=1,
# then update these hashes and UpdateChecker.NATIVE_VERSION.
PINNED_NATIVE_SHA256 = {
    "libpzo_native64.so": "f5333959f6843e3294d3328dc7b638ae074f56ef125ad4f8793da86c6743aa67",
    "libpzo_native64.dylib": "2f0575aa250d48870a607c89062c02c5e23ed92937dc7038a05b2fee8df25510",
}

def sha256_file(filepath):
    h = hashlib.sha256()
    with open(filepath, "rb") as f:
        while chunk := f.read(65536):
            h.update(chunk)
    return h.hexdigest()

# Direct lookups only: a recursive walk of the repo once found a stale native in dist/ before native/ and shipped it
def find_file(fname, search_roots):
    for root_dir in search_roots:
        direct = os.path.join(root_dir, fname)
        if os.path.isfile(direct):
            return direct
    return None

# PZO targets Java 17 (build_all.py --release 17); a class built for a newer Java than the game's JRE fails to load (issue #48)
MAX_CLASS_MAJOR = 61
# Built from src-ffm with --release 22 and only loaded by PZONative on Java 22+
NEWER_CLASS_ALLOWED = ("com/pzoptimizer/WinApi.class", "com/pzoptimizer/WinApi$")

def create_jar(bin_dir, manifest_file, out_jar):
    too_new = []
    for root, _, files in os.walk(bin_dir):
        for file in files:
            if file.endswith(".class"):
                with open(os.path.join(root, file), "rb") as f:
                    header = f.read(8)
                rel = os.path.relpath(os.path.join(root, file), bin_dir).replace("\\", "/")
                if len(header) == 8 and int.from_bytes(header[6:8], "big") > MAX_CLASS_MAJOR and not rel.startswith(NEWER_CLASS_ALLOWED):
                    too_new.append(rel)
    if too_new:
        sys.exit(f"[!] Refusing to build {os.path.basename(out_jar)}: classes newer than Java 17 (rebuild with build_all.py): {too_new}")
    with zipfile.ZipFile(out_jar, "w", zipfile.ZIP_DEFLATED) as zf:
        zf.write(manifest_file, "META-INF/MANIFEST.MF")
        for root, _, files in os.walk(bin_dir):
            for file in sorted(files):
                full_path = os.path.join(root, file)
                rel_path = os.path.relpath(full_path, bin_dir).replace("\\", "/")
                if rel_path != "META-INF/MANIFEST.MF":
                    zf.write(full_path, rel_path)
    print(f"[+] Built JAR: {os.path.basename(out_jar)} ({os.path.getsize(out_jar):,} bytes)")

def add_file_to_archive(zf, fp, arcname):
    zinfo = zipfile.ZipInfo.from_file(fp, arcname)
    if arcname.endswith((".sh", ".command", ".so", ".dylib")):
        zinfo.external_attr = 0o100755 << 16
    else:
        zinfo.external_attr = 0o100644 << 16
    with open(fp, "rb") as f:
        zf.writestr(zinfo, f.read(), compress_type=zipfile.ZIP_DEFLATED)


SETUP_SOURCES = ("pzo_setup.py", "pzo_install_core.py", "pzo_launcher_config.py")
WINDOWS_SETUP = "PZO-Setup.exe"
# Files pzo_setup.py reads next to itself
SETUP_ASSETS = ("app.ico", "pzo_mascot.png")


def build_windows_setup(root_dir, dist_dir):
    if sys.platform != "win32":
        sys.exit("Build the standalone Windows setup on Windows.")
    work = os.path.join(root_dir, "build", "installer")
    staging = os.path.join(work, "staging")
    icon = os.path.join(root_dir, "app.ico")
    data = [arg for name in SETUP_ASSETS for arg in ("--add-data", os.path.join(root_dir, name) + ":.")]
    subprocess.run([
        sys.executable, "-m", "PyInstaller", "--noconfirm", "--onefile", "--windowed",
        "--name", "PZO-Setup", "--icon", icon, *data,
        "--distpath", staging, "--workpath", work, "--specpath", work,
        os.path.join(root_dir, "pzo_setup.py"),
    ], cwd=root_dir, check=True)
    os.makedirs(dist_dir, exist_ok=True)
    os.replace(os.path.join(staging, WINDOWS_SETUP), os.path.join(dist_dir, WINDOWS_SETUP))


def package_client_installers(root_dir, dist_dir):
    executable = os.path.join(dist_dir, WINDOWS_SETUP)
    if sys.platform == "win32" and importlib.util.find_spec("PyInstaller"):
        built = os.path.getmtime(executable) if os.path.isfile(executable) else 0
        if any(os.path.getmtime(os.path.join(root_dir, name)) > built
               for name in (*SETUP_SOURCES, "package_release.py", *SETUP_ASSETS)):
            build_windows_setup(root_dir, dist_dir)
    for name in (*SETUP_SOURCES, *SETUP_ASSETS, "pzo_optimizer.sh", "pzo_optimizer.command"):
        shutil.copy2(os.path.join(root_dir, name), os.path.join(dist_dir, name))
    archives = {
        "PZO_Optimizer_macOS_Linux.zip": (
            "pzo_optimizer.sh", "pzo_optimizer.command", "pzo_downgrade.sh",
            "pzo_downgrade.command", *SETUP_SOURCES, *SETUP_ASSETS, "PZOptimEngine.jar",
            "README.md", "README_DOWNGRADE.md", "libpzo_native64.so", "libpzo_native64.dylib",
        ),
    }
    if os.path.isfile(executable):
        archives["PZO-Optimizer-Windows.zip"] = (WINDOWS_SETUP, "README.md")
    else:
        print("[!] Skipping Windows ZIP. Build or stage PZO-Setup.exe in dist first.")
    for archive, names in archives.items():
        destination = os.path.join(dist_dir, archive)
        with zipfile.ZipFile(destination, "w", zipfile.ZIP_DEFLATED) as zf:
            for name in names:
                roots = [dist_dir, root_dir] if name.endswith((".jar", ".exe")) else [root_dir, dist_dir]
                source = find_file(name, [*roots, os.path.join(root_dir, "native")])
                if source:
                    add_file_to_archive(zf, source, name)
        print(f"[+] Packaged: {archive} ({os.path.getsize(destination):,} bytes)")


def main():
    root_dir = os.path.dirname(os.path.abspath(__file__))
    dist_dir = os.path.join(root_dir, "dist")
    native_dir = os.path.join(root_dir, "native")
    bin_dir = os.path.join(root_dir, "bin")
    src_dir = os.path.join(root_dir, "src")
    os.makedirs(dist_dir, exist_ok=True)

    print("================================================================================")
    print(" Project Zomboid Optimiser (PZO) - Release Packager")
    print(f" Root: {root_dir}")
    print(f" Dist: {dist_dir}")
    print("================================================================================")

    # Build the client and server jars if missing or out of date
    client_mf = os.path.join(src_dir, "META-INF", "MANIFEST.MF")
    server_mf = os.path.join(src_dir, "META-INF", "MANIFEST_SERVER.MF")
    client_jar = os.path.join(dist_dir, "PZOptimEngine.jar")
    server_jar = os.path.join(dist_dir, "PZOServerEngine.jar")

    if os.path.isdir(bin_dir) and os.path.isfile(client_mf):
        create_jar(bin_dir, client_mf, client_jar)
    if os.path.isdir(bin_dir) and os.path.isfile(server_mf):
        create_jar(bin_dir, server_mf, server_jar)

    # Also copy them to the repo root
    if os.path.isfile(client_jar):
        shutil.copy2(client_jar, os.path.join(root_dir, "PZOptimEngine.jar"))
    if os.path.isfile(server_jar):
        shutil.copy2(server_jar, os.path.join(root_dir, "PZOServerEngine.jar"))

    # Natives come from native/ (CI builds land there), scripts and readmes from the repo root; never from dist/
    search_dirs = [native_dir, root_dir]

    for nname, want in PINNED_NATIVE_SHA256.items():
        npath = os.path.join(native_dir, nname)
        if os.path.isfile(npath) and sha256_file(npath) != want:
            sys.exit(f"[!] Refusing to package: native/{nname} is not the pinned binary (sha256 {sha256_file(npath)})")

    # Standalone binaries and scripts for dist/
    files_to_copy = [
        "libpzo_native64.so",
        "libpzo_native64.dylib",
        "downgrade.bat",
        "pzo_downgrade.sh",
        "pzo_downgrade.command",
        "README.md",
        "README_SERVER.md",
        "README_DOWNGRADE.md",
    ]

    for fname in files_to_copy:
        src = find_file(fname, search_dirs)
        dest = os.path.join(dist_dir, fname)
        if src:
            if os.path.abspath(src) != os.path.abspath(dest):
                shutil.copy2(src, dest)
            print(f"[+] Staged to dist/: {fname} ({os.path.getsize(dest):,} bytes)")
        else:
            print(f"[-] Optional asset not found (skipped): {fname}")

    package_client_installers(root_dir, dist_dir)

    downgrader_zip = os.path.join(dist_dir, "PZO-Downgrader.zip")
    with zipfile.ZipFile(downgrader_zip, "w", zipfile.ZIP_DEFLATED) as zf:
        for f in ["downgrade.bat", "pzo_downgrade.sh", "pzo_downgrade.command", "README_DOWNGRADE.md"]:
            fp = os.path.join(dist_dir, f)
            if os.path.isfile(fp):
                add_file_to_archive(zf, fp, f)
    print(f"[+] Packaged: PZO-Downgrader.zip ({os.path.getsize(downgrader_zip):,} bytes)")

    srv_win_zip = os.path.join(dist_dir, "PZO-Server-Windows.zip")
    with zipfile.ZipFile(srv_win_zip, "w", zipfile.ZIP_DEFLATED) as zf:
        for f in ["PZOServerEngine.jar", "README_SERVER.md"]:
            fp = os.path.join(dist_dir, f)
            if os.path.isfile(fp):
                add_file_to_archive(zf, fp, f)
    print(f"[+] Packaged: PZO-Server-Windows.zip ({os.path.getsize(srv_win_zip):,} bytes)")

    srv_linux_zip = os.path.join(dist_dir, "PZO-Server-Linux.zip")
    with zipfile.ZipFile(srv_linux_zip, "w", zipfile.ZIP_DEFLATED) as zf:
        for f in ["PZOServerEngine.jar", "README_SERVER.md", "libpzo_native64.so"]:
            fp = os.path.join(dist_dir, f)
            if os.path.isfile(fp):
                add_file_to_archive(zf, fp, f)
    print(f"[+] Packaged: PZO-Server-Linux.zip ({os.path.getsize(srv_linux_zip):,} bytes)")

    print("\n================================================================================")
    print(f"{'Artifact':<36} | {'Size':>12} | {'SHA-256 (First 16 chars)':<20}")
    print("--------------------------------------------------------------------------------")
    for item in sorted(os.listdir(dist_dir)):
        item_path = os.path.join(dist_dir, item)
        if os.path.isfile(item_path):
            size = f"{os.path.getsize(item_path):,} B"
            digest = sha256_file(item_path)[:16]
            print(f"{item:<36} | {size:>12} | {digest:<20}")
    print("================================================================================")

if __name__ == "__main__":
    if sys.argv[1:] == ["--build-windows-setup"]:
        root = os.path.dirname(os.path.abspath(__file__))
        build_windows_setup(root, os.path.join(root, "dist"))
    else:
        main()

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

def sha256_file(filepath):
    h = hashlib.sha256()
    with open(filepath, "rb") as f:
        while chunk := f.read(65536):
            h.update(chunk)
    return h.hexdigest()

# Direct lookups only: a recursive walk of the repo found a stale dist/pzo_native64.dll before native/ and shipped it
def find_file(fname, search_roots):
    for root_dir in search_roots:
        direct = os.path.join(root_dir, fname)
        if os.path.isfile(direct):
            return direct
    return None

# PZO targets Java 17 (build_all.py --release 17); a class built for a newer Java than the game's JRE fails to load (issue #48)
MAX_CLASS_MAJOR = 61

def create_jar(bin_dir, manifest_file, out_jar):
    too_new = []
    for root, _, files in os.walk(bin_dir):
        for file in files:
            if file.endswith(".class"):
                with open(os.path.join(root, file), "rb") as f:
                    header = f.read(8)
                if len(header) == 8 and int.from_bytes(header[6:8], "big") > MAX_CLASS_MAJOR:
                    too_new.append(os.path.relpath(os.path.join(root, file), bin_dir))
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

    # Standalone binaries and scripts for dist/
    files_to_copy = [
        "pzo_native64.dll",
        "libpzo_native64.so",
        "libpzo_native64.dylib",
        "install.bat",
        "downgrade.bat",
        "pzo_optimizer.sh",
        "pzo_downgrade.sh",
        "pzo_optimizer.command",
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

    def add_file_to_archive(zf, fp, arcname):
        zinfo = zipfile.ZipInfo.from_file(fp, arcname)
        if arcname.endswith((".sh", ".command", ".so", ".dylib")):
            zinfo.external_attr = 0o100755 << 16
        else:
            zinfo.external_attr = 0o100644 << 16
        with open(fp, "rb") as f:
            zf.writestr(zinfo, f.read(), compress_type=zipfile.ZIP_DEFLATED)

    win_zip = os.path.join(dist_dir, "PZO-Optimizer-Windows.zip")
    with zipfile.ZipFile(win_zip, "w", zipfile.ZIP_DEFLATED) as zf:
        for f in ["install.bat", "downgrade.bat", "PZOptimEngine.jar", "pzo_native64.dll", "README.md", "README_DOWNGRADE.md"]:
            fp = os.path.join(dist_dir, f)
            if os.path.isfile(fp):
                add_file_to_archive(zf, fp, f)
    print(f"[+] Packaged: PZO-Optimizer-Windows.zip ({os.path.getsize(win_zip):,} bytes)")

    mac_linux_zip = os.path.join(dist_dir, "PZO_Optimizer_macOS_Linux.zip")
    with zipfile.ZipFile(mac_linux_zip, "w", zipfile.ZIP_DEFLATED) as zf:
        for f in ["pzo_optimizer.sh", "pzo_downgrade.sh", "pzo_optimizer.command", "pzo_downgrade.command", "PZOptimEngine.jar", "README.md", "README_DOWNGRADE.md", "pzo_native64.dll", "libpzo_native64.so", "libpzo_native64.dylib"]:
            fp = os.path.join(dist_dir, f)
            if os.path.isfile(fp):
                add_file_to_archive(zf, fp, f)
    print(f"[+] Packaged: PZO_Optimizer_macOS_Linux.zip ({os.path.getsize(mac_linux_zip):,} bytes)")

    downgrader_zip = os.path.join(dist_dir, "PZO-Downgrader.zip")
    with zipfile.ZipFile(downgrader_zip, "w", zipfile.ZIP_DEFLATED) as zf:
        for f in ["downgrade.bat", "pzo_downgrade.sh", "pzo_downgrade.command", "README_DOWNGRADE.md"]:
            fp = os.path.join(dist_dir, f)
            if os.path.isfile(fp):
                add_file_to_archive(zf, fp, f)
    print(f"[+] Packaged: PZO-Downgrader.zip ({os.path.getsize(downgrader_zip):,} bytes)")

    srv_win_zip = os.path.join(dist_dir, "PZO-Server-Windows.zip")
    with zipfile.ZipFile(srv_win_zip, "w", zipfile.ZIP_DEFLATED) as zf:
        for f in ["PZOServerEngine.jar", "README_SERVER.md", "pzo_native64.dll"]:
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
    main()

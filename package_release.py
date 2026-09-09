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

def find_file(fname, search_roots):
    for root_dir in search_roots:
        if not os.path.exists(root_dir):
            continue
        direct = os.path.join(root_dir, fname)
        if os.path.isfile(direct):
            return direct
        for current, _, files in os.walk(root_dir):
            if fname in files:
                return os.path.join(current, fname)
    return None

def create_jar(bin_dir, manifest_file, out_jar):
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

    client_mf = os.path.join(src_dir, "META-INF", "MANIFEST.MF")
    server_mf = os.path.join(src_dir, "META-INF", "MANIFEST_SERVER.MF")
    client_jar = os.path.join(dist_dir, "PZOptimEngine.jar")
    server_jar = os.path.join(dist_dir, "PZOServerEngine.jar")

    if os.path.isdir(bin_dir) and os.path.isfile(client_mf):
        create_jar(bin_dir, client_mf, client_jar)
    if os.path.isdir(bin_dir) and os.path.isfile(server_mf):
        create_jar(bin_dir, server_mf, server_jar)

    if os.path.isfile(client_jar):
        shutil.copy2(client_jar, os.path.join(root_dir, "PZOptimEngine.jar"))
    if os.path.isfile(server_jar):
        shutil.copy2(server_jar, os.path.join(root_dir, "PZOServerEngine.jar"))

    search_dirs = [dist_dir, native_dir, root_dir]

    files_to_copy = [
        "pzo_native64.dll",
        "libpzo_native64.so",
        "libpzo_native64.dylib",
        "install.bat",
        "pzo_optimizer.sh",
        "pzo_optimizer.command",
        "README.md",
        "README_SERVER.md",
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

    win_zip = os.path.join(dist_dir, "PZO-Optimizer-Windows.zip")
    with zipfile.ZipFile(win_zip, "w", zipfile.ZIP_DEFLATED) as zf:
        for f in ["install.bat", "PZOptimEngine.jar", "pzo_native64.dll", "README.md"]:
            fp = os.path.join(dist_dir, f)
            if os.path.isfile(fp):
                zf.write(fp, f)
    print(f"[+] Packaged: PZO-Optimizer-Windows.zip ({os.path.getsize(win_zip):,} bytes)")

    mac_linux_zip = os.path.join(dist_dir, "PZO_Optimizer_macOS_Linux.zip")
    with zipfile.ZipFile(mac_linux_zip, "w", zipfile.ZIP_DEFLATED) as zf:
        for f in ["pzo_optimizer.sh", "pzo_optimizer.command", "PZOptimEngine.jar", "README.md", "libpzo_native64.so", "libpzo_native64.dylib"]:
            fp = os.path.join(dist_dir, f)
            if os.path.isfile(fp):
                zf.write(fp, f)
    print(f"[+] Packaged: PZO_Optimizer_macOS_Linux.zip ({os.path.getsize(mac_linux_zip):,} bytes)")

    srv_win_zip = os.path.join(dist_dir, "PZO-Server-Windows.zip")
    with zipfile.ZipFile(srv_win_zip, "w", zipfile.ZIP_DEFLATED) as zf:
        for f in ["PZOServerEngine.jar", "README_SERVER.md", "pzo_native64.dll"]:
            fp = os.path.join(dist_dir, f)
            if os.path.isfile(fp):
                zf.write(fp, f)
    print(f"[+] Packaged: PZO-Server-Windows.zip ({os.path.getsize(srv_win_zip):,} bytes)")

    srv_linux_zip = os.path.join(dist_dir, "PZO-Server-Linux.zip")
    with zipfile.ZipFile(srv_linux_zip, "w", zipfile.ZIP_DEFLATED) as zf:
        for f in ["PZOServerEngine.jar", "README_SERVER.md", "libpzo_native64.so"]:
            fp = os.path.join(dist_dir, f)
            if os.path.isfile(fp):
                zf.write(fp, f)
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

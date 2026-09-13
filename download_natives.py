#!/usr/bin/env python3
"""
PZO Native Binaries Downloader
Downloads compiled Linux (libpzo_native64.so) and macOS (libpzo_native64.dylib)
binaries from GitHub Actions artifacts into native/ and dist/, then updates release ZIPs.
Can also trigger the build-natives workflow on GitHub Actions.
"""

import os
import sys
import time
import json
import zipfile
import io
import subprocess
import urllib.request
import urllib.error

REPO_OWNER = "prop11"
REPO_NAME = "PZO-Launcher"
WORKFLOW_FILE = "build-natives.yml"

class NoAuthRedirect(urllib.request.HTTPRedirectHandler):
    """Prevents re-sending the GitHub Bearer token to Azure Blob storage on redirect."""
    def redirect_request(self, req, fp, code, msg, headers, newurl):
        new_req = super().redirect_request(req, fp, code, msg, headers, newurl)
        if new_req and "Authorization" in new_req.headers:
            del new_req.headers["Authorization"]
        return new_req

def get_github_token():
    token = os.environ.get("GITHUB_TOKEN") or os.environ.get("GH_TOKEN")
    if token:
        return token.strip()
    try:
        p = subprocess.Popen(
            ["git", "credential", "fill"],
            stdin=subprocess.PIPE,
            stdout=subprocess.PIPE,
            stderr=subprocess.PIPE,
            text=True
        )
        out, _ = p.communicate("protocol=https\nhost=github.com\n")
        for line in out.splitlines():
            if line.startswith("password="):
                return line.split("=", 1)[1].strip()
    except Exception:
        pass
    return None

def api_request(path, token, data=None, method=None):
    url = f"https://api.github.com/repos/{REPO_OWNER}/{REPO_NAME}/{path}" if not path.startswith("https://") else path
    headers = {
        "User-Agent": "PZO-Native-Downloader",
        "Accept": "application/vnd.github+json"
    }
    if token:
        headers["Authorization"] = f"Bearer {token}"

    encoded_data = json.dumps(data).encode("utf-8") if data is not None else None
    req = urllib.request.Request(url, data=encoded_data, headers=headers, method=method)

    opener = urllib.request.build_opener(NoAuthRedirect)
    with opener.open(req) as resp:
        content_type = resp.headers.get("Content-Type", "")
        if "application/json" in content_type:
            return json.loads(resp.read().decode("utf-8"))
        return resp.read()

def trigger_workflow(token, branch="main"):
    print(f"[*] Triggering GitHub Actions workflow '{WORKFLOW_FILE}' on branch '{branch}'...")
    payload = {"ref": branch}
    try:
        api_request(f"actions/workflows/{WORKFLOW_FILE}/dispatches", token, data=payload, method="POST")
        print("[+] Workflow dispatch triggered successfully!")
        return True
    except urllib.error.HTTPError as e:
        print(f"[!] Failed to trigger workflow: HTTP {e.code} - {e.reason}")
        return False

def wait_for_completion(token, poll_interval=10, timeout=600):
    print("[*] Waiting for workflow execution to complete...")
    start_time = time.time()
    last_status = None

    time.sleep(5)

    while time.time() - start_time < timeout:
        try:
            data = api_request("actions/runs?per_page=5", token)
            runs = data.get("workflow_runs", [])
            if runs:
                latest = runs[0]
                status = latest.get("status")
                conclusion = latest.get("conclusion")
                run_id = latest.get("id")

                if status != last_status:
                    print(f"[*] Run #{run_id}: status={status}, conclusion={conclusion}")
                    last_status = status

                if status == "completed":
                    if conclusion == "success":
                        print(f"[+] Run #{run_id} completed successfully!")
                        return run_id
                    else:
                        print(f"[!] Run #{run_id} ended with conclusion: {conclusion}")
                        return None
        except Exception as e:
            print(f"[-] Poll notice: {e}")

        time.sleep(poll_interval)

    print("[!] Timed out waiting for workflow run.")
    return None

def download_artifacts_for_run(token, run_id, root_dir):
    native_dir = os.path.join(root_dir, "native")
    dist_dir = os.path.join(root_dir, "dist")
    os.makedirs(dist_dir, exist_ok=True)

    print(f"[*] Fetching artifacts for workflow run #{run_id}...")
    artifacts_data = api_request(f"actions/runs/{run_id}/artifacts", token)
    artifacts = artifacts_data.get("artifacts", [])

    if not artifacts:
        print("[!] No artifacts found for this run.")
        return False

    downloaded = 0
    for art in artifacts:
        name = art.get("name")
        art_id = art.get("id")
        print(f"[*] Downloading artifact '{name}' (ID: {art_id})...")

        raw_zip = api_request(f"actions/artifacts/{art_id}/zip", token)
        with zipfile.ZipFile(io.BytesIO(raw_zip)) as zf:
            for item in zf.namelist():
                extracted_bytes = zf.read(item)
                target_filename = os.path.basename(item)

                native_path = os.path.join(native_dir, target_filename)
                with open(native_path, "wb") as f:
                    f.write(extracted_bytes)
                print(f"[+] Saved: native/{target_filename} ({len(extracted_bytes):,} bytes)")

                dist_path = os.path.join(dist_dir, target_filename)
                with open(dist_path, "wb") as f:
                    f.write(extracted_bytes)
                print(f"[+] Saved: dist/{target_filename} ({len(extracted_bytes):,} bytes)")

                downloaded += 1

    return downloaded > 0

def find_latest_successful_run(token):
    print("[*] Locating latest successful build run...")
    data = api_request("actions/runs?status=completed&per_page=10", token)
    for run in data.get("workflow_runs", []):
        if run.get("conclusion") == "success":
            return run.get("id")
    return None

def main():
    root_dir = os.path.dirname(os.path.abspath(__file__))
    token = get_github_token()

    if not token:
        print("[!] Error: No GitHub token found in environment or git credential manager.")
        sys.exit(1)

    trigger = "--trigger" in sys.argv
    wait = "--wait" in sys.argv or trigger

    run_id = None
    if trigger:
        if trigger_workflow(token):
            run_id = wait_for_completion(token)
        else:
            sys.exit(1)
    elif wait:
        run_id = wait_for_completion(token)
    else:
        run_id = find_latest_successful_run(token)

    if not run_id:
        print("[!] Could not determine a successful workflow run ID.")
        sys.exit(1)

    success = download_artifacts_for_run(token, run_id, root_dir)
    if not success:
        print("[!] Failed to download native artifacts.")
        sys.exit(1)

    package_script = os.path.join(root_dir, "package_release.py")
    if os.path.isfile(package_script):
        print("\n[*] Updating distribution packages in dist/...")
        subprocess.run([sys.executable, package_script], cwd=root_dir)

    print("\n[+] Native binary sync complete!")

    check_binary_versions(root_dir)

def check_binary_versions(root_dir):
    import re
    native_dir = os.path.join(root_dir, "native")
    print("\n================================================================================")
    print(" Native Binary Versions in native/")
    print("================================================================================")
    for fname in ["pzo_native64.dll", "libpzo_native64.so", "libpzo_native64.dylib"]:
        p = os.path.join(native_dir, fname)
        if os.path.isfile(p):
            with open(p, "rb") as f:
                data = f.read()
            matches = re.findall(rb"0\.9\.[0-9]+(?:\.[0-9]+)?", data)
            decoded = [m.decode("ascii") for m in set(matches)]
            ver_str = ", ".join(decoded) if decoded else "Unknown"
            print(f" {fname:<25} | Size: {os.path.getsize(p):>8,} bytes | Embedded: {ver_str}")
        else:
            print(f" {fname:<25} | Missing")
    print("================================================================================\n")

if __name__ == "__main__":
    main()

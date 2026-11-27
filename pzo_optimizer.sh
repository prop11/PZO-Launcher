#!/bin/bash
set -eu
script_dir="$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")" && pwd)"
if ! command -v python3 >/dev/null 2>&1; then
    echo "PZO Setup requires Python 3.9 or newer." >&2
    exit 1
fi
if [ ! -t 0 ] && [ "$#" -eq 0 ] && [ -n "${DISPLAY:-}${WAYLAND_DISPLAY:-}" ]; then
    if ! python3 -c 'import tkinter' >/dev/null 2>&1; then
        if command -v konsole >/dev/null 2>&1; then
            exec konsole -e bash "$script_dir/pzo_optimizer.sh" --cli
        elif command -v xterm >/dev/null 2>&1; then
            exec xterm -e bash "$script_dir/pzo_optimizer.sh" --cli
        fi
    fi
fi
exec python3 "$script_dir/pzo_setup.py" "$@"

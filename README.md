# PZO Launcher

A lightweight launcher and engine optimizer for Project Zomboid (Build 42 & 41).

## Features
- Hardware-adaptive JVM memory heap allocation (`-Xmx` scaling)
- Low-latency G1 Garbage Collection tuning
- Memory pre-allocation (`-XX:+AlwaysPreTouch`)
- CPU affinity management (Intel P-Core / AMD 3D V-Cache prioritization)
- Windows high-priority process scheduling
- Optional Java runtime agent injection (`PZOptimEngine.jar`)
- One-click cache cleaner and configuration backup/restore

## Requirements
- Python 3.8+
- Project Zomboid (Steam 64-bit)

## Usage
Run `run.bat` or execute:
```bash
python launcher.py
```

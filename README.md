# Project Zomboid Config & File Optimizer

A lightweight, standalone optimization utility and engine injector for Project Zomboid (Build 42 & 41).

## Features
- Zero dependencies (Native Windows 64-bit executable)
- Automatic hardware and RAM detection
- Dedicated JVM heap memory scaling (`-Xmx` 4GB - 16GB)
- Low-latency G1 Garbage Collection tuning
- Memory heap pre-allocation (`-XX:+AlwaysPreTouch`)
- Java runtime agent injection (`PZOptimEngine.jar`)
- One-click cache cleaner and configuration backup/restore

## Quick Start
1. Download **`PZO_Optimizer.exe`** and the **`dist`** folder from Releases.
2. Run `PZO_Optimizer.exe`.
3. Select your desired RAM allocation and click **Optimize Game**.
4. Launch Project Zomboid normally through Steam.

## Building from Source
To compile `PZO_Optimizer.exe` using the Windows built-in C# compiler:
```cmd
C:\Windows\Microsoft.NET\Framework64\v4.0.30319\csc.exe /target:winexe /optimize+ /platform:x64 /out:PZO_Optimizer.exe /r:System.Windows.Forms.dll /r:System.Drawing.dll /r:System.dll Program.cs
```

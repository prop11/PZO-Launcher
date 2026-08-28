# Project Zomboid Config & Engine Optimizer

A lightweight, zero-dependency standalone optimization utility and engine wrapper for Project Zomboid (Build 42 & 41).

## Features
- Zero dependencies (Single standalone Windows 64-bit executable)
- Automatic hardware & system RAM detection
- Dedicated JVM heap memory scaling (-Xmx 4GB - 16GB)
- Low-latency G1 Garbage Collection tuning (-XX:+UseG1GC)
- Mid-game allocation hitch prevention (-XX:+AlwaysPreTouch)
- Engine Classpath Wrapper (PZOEntrypoint & PZOptimEngine.jar)
- Native FastMath vector acceleration & async texture pre-loading
- Full in-game synchronization with the Project Zomboid Optimiser Workshop mod
- One-click cache cleaner and automatic configuration backup/restore

## Quick Start
1. Download **PZO_Optimizer.exe** from [Releases](https://github.com/prop11/PZO-Launcher/releases).
2. Run PZO_Optimizer.exe.
3. Select your desired RAM allocation (8GB recommended for 16GB+ systems) and click **Optimize Game**.
4. Launch Project Zomboid normally through Steam.

*Note: You only need to run the optimizer once. All settings persist across game launches.*

## Workshop Mod Integration
When combined with the **Project Zomboid Optimiser** Steam Workshop mod, running this optimizer unlocks the dedicated **[+] JVM Engine (Tab 7)** inside the in-game F10 Control Center:
- Zero-Stutter Background GC Mode
- Deep RAM Chunk Cache (Zero Disk I/O Lag)
- Asynchronous 3D Model Compilation
- Distant Horde Spatial Hibernation
- Live JVM Heap & Telemetry Monitoring

## Building from Source

### 1. Compile Java Engine Wrapper
`cmd
javac --release 17 -d bin src\com\pzoptimizer\*.java
jar -cfm dist\PZOptimEngine.jar src\META-INF\MANIFEST.MF -C bin .
`

### 2. Compile Standalone C# Executable (with embedded JAR)
`cmd
C:\Windows\Microsoft.NET\Framework64\v4.0.30319\csc.exe /target:winexe /optimize+ /platform:x64 /resource:dist\PZOptimEngine.jar,PZOptimEngine.jar /out:PZO_Optimizer.exe /r:System.Windows.Forms.dll /r:System.Drawing.dll /r:System.dll Program.cs
`

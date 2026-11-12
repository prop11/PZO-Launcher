@echo off
setlocal
set "PZO_INSTALLER_DIR=%~dp0"
title Project Zomboid Build 42 - Config ^& Engine Optimizer Installer

where powershell >nul 2>nul
if %errorlevel% neq 0 (
    echo [ERROR] PowerShell was not found on your system.
    echo Please install Windows PowerShell to run this installer.
    pause
    exit /b 1
)

powershell -NoProfile -ExecutionPolicy Bypass -Command "$f = Get-Content -LiteralPath '%~f0'; $start = 0; for ($i=0; $i -lt $f.Length; $i++) { if ($f[$i] -eq '# __START_POWERSHELL__') { $start = $i + 1; break } }; $ps = ($f[$start..($f.Length - 1)]) -join [Environment]::NewLine; [ScriptBlock]::Create($ps).Invoke()"
pause
exit /b %errorlevel%

# __START_POWERSHELL__
# PZO installer and uninstaller

Write-Host "=================================================================" -ForegroundColor Cyan
Write-Host " Project Zomboid Build 42 Engine Optimizer (v2.0.1)" -ForegroundColor Cyan
Write-Host " Native Configuration & Engine Agent Installer" -ForegroundColor Cyan
Write-Host "=================================================================" -ForegroundColor Cyan

$ScriptDir = if ($env:PZO_INSTALLER_DIR) { $env:PZO_INSTALLER_DIR.TrimEnd('\') } else { $PWD.Path }
$JarFileName       = "PZOptimEngine.jar"
$NativeDllName     = "pzo_native64.dll"
$TargetFileName    = "ProjectZomboid64.json"
$LaunchBatName     = "ProjectZomboid64_PZO.bat"
$BackupFolder      = "Installer_Backups"
$ZomboidLuaDir     = [System.IO.Path]::Combine($HOME, "Zomboid\Lua")
$PzoStatusFile     = [System.IO.Path]::Combine($ZomboidLuaDir, "pzo_status.json")
$ZomboidModsDir    = [System.IO.Path]::Combine($HOME, "Zomboid\mods")

# Guard against game running
$runningPZ = Get-Process -Name "ProjectZomboid64", "ProjectZomboid32" -ErrorAction SilentlyContinue
if ($runningPZ) {
    Write-Host "`n[!] Warning: Project Zomboid is currently running." -ForegroundColor Yellow
    Write-Host "    Please close Project Zomboid to prevent file permission locks." -ForegroundColor Yellow
    Write-Host "    Press Enter once the game is closed to continue..." -ForegroundColor Gray
    Read-Host
}

if (-not (Test-Path -LiteralPath $ZomboidLuaDir -ErrorAction SilentlyContinue)) {
    New-Item -ItemType Directory -Path $ZomboidLuaDir -Force | Out-Null
}
if (-not (Test-Path -LiteralPath $ZomboidModsDir -ErrorAction SilentlyContinue)) {
    New-Item -ItemType Directory -Path $ZomboidModsDir -Force | Out-Null
}

# JSON templates (written without a BOM)
$Json5OrLess = @"
{
    "mainClass": "com/pzoptimizer/PZOEntrypoint",
    "classpath": [
        ".",
        "PZOptimEngine.jar",
        "projectzomboid.jar"
    ],
    "vmArgs": [
        "-agentlib:pzo_native64",
        "--enable-native-access=ALL-UNNAMED",
        "--add-exports=java.base/jdk.internal.misc=ALL-UNNAMED",
        "-Xms2048m",
        "-Xmx4096m",
        "-Dzomboid.steam=1",
        "-Dzomboid.znetlog=1",
        "-Djava.library.path=win64/;.;natives/",
        "-XX:-CreateCoredumpOnCrash",
        "-XX:-OmitStackTraceInFastThrow",
        "-XX:+PerfDisableSharedMem",
        "-XX:+UnlockExperimentalVMOptions",
        "-XX:+UseSuperWord",
        "-XX:MaxInlineLevel=15",
        "-XX:InlineSmallCode=2500",
        "-XX:+UseNUMA",
        "-XX:+AlwaysPreTouch"
    ],
    "windows": {
        "6.1": {
            "vmArgs": [
                "-XX:+UseG1GC"
            ]
        },
        "10.0.17134": {
            "vmArgs": [
                "-XX:+UseG1GC",
                "-XX:InitiatingHeapOccupancyPercent=45",
                "-XX:G1ReservePercent=15"
            ]
        }
    }
}
"@

$Json6To12 = @"
{
    "mainClass": "com/pzoptimizer/PZOEntrypoint",
    "classpath": [
        ".",
        "PZOptimEngine.jar",
        "projectzomboid.jar"
    ],
    "vmArgs": [
        "-agentlib:pzo_native64",
        "--enable-native-access=ALL-UNNAMED",
        "--add-exports=java.base/jdk.internal.misc=ALL-UNNAMED",
        "-Xms3072m",
        "-Xmx6144m",
        "-Dzomboid.steam=1",
        "-Dzomboid.znetlog=1",
        "-Djava.library.path=win64/;.;natives/",
        "-XX:-CreateCoredumpOnCrash",
        "-XX:-OmitStackTraceInFastThrow",
        "-XX:+PerfDisableSharedMem",
        "-XX:+UnlockExperimentalVMOptions",
        "-XX:+UseSuperWord",
        "-XX:MaxInlineLevel=15",
        "-XX:InlineSmallCode=2500",
        "-XX:+UseNUMA",
        "-XX:+AlwaysPreTouch"
    ],
    "windows": {
        "6.1": {
            "vmArgs": [
                "-XX:+UseG1GC"
            ]
        },
        "10.0.17134": {
            "vmArgs": [
                "-XX:+UseG1GC",
                "-XX:InitiatingHeapOccupancyPercent=45",
                "-XX:G1ReservePercent=15",
                "-XX:+UseStringDeduplication"
            ]
        }
    }
}
"@

$Json13To20_G1GC = @"
{
    "mainClass": "com/pzoptimizer/PZOEntrypoint",
    "classpath": [
        ".",
        "PZOptimEngine.jar",
        "projectzomboid.jar"
    ],
    "vmArgs": [
        "-agentlib:pzo_native64",
        "--enable-native-access=ALL-UNNAMED",
        "--add-exports=java.base/jdk.internal.misc=ALL-UNNAMED",
        "-Xms4096m",
        "-Xmx8192m",
        "-Dzomboid.steam=1",
        "-Dzomboid.znetlog=1",
        "-Djava.library.path=win64/;.;natives/",
        "-XX:-CreateCoredumpOnCrash",
        "-XX:-OmitStackTraceInFastThrow",
        "-XX:+PerfDisableSharedMem",
        "-XX:+UnlockExperimentalVMOptions",
        "-XX:+UseSuperWord",
        "-XX:MaxInlineLevel=15",
        "-XX:InlineSmallCode=2500",
        "-XX:+UseNUMA",
        "-XX:+AlwaysPreTouch"
    ],
    "windows": {
        "6.1": {
            "vmArgs": [
                "-XX:+UseG1GC"
            ]
        },
        "10.0.17134": {
            "vmArgs": [
                "-XX:+UseG1GC",
                "-XX:InitiatingHeapOccupancyPercent=45",
                "-XX:G1ReservePercent=15",
                "-XX:+UseStringDeduplication"
            ]
        }
    }
}
"@

$Json13To20_ZGC = @"
{
    "mainClass": "com/pzoptimizer/PZOEntrypoint",
    "classpath": [
        ".",
        "PZOptimEngine.jar",
        "projectzomboid.jar"
    ],
    "vmArgs": [
        "-agentlib:pzo_native64",
        "--enable-native-access=ALL-UNNAMED",
        "--add-exports=java.base/jdk.internal.misc=ALL-UNNAMED",
        "-Xms4096m",
        "-Xmx8192m",
        "-Dzomboid.steam=1",
        "-Dzomboid.znetlog=1",
        "-Djava.library.path=win64/;.;natives/",
        "-XX:-CreateCoredumpOnCrash",
        "-XX:-OmitStackTraceInFastThrow",
        "-XX:+PerfDisableSharedMem",
        "-XX:+UnlockExperimentalVMOptions",
        "-XX:+UseSuperWord",
        "-XX:MaxInlineLevel=15",
        "-XX:InlineSmallCode=2500",
        "-XX:+UseNUMA",
        "-XX:+AlwaysPreTouch"
    ],
    "windows": {
        "6.1": {
            "vmArgs": [
                "-XX:+UseG1GC"
            ]
        },
        "10.0.17134": {
            "vmArgs": [
                "-XX:+UseZGC",
                "-XX:ZAllocationSpikeTolerance=5",
                "-XX:+UseStringDeduplication"
            ]
        }
    }
}
"@

$Json13To20 = $Json13To20_G1GC

$JsonAbove20 = @"
{
    "mainClass": "com/pzoptimizer/PZOEntrypoint",
    "classpath": [
        ".",
        "PZOptimEngine.jar",
        "projectzomboid.jar"
    ],
    "vmArgs": [
        "-agentlib:pzo_native64",
        "--enable-native-access=ALL-UNNAMED",
        "--add-exports=java.base/jdk.internal.misc=ALL-UNNAMED",
        "-Xms8192m",
        "-Xmx16384m",
        "-Dzomboid.steam=1",
        "-Dzomboid.znetlog=1",
        "-Djava.library.path=win64/;.;natives/",
        "-XX:-CreateCoredumpOnCrash",
        "-XX:-OmitStackTraceInFastThrow",
        "-XX:+PerfDisableSharedMem",
        "-XX:+UnlockExperimentalVMOptions",
        "-XX:+UseSuperWord",
        "-XX:MaxInlineLevel=15",
        "-XX:InlineSmallCode=2500",
        "-XX:+UseZGC",
        "-XX:ZAllocationSpikeTolerance=5",
        "-XX:+UseStringDeduplication",
        "-XX:+UseNUMA",
        "-XX:+AlwaysPreTouch"
    ]
}
"@

function Write-JsonNoBOM($filePath, $content) {
    $utf8NoBom = New-Object System.Text.UTF8Encoding($false)
    [System.IO.File]::WriteAllText($filePath, $content, $utf8NoBom)
}

function Download-PZOGitHubJar($targetFile) {
    $downloadUrl = "https://github.com/prop11/PZO-Launcher/releases/latest/download/PZOptimEngine.jar"
    Write-Host "`n[*] Downloading latest PZOptimEngine.jar from GitHub Releases..." -ForegroundColor Cyan
    Write-Host "    URL: $downloadUrl" -ForegroundColor Gray
    try {
        [Net.ServicePointManager]::SecurityProtocol = [Net.SecurityProtocolType]::Tls12 -bor [Net.SecurityProtocolType]::Tls13
        $webClient = New-Object System.Net.WebClient
        $webClient.Headers.Add("User-Agent", "PZO-Installer")
        $webClient.DownloadFile($downloadUrl, $targetFile)
        if (Test-Path -LiteralPath $targetFile -ErrorAction SilentlyContinue) {
            $sizeKB = [Math]::Round((Get-Item -LiteralPath $targetFile).Length / 1KB, 1)
            Write-Host "    [SUCCESS] Downloaded PZOptimEngine.jar ($sizeKB KB)" -ForegroundColor Green
            return $true
        }
    } catch {
        Write-Host "    [WARNING] Download error: $($_.Exception.Message)" -ForegroundColor Yellow
    }
    return $false
}

function Download-PZOGitHubDll($targetFile) {
    # Pinned to the release Smart App Control accepts, same version as UpdateChecker.NATIVE_VERSION
    $downloadUrl = "https://github.com/prop11/PZO-Launcher/releases/download/V1.9.7/pzo_native64.dll"
    Write-Host "`n[*] Downloading pzo_native64.dll from GitHub Releases..." -ForegroundColor Cyan
    Write-Host "    URL: $downloadUrl" -ForegroundColor Gray
    try {
        [Net.ServicePointManager]::SecurityProtocol = [Net.SecurityProtocolType]::Tls12 -bor [Net.SecurityProtocolType]::Tls13
        $webClient = New-Object System.Net.WebClient
        $webClient.Headers.Add("User-Agent", "PZO-Installer")
        $webClient.DownloadFile($downloadUrl, $targetFile)
        if (Test-Path -LiteralPath $targetFile -ErrorAction SilentlyContinue) {
            $sizeKB = [Math]::Round((Get-Item -LiteralPath $targetFile).Length / 1KB, 1)
            Write-Host "    [SUCCESS] Downloaded pzo_native64.dll ($sizeKB KB)" -ForegroundColor Green
            return $true
        }
    } catch {
        Write-Host "    [WARNING] Download error: $($_.Exception.Message)" -ForegroundColor Yellow
    }
    return $false
}

function Get-PZInstallPath {
    Write-Host "`nSearching for Project Zomboid installation..." -ForegroundColor Cyan

    $regPath = "HKCU:\Software\Valve\Steam"
    $steamPath = $null
    if (Test-Path $regPath) {
        $steamPath = (Get-ItemProperty -Path $regPath -Name "SteamPath" -ErrorAction SilentlyContinue).SteamPath
    }

    $candidateLibraries = [System.Collections.Generic.List[string]]::new()
    if ($steamPath) {
        $candidateLibraries.Add([System.IO.Path]::Combine($steamPath, "steamapps"))
        $vdfPath = [System.IO.Path]::Combine($steamPath, "steamapps\libraryfolders.vdf")
        if (Test-Path -LiteralPath $vdfPath -ErrorAction SilentlyContinue) {
            $vdfContent = Get-Content -LiteralPath $vdfPath -ErrorAction SilentlyContinue
            if ($vdfContent) {
                foreach ($line in $vdfContent) {
                    if ($line -match '"path"\s+"([^"]+)"') {
                        $rawLib = $matches[1] -replace '\\\\', '\'
                        $drive = [System.IO.Path]::GetPathRoot($rawLib)
                        if ($drive -and (Test-Path -LiteralPath $drive -ErrorAction SilentlyContinue)) {
                            $appsFolder = [System.IO.Path]::Combine($rawLib, "steamapps")
                            if (-not $candidateLibraries.Contains($appsFolder)) {
                                $candidateLibraries.Add($appsFolder)
                            }
                        }
                    }
                }
            }
        }
    }

    $drives = [System.IO.DriveInfo]::GetDrives() | Where-Object { $_.IsReady } | ForEach-Object { $_.Name }
    foreach ($drv in $drives) {
        $candidateLibraries.Add([System.IO.Path]::Combine($drv, "SteamLibrary\steamapps"))
        $candidateLibraries.Add([System.IO.Path]::Combine($drv, "Program Files (x86)\Steam\steamapps"))
        $candidateLibraries.Add([System.IO.Path]::Combine($drv, "Program Files\Steam\steamapps"))
    }

    foreach ($lib in $candidateLibraries) {
        if (-not [string]::IsNullOrWhiteSpace($lib)) {
            $driveRoot = [System.IO.Path]::GetPathRoot($lib)
            if ($driveRoot -and (Test-Path -LiteralPath $driveRoot -ErrorAction SilentlyContinue)) {
                $pzPath = [System.IO.Path]::Combine($lib, "common\ProjectZomboid")
                $exePath = [System.IO.Path]::Combine($pzPath, "ProjectZomboid64.exe")
                if (Test-Path -LiteralPath $exePath -ErrorAction SilentlyContinue) {
                    return $pzPath
                }
            }
        }
    }

    $gogPath = "C:\GOG Games\Project Zomboid"
    if (Test-Path -LiteralPath ([System.IO.Path]::Combine($gogPath, "ProjectZomboid64.exe")) -ErrorAction SilentlyContinue) {
        return $gogPath
    }

    return $null
}

$InstallPath = Get-PZInstallPath

if (-not $InstallPath) {
    Write-Host "Auto-detection could not locate Project Zomboid." -ForegroundColor Yellow
    Write-Host "Please select your 'ProjectZomboid' folder..." -ForegroundColor Yellow
    Add-Type -AssemblyName System.Windows.Forms
    $FolderBrowser = New-Object System.Windows.Forms.FolderBrowserDialog
    $FolderBrowser.Description = "Select Project Zomboid Installation Directory"
    $FolderBrowser.ShowNewFolderButton = $false

    if ($FolderBrowser.ShowDialog() -eq [System.Windows.Forms.DialogResult]::OK) {
        $InstallPath = $FolderBrowser.SelectedPath
    } else {
        Write-Host "Installation cancelled by user." -ForegroundColor Red
        return
    }
}

Write-Host "Project Zomboid directory: $InstallPath" -ForegroundColor Green

$InstalledJarPath = [System.IO.Path]::Combine($InstallPath, $JarFileName)
$InstalledDllPath = [System.IO.Path]::Combine($InstallPath, $NativeDllName)
$TargetFilePath   = [System.IO.Path]::Combine($InstallPath, $TargetFileName)
$LaunchBatPath    = [System.IO.Path]::Combine($InstallPath, $LaunchBatName)
$BackupDir        = [System.IO.Path]::Combine($InstallPath, $BackupFolder)

# Locate Source Jar
$candidateJarPaths = [System.Collections.Generic.List[string]]::new()
if ($ScriptDir) {
    $candidateJarPaths.Add([System.IO.Path]::Combine($ScriptDir, $JarFileName))
    $candidateJarPaths.Add([System.IO.Path]::Combine($ScriptDir, "dist\$JarFileName"))
}
if ($PWD -and $PWD.Path) {
    $candidateJarPaths.Add([System.IO.Path]::Combine($PWD.Path, $JarFileName))
    $candidateJarPaths.Add([System.IO.Path]::Combine($PWD.Path, "dist\$JarFileName"))
}

$SourceJar = $null
foreach ($cp in $candidateJarPaths) {
    if ($cp -and (Test-Path -LiteralPath $cp -ErrorAction SilentlyContinue)) {
        $SourceJar = $cp
        break
    }
}

if ($SourceJar) {
    Write-Host "Using local engine package: $SourceJar" -ForegroundColor Gray
} else {
    Write-Host "`n[Notice] '$JarFileName' not found locally next to install.bat." -ForegroundColor Yellow
    $downloadTarget = [System.IO.Path]::Combine($ScriptDir, $JarFileName)
    if (Download-PZOGitHubJar $downloadTarget) {
        $SourceJar = $downloadTarget
    }
}

# Locate Source Native DLL (pzo_native64.dll)
$candidateDllPaths = [System.Collections.Generic.List[string]]::new()
if ($ScriptDir) {
    $candidateDllPaths.Add([System.IO.Path]::Combine($ScriptDir, $NativeDllName))
    $candidateDllPaths.Add([System.IO.Path]::Combine($ScriptDir, "dist\$NativeDllName"))
    $candidateDllPaths.Add([System.IO.Path]::Combine($ScriptDir, "native\$NativeDllName"))
}
if ($PWD -and $PWD.Path) {
    $candidateDllPaths.Add([System.IO.Path]::Combine($PWD.Path, $NativeDllName))
    $candidateDllPaths.Add([System.IO.Path]::Combine($PWD.Path, "dist\$NativeDllName"))
    $candidateDllPaths.Add([System.IO.Path]::Combine($PWD.Path, "native\$NativeDllName"))
}

$SourceDll = $null
foreach ($dp in $candidateDllPaths) {
    if ($dp -and (Test-Path -LiteralPath $dp -ErrorAction SilentlyContinue)) {
        $SourceDll = $dp
        break
    }
}
if ($SourceDll) {
    Write-Host "Using local native governor: $SourceDll" -ForegroundColor Gray
} else {
    Write-Host "`n[Notice] '$NativeDllName' not found locally next to install.bat." -ForegroundColor Yellow
    $dllDownloadTarget = [System.IO.Path]::Combine($ScriptDir, $NativeDllName)
    if (Download-PZOGitHubDll $dllDownloadTarget) {
        $SourceDll = $dllDownloadTarget
    }
}

# ZombieBuddy coexistence
$zbNativeDll = [System.IO.Path]::Combine($InstallPath, "zbNative.dll")
$zbNativeWin64 = [System.IO.Path]::Combine($InstallPath, "win64\zbNative.dll")
$hasZbInJson = $false

if (Test-Path -LiteralPath $TargetFilePath) {
    $rawJson = Get-Content -LiteralPath $TargetFilePath -Raw -ErrorAction SilentlyContinue
    if ($rawJson -and ($rawJson -match "zbNative" -or $rawJson -match "ZombieBuddy")) {
        $hasZbInJson = $true
    }
}

$isZombieBuddyActive = $hasZbInJson -or (Test-Path -LiteralPath $zbNativeDll) -or (Test-Path -LiteralPath $zbNativeWin64)
if ($isZombieBuddyActive) {
    Write-Host "`n[+] ZombieBuddy detected! Coexistence mode enabled (preserving -agentlib:zbNative)." -ForegroundColor Green
}

# Check for Zed Better FPS NG conflict / redundancy
$zbFpsWorkshop = [System.IO.Path]::Combine($InstallPath, "..\..\workshop\content\108600\3793137588")
$zbFpsMods = [System.IO.Path]::Combine($env:USERPROFILE, "Zomboid\mods\ZBBetterFPSNG")
if ((Test-Path -LiteralPath $zbFpsWorkshop) -or (Test-Path -LiteralPath $zbFpsMods)) {
    Write-Host "`n[!] NOTICE: Zed Better FPS NG detected in Workshop/Mods!" -ForegroundColor Yellow
    Write-Host "    PZO natively includes hardware-level AVX2 culling, multi-core chunk streaming," -ForegroundColor Gray
    Write-Host "    and kernel thread scheduling that outperforms and replaces Java-level FPS mods." -ForegroundColor Gray
    Write-Host "    ZombieBuddy itself and all other gameplay/Lua mods remain 100% compatible." -ForegroundColor Gray
    Write-Host "    We recommend disabling Zed Better FPS NG to prevent duplicate hook overhead.`n" -ForegroundColor Yellow
}

# Older or low-end PC (issue #45): same rules as CompatMode in the engine. pzo_config.json "compat_mode" (on / off)
# overrides the detection.
function Get-PZOCompatMode($TotalRamBytes) {
    $mode = "auto"
    try {
        $cfg = [System.IO.Path]::Combine($ZomboidLuaDir, "pzo_config.json")
        if (Test-Path -LiteralPath $cfg -ErrorAction SilentlyContinue) {
            $raw = Get-Content -LiteralPath $cfg -Raw -ErrorAction Stop
            if ($raw -match '"compat_mode"\s*:\s*"(on|off|auto)"') { $mode = $Matches[1] }
        }
    } catch {}
    $why = @()
    if ($env:PROCESSOR_ARCHITECTURE -ne "ARM64" -and $env:PROCESSOR_ARCHITEW6432 -ne "ARM64") {
        try {
            if (-not ("PZO.CpuFeatures" -as [type])) {
                Add-Type -Namespace PZO -Name CpuFeatures -MemberDefinition '[DllImport("kernel32.dll")] public static extern bool IsProcessorFeaturePresent(uint feature);'
            }
            # PF_AVX2_INSTRUCTIONS_AVAILABLE; also false when the OS has the AVX register state switched off
            if (-not [PZO.CpuFeatures]::IsProcessorFeaturePresent(40)) { $why += "no AVX2" }
        } catch {}
    }
    $threads = [Environment]::ProcessorCount
    if ($threads -le 4) { $why += "$threads threads" }
    if ($TotalRamBytes -gt 0 -and ($TotalRamBytes / 1GB) -lt 7.5) { $why += ("{0:N1} GB RAM" -f ($TotalRamBytes / 1GB)) }
    $active = $why.Count -gt 0
    if ($mode -eq "on") { $active = $true }
    if ($mode -eq "off") { $active = $false }
    [pscustomobject]@{ Active = $active; Reasons = ($why -join ", ") }
}

# Two args with the same key are the same setting with different values
function Get-PZOArgKey([string]$arg) {
    if ($arg -match '^-XX:\+Use(ZGC|G1GC|SerialGC|ParallelGC|ShenandoahGC)$') { return "gc" }
    if ($arg -match '^-agent.*zbNative') { return "zb" }
    if ($arg -cmatch '^-Xm[sx]') { return $arg.Substring(0, 4) }
    if ($arg -match '^-XX:[+-]?([A-Za-z0-9_]+)') { return "-XX:" + $Matches[1] }
    if ($arg -match '^-D([^=]+)') { return "-D" + $Matches[1] }
    if ($arg -match '^-(agentlib|agentpath|javaagent):([^=]+)') { return "-" + $Matches[1] + ":" + $Matches[2] }
    return $arg
}

# Args someone joined with spaces get split back up; a value that just contains a space (a folder path) stays whole
function Split-PZOJoinedArgs($values) {
    foreach ($value in @($values)) {
        if ($null -eq $value) { continue }
        $text = ([string]$value).Trim()
        if (-not $text) { continue }
        $parts = @($text -split '\s+')
        if (@($parts | Where-Object { -not $_.StartsWith("-") }).Count -gt 0) { $text } else { $parts }
    }
}

function Test-PZOZgcOnlyArg([string]$arg) {
    return $arg -match '^-XX:[+-]?(ZGenerational|ZAllocationSpikeTolerance|ZCollectionInterval|ZUncommit|ZUncommitDelay|SoftMaxHeapSize)\b'
}

function Get-PZOHeapMb([string]$arg) {
    if ($arg -notmatch '^-Xm[sx](\d+)([gGmMkK]?)$') { return -1 }
    $n = [double]$Matches[1]
    switch ($Matches[2].ToLower()) {
        "g" { return $n * 1024 }
        "m" { return $n }
        "k" { return $n / 1024 }
        default { return $n / 1MB }
    }
}

function Get-PZONormalizedPath([string]$entry) {
    $e = $entry.Trim().Replace('\', '/')
    while ($e.Length -gt 1 -and $e.EndsWith("/")) { $e = $e.Substring(0, $e.Length - 1) }
    while ($e.Length -gt 2 -and $e.StartsWith("./")) { $e = $e.Substring(2) }
    return $e
}

function Format-PZOJson($value, [int]$indent = 0) {
    $pad = " " * ($indent + 4)
    $end = " " * $indent
    if ($null -eq $value) { return "null" }
    if ($value -is [bool]) { if ($value) { return "true" } else { return "false" } }
    if ($value -is [string]) {
        $sb = New-Object System.Text.StringBuilder
        foreach ($c in $value.ToCharArray()) {
            switch ($c) {
                '"' { [void]$sb.Append('\"') }
                '\' { [void]$sb.Append('\\') }
                "`n" { [void]$sb.Append('\n') }
                "`r" { [void]$sb.Append('\r') }
                "`t" { [void]$sb.Append('\t') }
                default {
                    if ([int]$c -lt 32) { [void]$sb.Append(('\u{0:x4}' -f [int]$c)) } else { [void]$sb.Append($c) }
                }
            }
        }
        return '"' + $sb.ToString() + '"'
    }
    if ($value -is [array] -or $value -is [System.Collections.ArrayList]) {
        if ($value.Count -eq 0) { return "[]" }
        $items = foreach ($item in $value) { $pad + (Format-PZOJson $item ($indent + 4)) }
        return "[`n" + ($items -join ",`n") + "`n$end]"
    }
    if ($value -is [System.Management.Automation.PSCustomObject]) {
        $props = @($value.PSObject.Properties)
        if ($props.Count -eq 0) { return "{}" }
        $items = foreach ($p in $props) { $pad + (Format-PZOJson $p.Name) + ": " + (Format-PZOJson $p.Value ($indent + 4)) }
        return "{`n" + ($items -join ",`n") + "`n$end}"
    }
    return ([System.IConvertible]$value).ToString([System.Globalization.CultureInfo]::InvariantCulture)
}

# Applies what PZO needs on top of the user's file, so their own args, classpath entries and other keys stay. If the
# file can't be read as JSON the template is used; Apply-PZOConfiguration has already backed it up by then.
function Merge-PZOUserSettings($TemplateJson, $Path, [bool]$CompatActive) {
    if (-not (Test-Path -LiteralPath $Path -ErrorAction SilentlyContinue)) { return $TemplateJson }
    try {
        $old = [System.IO.File]::ReadAllText($Path) | ConvertFrom-Json
        $tpl = $TemplateJson | ConvertFrom-Json
    } catch {
        Write-Host "    [!] $([System.IO.Path]::GetFileName($Path)) is not valid JSON, so custom settings could not be carried over. The old file is in $BackupFolder." -ForegroundColor Yellow
        return $TemplateJson
    }
    if ($null -eq $old -or $null -eq $old.vmArgs) { return $TemplateJson }

    $oldArgs = @(Split-PZOJoinedArgs $old.vmArgs | Where-Object {
        $_ -notmatch 'java\.awt\.headless|pzo_native64|UseCompactObjectHeaders' -and
        -not ($CompatActive -and ($_ -eq "-XX:+AlwaysPreTouch" -or $_ -eq "-XX:+UseZGC" -or (Test-PZOZgcOnlyArg $_)))
    })

    # ZGC is what the game ships and the template picks it by hardware, so only another collector counts as a choice
    $userGc = @($oldArgs | Where-Object { (Get-PZOArgKey $_) -eq "gc" -and $_ -ne "-XX:+UseZGC" }) | Select-Object -First 1

    $merged = New-Object System.Collections.ArrayList
    foreach ($a in @($tpl.vmArgs)) {
        if ($userGc -and (Get-PZOArgKey $a) -eq "gc") { continue }
        [void]$merged.Add([string]$a)
    }
    if ($userGc) { [void]$merged.Add([string]$userGc) }

    $zgcInUse = $merged -contains "-XX:+UseZGC"
    if (-not $userGc -and $tpl.windows) {
        foreach ($p in $tpl.windows.PSObject.Properties) {
            if ($p.Name -ne "6.1" -and @($p.Value.vmArgs) -contains "-XX:+UseZGC") { $zgcInUse = $true }
        }
    }
    if (-not $zgcInUse) {
        foreach ($a in @($merged | Where-Object { Test-PZOZgcOnlyArg $_ })) { $merged.Remove($a) }
    }

    $oldHasXms = $false
    $oldHasXmx = $false
    foreach ($arg in $oldArgs) {
        $key = Get-PZOArgKey $arg
        if ($key -eq "gc") { continue }
        if (-not $zgcInUse -and (Test-PZOZgcOnlyArg $arg)) { continue }
        if ($arg -eq "-Xmx3072m") { continue }  # the game's stock max, raised by the template

        if ($key -eq "-Djava.library.path") {
            for ($i = 0; $i -lt $merged.Count; $i++) {
                if ((Get-PZOArgKey $merged[$i]) -ne $key) { continue }
                $value = $merged[$i]
                $known = @(($value.Substring($value.IndexOf("=") + 1) -split ";") | ForEach-Object { Get-PZONormalizedPath $_ })
                foreach ($entry in ($arg.Substring($arg.IndexOf("=") + 1) -split ";")) {
                    if (-not $entry.Trim()) { continue }
                    $norm = Get-PZONormalizedPath $entry
                    if ($known -notcontains $norm) {
                        $value += ";" + $entry.Trim()
                        $known += $norm
                    }
                }
                $merged[$i] = $value
            }
            continue
        }

        if ($key -ceq "-Xms") { $oldHasXms = $true }
        if ($key -ceq "-Xmx") { $oldHasXmx = $true }

        $found = $false
        for ($i = 0; $i -lt $merged.Count; $i++) {
            if ((Get-PZOArgKey $merged[$i]) -ceq $key) {
                $merged[$i] = $arg
                $found = $true
                break
            }
        }
        if (-not $found) { [void]$merged.Add($arg) }
    }

    # The JVM won't start with the starting heap above the max, so whichever size the user didn't set gives way
    $xmsIndex = -1
    $xmxIndex = -1
    for ($i = 0; $i -lt $merged.Count; $i++) {
        if ($merged[$i] -cmatch '^-Xms') { $xmsIndex = $i }
        if ($merged[$i] -cmatch '^-Xmx') { $xmxIndex = $i }
    }
    if ($xmsIndex -ge 0 -and $xmxIndex -ge 0) {
        if ($CompatActive -and (Get-PZOHeapMb $merged[$xmsIndex]) -gt 1024) { $merged[$xmsIndex] = "-Xms1024m" }
        $xmsMb = Get-PZOHeapMb $merged[$xmsIndex]
        $xmxMb = Get-PZOHeapMb $merged[$xmxIndex]
        if ($xmsMb -gt 0 -and $xmxMb -gt 0 -and $xmsMb -gt $xmxMb) {
            if ($oldHasXms -and -not $oldHasXmx) {
                $merged[$xmxIndex] = "-Xmx" + $merged[$xmsIndex].Substring(4)
            } elseif ($oldHasXmx -and -not $oldHasXms) {
                $merged[$xmsIndex] = "-Xms" + $merged[$xmxIndex].Substring(4)
            }
        }
    }

    $classpath = New-Object System.Collections.ArrayList
    foreach ($c in @($old.classpath)) {
        if ($c -and -not $classpath.Contains([string]$c)) { [void]$classpath.Add([string]$c) }
    }
    if (-not $classpath.Contains(".")) { $classpath.Insert(0, ".") }
    if (-not $classpath.Contains("PZOptimEngine.jar")) { $classpath.Insert($classpath.IndexOf(".") + 1, "PZOptimEngine.jar") }
    if (-not $classpath.Contains("projectzomboid.jar")) { [void]$classpath.Add("projectzomboid.jar") }
    foreach ($c in @($tpl.classpath)) {
        if (-not $classpath.Contains([string]$c)) { $classpath.Insert($classpath.IndexOf("projectzomboid.jar"), [string]$c) }
    }

    # Version blocks: the template decides the collector, anything else the user added there stays
    $topHasGc = @($merged | Where-Object { (Get-PZOArgKey $_) -eq "gc" }).Count -gt 0
    $versions = New-Object System.Collections.ArrayList
    foreach ($source in @($tpl.windows, $old.windows)) {
        if ($null -eq $source) { continue }
        foreach ($p in $source.PSObject.Properties) {
            if (-not $versions.Contains($p.Name)) { [void]$versions.Add($p.Name) }
        }
    }
    $windows = New-Object PSObject
    foreach ($name in $versions) {
        $tplBlock = if ($tpl.windows) { $tpl.windows.PSObject.Properties[$name] } else { $null }
        $oldBlock = if ($old.windows) { $old.windows.PSObject.Properties[$name] } else { $null }
        $blockArgs = New-Object System.Collections.ArrayList
        if ($tplBlock) { foreach ($a in @($tplBlock.Value.vmArgs)) { [void]$blockArgs.Add([string]$a) } }
        if ($oldBlock) {
            foreach ($arg in @(Split-PZOJoinedArgs $oldBlock.Value.vmArgs)) {
                if ($arg -match 'pzo_native64|zbNative|UseCompactObjectHeaders') { continue }
                $key = Get-PZOArgKey $arg
                if ($key -eq "gc") {
                    if (-not $tplBlock -and -not $CompatActive) { [void]$blockArgs.Add($arg) }
                    continue
                }
                if ((Test-PZOZgcOnlyArg $arg) -and $blockArgs -notcontains "-XX:+UseZGC") { continue }
                $found = $false
                for ($i = 0; $i -lt $blockArgs.Count; $i++) {
                    if ((Get-PZOArgKey $blockArgs[$i]) -ceq $key) {
                        $blockArgs[$i] = $arg
                        $found = $true
                        break
                    }
                }
                if (-not $found) { [void]$blockArgs.Add($arg) }
            }
        }
        if ($topHasGc) {
            foreach ($a in @($blockArgs | Where-Object { (Get-PZOArgKey $_) -eq "gc" -or (Test-PZOZgcOnlyArg $_) })) { $blockArgs.Remove($a) }
        }
        if ($blockArgs.Count -eq 0) { continue }
        $block = if ($oldBlock) { $oldBlock.Value } else { New-Object PSObject }
        $block | Add-Member -NotePropertyName "vmArgs" -NotePropertyValue $blockArgs -Force
        $windows | Add-Member -NotePropertyName $name -NotePropertyValue $block -Force
    }

    $old | Add-Member -NotePropertyName "mainClass" -NotePropertyValue $tpl.mainClass -Force
    $old | Add-Member -NotePropertyName "classpath" -NotePropertyValue $classpath -Force
    $old | Add-Member -NotePropertyName "vmArgs" -NotePropertyValue $merged -Force
    if (@($windows.PSObject.Properties).Count -gt 0) {
        $old | Add-Member -NotePropertyName "windows" -NotePropertyValue $windows -Force
    } elseif ($old.PSObject.Properties["windows"]) {
        $old.PSObject.Properties.Remove("windows")
    }
    return (Format-PZOJson $old) + "`n"
}

function Test-PZOJsonHasPzo {
    if (-not (Test-Path -LiteralPath $TargetFilePath -ErrorAction SilentlyContinue)) { return $false }
    $raw = Get-Content -LiteralPath $TargetFilePath -Raw -ErrorAction SilentlyContinue
    return [bool]($raw -and (($raw -match "com/pzoptimizer") -or ($raw -match "pzo_native64") -or ($raw -match "PZOptimEngine\.jar")))
}

# Puts ProjectZomboid64.json back to stock: the oldest backup without PZO in it, then strip anything PZO left behind
function Restore-PZOStockJson {
    $cleanBackup = $null
    if (Test-Path -LiteralPath $BackupDir -ErrorAction SilentlyContinue) {
        $bakFiles = Get-ChildItem -LiteralPath $BackupDir -Filter "$($TargetFileName)_*.bak" -ErrorAction SilentlyContinue | Sort-Object LastWriteTime
        foreach ($bf in $bakFiles) {
            $txt = Get-Content -LiteralPath $bf.FullName -Raw -ErrorAction SilentlyContinue
            if ($txt -and ($txt -notmatch "com/pzoptimizer") -and ($txt -notmatch "pzo_native64")) {
                $cleanBackup = $bf
                break
            }
        }
    }

    if ($cleanBackup) {
        Copy-Item -Path $cleanBackup.FullName -Destination $TargetFilePath -Force
        Write-Host "Restored original clean stock backup: $($cleanBackup.Name) -> $TargetFileName" -ForegroundColor Green
    }

    if (Test-PZOJsonHasPzo) {
        $jsonRaw = Get-Content -LiteralPath $TargetFilePath -Raw -ErrorAction SilentlyContinue
        $sanitized = $jsonRaw -replace '"mainClass"\s*:\s*"com/pzoptimizer/PZOEntrypoint"', '"mainClass": "zombie/gameStates/MainScreenState"'
        $sanitized = $sanitized -replace '\s*"PZOptimEngine\.jar",?', ''
        $sanitized = $sanitized -replace '\s*"-agentlib:pzo_native64",?', ''
        $sanitized = $sanitized -replace ',\s*\]', "`n    ]"
        [System.IO.File]::WriteAllText($TargetFilePath, $sanitized, (New-Object System.Text.UTF8Encoding($false)))
        Write-Host "Sanitized $($TargetFileName): Restored vanilla mainClass and removed PZO agents." -ForegroundColor Green
    }
}

function Format-PZOBatArg([string]$arg) {
    $arg = $arg.Replace('%', '%%')
    if ($arg -match '[\s&|<>^()]') { return '"' + $arg + '"' }
    return $arg
}

# The stock .bat launchers call java.exe directly and never read ProjectZomboid64.json, so this writes one that runs
# the same command line the exe builds from the JSON
function Write-PZOLaunchBat([string]$JsonText) {
    try {
        $cfg = $JsonText | ConvertFrom-Json
    } catch {
        Write-Host "[ERROR] Could not read the merged config, $LaunchBatName was not written." -ForegroundColor Red
        return
    }

    $launchArgs = New-Object System.Collections.ArrayList
    foreach ($a in @(Split-PZOJoinedArgs $cfg.vmArgs)) { [void]$launchArgs.Add([string]$a) }

    # The exe adds one windows.<version> block: the highest version this Windows meets. Applying two would give the
    # JVM two collectors and it refuses to start.
    if ($cfg.windows) {
        $os = [Environment]::OSVersion.Version
        $bestVer = $null
        $bestArgs = $null
        foreach ($p in $cfg.windows.PSObject.Properties) {
            $v = $null
            if (-not [version]::TryParse($p.Name, [ref]$v)) { continue }
            if ($v -le $os -and ($null -eq $bestVer -or $v -gt $bestVer)) {
                $bestVer = $v
                $bestArgs = $p.Value.vmArgs
            }
        }
        foreach ($a in @(Split-PZOJoinedArgs $bestArgs)) {
            $key = Get-PZOArgKey $a
            for ($i = $launchArgs.Count - 1; $i -ge 0; $i--) {
                if ((Get-PZOArgKey $launchArgs[$i]) -ceq $key) { $launchArgs.RemoveAt($i) }
            }
            [void]$launchArgs.Add([string]$a)
        }
    }

    $classpath = (@($cfg.classpath) | ForEach-Object { [string]$_ }) -join ";"
    $mainClass = ([string]$cfg.mainClass).Replace('/', '.')
    $tail = "-cp " + (Format-PZOBatArg $classpath) + " " + $mainClass + " %*"

    $fullLine = (@($launchArgs | ForEach-Object { Format-PZOBatArg $_ }) -join " ")
    # Same fallback as the stock launcher: if the JVM won't start, try again with its default collector
    $noGcLine = (@($launchArgs | Where-Object { (Get-PZOArgKey $_) -ne "gc" -and -not (Test-PZOZgcOnlyArg $_) } | ForEach-Object { Format-PZOBatArg $_ }) -join " ")

    $lines = @(
        "@echo off",
        "rem Written by the PZO installer from $TargetFileName. Run install.bat again after changing that file.",
        "setlocal enableextensions",
        "cd /d `"%~dp0`"",
        "set _JAVA_OPTIONS=",
        "",
        "`".\jre64\bin\java.exe`" $fullLine $tail",
        "",
        "if %ERRORLEVEL% NEQ 0 (",
        "    `".\jre64\bin\java.exe`" $noGcLine $tail",
        ")",
        "",
        "pause"
    )
    try {
        [System.IO.File]::WriteAllText($LaunchBatPath, (($lines -join "`r`n") + "`r`n"), [System.Text.Encoding]::ASCII)
        Write-Host "[SUCCESS] Wrote alternative launcher: $LaunchBatPath" -ForegroundColor Green
        Write-Host "    Start the game with $LaunchBatName (or point your shortcut at it) to run with PZO." -ForegroundColor Gray
        Write-Host "    The stock ProjectZomboid64.bat still launches the unmodded game." -ForegroundColor Gray
    } catch {
        Write-Host "[ERROR] Failed writing $LaunchBatPath. Check folder write permissions." -ForegroundColor Red
    }
}

function Remove-PZOLaunchBat {
    if (Test-Path -LiteralPath $LaunchBatPath -ErrorAction SilentlyContinue) {
        Remove-Item -LiteralPath $LaunchBatPath -Force -ErrorAction SilentlyContinue
        Write-Host "Removed: $LaunchBatName" -ForegroundColor Green
    }
}

function Read-PZOLaunchMode {
    $hasBat = Test-Path -LiteralPath $LaunchBatPath -ErrorAction SilentlyContinue
    $default = "1"
    if ($hasBat -and (Test-PZOJsonHasPzo)) { $default = "3" } elseif ($hasBat) { $default = "2" }

    Write-Host "`nHow do you start Project Zomboid?" -ForegroundColor Cyan
    Write-Host "1) Normal launch      - Steam or ProjectZomboid64.exe (sets up $TargetFileName)"
    Write-Host "2) Alternative launch - a .bat file (writes $LaunchBatName, Steam/exe launch stays stock)"
    Write-Host "3) Both"
    while ($true) {
        $c = Read-Host "`nEnter choice (1, 2, or 3, Default: $default)"
        if ([string]::IsNullOrWhiteSpace($c)) { $c = $default }
        switch ($c.Trim()) {
            "1" { return "normal" }
            "2" { return "alternative" }
            "3" { return "both" }
        }
        Write-Host "Please enter 1, 2 or 3." -ForegroundColor Yellow
    }
}

function Apply-PZOConfiguration([string]$LaunchMode = "normal") {
    $TotalRamBytes = 0
    try {
        $TotalRamBytes = (Get-CimInstance Win32_ComputerSystem -ErrorAction Stop).TotalPhysicalMemory
    } catch {
        try {
            $TotalRamBytes = (Get-WmiObject Win32_ComputerSystem).TotalPhysicalMemory
        } catch {
            $TotalRamBytes = 16GB
        }
    }
    $TotalRamGB = [Math]::Round($TotalRamBytes / 1GB)
    if ($TotalRamGB -lt 4) { $TotalRamGB = 8 }
    Write-Host "Detected System RAM: $TotalRamGB GB" -ForegroundColor Yellow

    # Detect Available Free Physical RAM for commit safety check
    $FreeRamGB = 0
    try {
        $FreeRamKB = (Get-CimInstance Win32_OperatingSystem -ErrorAction Stop).FreePhysicalMemory
        $FreeRamGB = [Math]::Round($FreeRamKB / 1MB, 1)
    } catch {
        try {
            $FreeRamKB = (Get-WmiObject Win32_OperatingSystem).FreePhysicalMemory
            $FreeRamGB = [Math]::Round($FreeRamKB / 1MB, 1)
        } catch {}
    }
    if ($FreeRamGB -gt 0) {
        Write-Host "Available Free RAM: $FreeRamGB GB" -ForegroundColor Gray
    }

    if (-not (Test-Path -LiteralPath $BackupDir -ErrorAction SilentlyContinue)) {
        New-Item -ItemType Directory -Path $BackupDir -Force | Out-Null
    }

    if (Test-Path -LiteralPath $TargetFilePath -ErrorAction SilentlyContinue) {
        $Timestamp = Get-Date -Format "yyyyMMdd_HHmmss"
        Copy-Item -LiteralPath $TargetFilePath -Destination "$BackupDir\$($TargetFileName)_$Timestamp.bak" -Force -ErrorAction SilentlyContinue
        Write-Host "Backed up original $TargetFileName -> $BackupFolder" -ForegroundColor Gray
    }

    # Minimum system RAM required for ZGC (Java 17 without compressed OOPs requires >= 20GB system RAM
    # to avoid pre-touch virtual memory commit failure and immediate JVM exit on startup).
    $ZgcMinRamThresholdGB = 20

    $UseG1GC = $true
    $chosenJson = ""
    if ($TotalRamGB -le 5) {
        Write-Host "Applying profile: <= 5 GB RAM (Heap: 2048m - 4096m | G1GC)" -ForegroundColor Green
        Write-Host "    [!] Low RAM detected ($TotalRamGB GB): Enforcing G1GC with Compressed OOPs (ZGC disabled to prevent OOM)." -ForegroundColor Cyan
        $chosenJson = $Json5OrLess
        $UseG1GC = $true
    }
    elseif ($TotalRamGB -ge 6 -and $TotalRamGB -le 12) {
        Write-Host "Applying profile: 6 - 12 GB RAM (Heap: 3072m - 6144m | G1GC)" -ForegroundColor Green
        Write-Host "    [!] RAM ($TotalRamGB GB) is below ZGC safety threshold ($($ZgcMinRamThresholdGB) GB)." -ForegroundColor Yellow
        Write-Host "    [+] Falling back to G1GC (-XX:+UseG1GC) for launch stability and lower memory overhead." -ForegroundColor Green
        $chosenJson = $Json6To12
        $UseG1GC = $true
    }
    elseif ($TotalRamGB -ge 13 -and $TotalRamGB -le 20) {
        Write-Host "Applying profile: 13 - 20 GB RAM (Heap: 4096m - 8192m)" -ForegroundColor Green
        if ($TotalRamGB -lt $ZgcMinRamThresholdGB -or ($FreeRamGB -gt 0 -and $FreeRamGB -lt 10)) {
            Write-Host "    [!] System RAM ($TotalRamGB GB, Free: $FreeRamGB GB) is below ZGC safety threshold ($($ZgcMinRamThresholdGB) GB)." -ForegroundColor Yellow
            Write-Host "    [+] Falling back to G1GC (-XX:+UseG1GC) with Compressed OOPs to prevent JVM launch crashes." -ForegroundColor Green
            $chosenJson = $Json13To20_G1GC
            $UseG1GC = $true
        } else {
            Write-Host "    [+] Adequate RAM detected ($TotalRamGB GB) -> Applying ZGC profile." -ForegroundColor Green
            $chosenJson = $Json13To20_ZGC
            $UseG1GC = $false
        }
    }
    else {
        # > 20 GB RAM
        Write-Host "Applying profile: > 20 GB RAM (Heap: 8192m - 16384m)" -ForegroundColor Green
        if ($FreeRamGB -gt 0 -and $FreeRamGB -lt 12) {
            Write-Host "    [!] Available free RAM is constrained ($FreeRamGB GB free). Falling back to G1GC to prevent PreTouch commit failure." -ForegroundColor Yellow
            $chosenJson = $JsonAbove20 -replace "-XX:\+UseZGC", "-XX:+UseG1GC"
            $chosenJson = $chosenJson -replace "-XX:ZAllocationSpikeTolerance=5", "-XX:InitiatingHeapOccupancyPercent=45"
            $UseG1GC = $true
        } else {
            Write-Host "    [+] Abundant RAM detected ($TotalRamGB GB >= $($ZgcMinRamThresholdGB) GB) -> Applying ZGC ultra-low latency profile." -ForegroundColor Green
            $chosenJson = $JsonAbove20
            $UseG1GC = $false
        }
    }

    # Compatibility mode: no heap pre-touch (it commits the whole starting heap before the game starts), a 1 GB
    # starting heap that grows as needed, and G1
    $Compat = Get-PZOCompatMode $TotalRamBytes
    if ($Compat.Active) {
        Write-Host "    [!] Compatibility mode for older hardware ($($Compat.Reasons)): no heap pre-touch, 1 GB starting heap, G1GC." -ForegroundColor Cyan
        $chosenJson = $chosenJson -replace ',\s*"-XX:\+AlwaysPreTouch"', ''
        $chosenJson = $chosenJson -replace '"-XX:\+AlwaysPreTouch",\s*', ''
        $chosenJson = $chosenJson -replace '"-Xms\d+m"', '"-Xms1024m"'
        $chosenJson = $chosenJson -replace '"-XX:\+UseZGC"', '"-XX:+UseG1GC"'
        $chosenJson = $chosenJson -replace ',\s*"-XX:ZAllocationSpikeTolerance=\d+"', ''
        $UseG1GC = $true
    }

    # If ZombieBuddy is active, preserve -agentlib:zbNative seamlessly in top-level vmArgs alongside -agentlib:pzo_native64
    if ($isZombieBuddyActive -and ($chosenJson -notmatch "zbNative")) {
        $chosenJson = $chosenJson.Replace('"-agentlib:pzo_native64",', '"-agentlib:zbNative",' + "`n        " + '"-agentlib:pzo_native64",')
    }

    # If ZombieBuddy.jar exists in game directory, preserve it in classpath alongside PZOptimEngine.jar
    $zbJarPath = [System.IO.Path]::Combine($InstallPath, "ZombieBuddy.jar")
    if ((Test-Path -LiteralPath $zbJarPath) -and ($chosenJson -notmatch "ZombieBuddy\.jar")) {
        $chosenJson = $chosenJson.Replace('"projectzomboid.jar"', '"ZombieBuddy.jar",' + "`n        " + '"projectzomboid.jar"')
    }

    $finalJson = Merge-PZOUserSettings $chosenJson $TargetFilePath $Compat.Active

    if ($LaunchMode -ne "alternative") {
        Write-JsonNoBOM $TargetFilePath $finalJson
        if (Test-Path -LiteralPath $TargetFilePath -ErrorAction SilentlyContinue) {
            Write-Host "[SUCCESS] Updated $TargetFileName with PZO Entrypoint & RAM settings." -ForegroundColor Green
        } else {
            Write-Host "[ERROR] Failed writing to $TargetFilePath. Check folder write permissions." -ForegroundColor Red
        }
    } elseif (Test-PZOJsonHasPzo) {
        Write-Host "Alternative launch only: putting $TargetFileName back to stock." -ForegroundColor Gray
        Restore-PZOStockJson
    }

    if ($LaunchMode -ne "normal") {
        Write-PZOLaunchBat $finalJson
    } else {
        Remove-PZOLaunchBat
    }

    if (-not (Test-Path -LiteralPath $ZomboidLuaDir -ErrorAction SilentlyContinue)) {
        New-Item -ItemType Directory -Path $ZomboidLuaDir -Force | Out-Null
    }

    $StatusPayload = [ordered]@{
        optimized   = $true
        ram_gb      = [int]$TotalRamGB
        g1gc        = $UseG1GC
        pretouch    = -not $Compat.Active
        compat_mode = [bool]$Compat.Active
        zombiebuddy = $isZombieBuddyActive
        launch_mode = $LaunchMode
        version     = "2.0.1"
    }

    $StatusJson = $StatusPayload | ConvertTo-Json -Compress
    Write-JsonNoBOM $PzoStatusFile $StatusJson
    Write-Host "Generated Lua bridge status: $PzoStatusFile" -ForegroundColor Green
}

# Existing installation: update or uninstall
if (Test-Path $InstalledJarPath) {
    Write-Host "`n[!] PZOptimEngine is already installed." -ForegroundColor Yellow
    Write-Host "1) Update    - Overwrite PZOptimEngine.jar with the new version & refresh config [Default]"
    Write-Host "2) Uninstall - Remove the mod and restore original settings"
    Write-Host "3) Cancel"
    
    $choice = Read-Host "`nEnter choice (1, 2, or 3, Default: 1)"
    if ([string]::IsNullOrWhiteSpace($choice)) { $choice = "1" }

    switch ($choice) {
        "1" {
            Write-Host "`nUpdating PZOptimEngine.jar and refreshing game configuration..." -ForegroundColor Cyan
            $updated = Download-PZOGitHubJar $InstalledJarPath
            if (-not $updated -and $SourceJar -and (Test-Path $SourceJar)) {
                Copy-Item -Path $SourceJar -Destination $InstalledJarPath -Force
                Write-Host "    [SUCCESS] Updated from local file: $JarFileName -> $InstalledJarPath" -ForegroundColor Green
                $updated = $true
            }
            if (-not $updated) {
                Write-Host "`n[ERROR] Could not download or find $JarFileName to update." -ForegroundColor Red
                return
            }

            Write-Host "`nUpdating Native Governor ($NativeDllName)..." -ForegroundColor Cyan
            $dllUpdated = Download-PZOGitHubDll $InstalledDllPath
            if (-not $dllUpdated -and $SourceDll -and (Test-Path -LiteralPath $SourceDll)) {
                Copy-Item -LiteralPath $SourceDll -Destination $InstalledDllPath -Force
                Write-Host "    [SUCCESS] Updated from local file: $NativeDllName -> $InstalledDllPath" -ForegroundColor Green
                $dllUpdated = $true
            }
            $win64Dir = [System.IO.Path]::Combine($InstallPath, "win64")
            if (Test-Path -LiteralPath $win64Dir) {
                $InstalledWin64Dll = [System.IO.Path]::Combine($win64Dir, $NativeDllName)
                if (Test-Path -LiteralPath $InstalledDllPath) {
                    Copy-Item -LiteralPath $InstalledDllPath -Destination $InstalledWin64Dll -Force
                    Write-Host "    [SUCCESS] Mirrored Native Governor -> $InstalledWin64Dll" -ForegroundColor Green
                }
            }

            $launchMode = Read-PZOLaunchMode
            Write-Host "`nRe-applying optimal configuration..." -ForegroundColor Cyan
            Apply-PZOConfiguration $launchMode
            Write-Host "`n[SUCCESS] Update complete! Project Zomboid is ready." -ForegroundColor Green
            return
        }
        "2" {
            Write-Host "`nUninstalling PZOptimEngine..." -ForegroundColor Cyan
            
            if (Test-Path $InstalledJarPath) {
                Remove-Item -Path $InstalledJarPath -Force
                Write-Host "Removed: $JarFileName" -ForegroundColor Green
            }

            if (Test-Path $InstalledDllPath) {
                Remove-Item -Path $InstalledDllPath -Force
                Write-Host "Removed: $NativeDllName" -ForegroundColor Green
            }
            $win64DllPath = [System.IO.Path]::Combine($InstallPath, "win64\$NativeDllName")
            if (Test-Path -LiteralPath $win64DllPath) {
                Remove-Item -LiteralPath $win64DllPath -Force
                Write-Host "Removed: win64\$NativeDllName" -ForegroundColor Green
            }

            # Purge all pzo_* bridge, telemetry, log and status files from ~/Zomboid/Lua/
            if (Test-Path -LiteralPath $ZomboidLuaDir -ErrorAction SilentlyContinue) {
                Get-ChildItem -LiteralPath $ZomboidLuaDir -Filter "pzo_*" -File -ErrorAction SilentlyContinue | ForEach-Object {
                    Remove-Item -LiteralPath $_.FullName -Force -ErrorAction SilentlyContinue
                    Write-Host "Removed: $($_.Name)" -ForegroundColor Green
                }
            }
            # Clean up any leftover pzo files in root Zomboid or game directories
            $extraPzoPaths = @(
                [System.IO.Path]::Combine($HOME, "Zomboid\pzo_status.json"),
                [System.IO.Path]::Combine($HOME, "Zomboid\pzo_telemetry.json"),
                [System.IO.Path]::Combine($InstallPath, "pzo_engine.log"),
                [System.IO.Path]::Combine($InstallPath, "pzo_telemetry.json"),
                [System.IO.Path]::Combine($InstallPath, "pzo_status.json")
            )
            foreach ($ep in $extraPzoPaths) {
                if (Test-Path -LiteralPath $ep -ErrorAction SilentlyContinue) {
                    Remove-Item -LiteralPath $ep -Force -ErrorAction SilentlyContinue
                    Write-Host "Removed: $([System.IO.Path]::GetFileName($ep))" -ForegroundColor Green
                }
            }

            Restore-PZOStockJson
            Remove-PZOLaunchBat

            # Reset debug-options.ini so vanilla does not thrash chunk FBOs
            $debugOptFile = [System.IO.Path]::Combine($HOME, "Zomboid\debug-options.ini")
            if (Test-Path -LiteralPath $debugOptFile -ErrorAction SilentlyContinue) {
                $dbgContent = Get-Content -LiteralPath $debugOptFile -Raw -ErrorAction SilentlyContinue
                if ($dbgContent) {
                    $cleanedDbg = $dbgContent -replace "FBORenderChunk\.CorpsesInChunkTexture=true", "FBORenderChunk.CorpsesInChunkTexture=false"
                    $cleanedDbg = $cleanedDbg -replace "FBORenderChunk\.ItemsInChunkTexture=true", "FBORenderChunk.ItemsInChunkTexture=false"
                    $cleanedDbg = $cleanedDbg -replace "Lighting\.SplitUpdate=true", "Lighting.SplitUpdate=false"
                    $cleanedDbg = $cleanedDbg -replace "Threading\.Lighting=true", "Threading.Lighting=false"
                    [System.IO.File]::WriteAllText($debugOptFile, $cleanedDbg, [System.Text.Encoding]::ASCII)
                    Write-Host "Cleansed: debug-options.ini (vanilla rendering and lighting restored)" -ForegroundColor Green
                }
            }

            Write-Host "`nUninstallation complete! Restored to stock settings." -ForegroundColor Cyan
            return
        }
        default {
            Write-Host "`nOperation cancelled." -ForegroundColor Yellow
            return
        }
    }
}

# Fresh installation
Write-Host "`nStarting fresh installation..." -ForegroundColor Cyan

if ($SourceJar -and (Test-Path -LiteralPath $SourceJar -ErrorAction SilentlyContinue)) {
    Copy-Item -LiteralPath $SourceJar -Destination $InstalledJarPath -Force
    Write-Host "Installed: $JarFileName -> $InstallPath" -ForegroundColor Green
} else {
    Write-Host "Error: '$JarFileName' was not found in the installer directory ($ScriptDir)." -ForegroundColor Red
    return
}

if ($SourceDll -and (Test-Path -LiteralPath $SourceDll -ErrorAction SilentlyContinue)) {
    Copy-Item -LiteralPath $SourceDll -Destination $InstalledDllPath -Force
    Write-Host "Installed Native Governor: $NativeDllName -> $InstallPath" -ForegroundColor Green
    $win64Dir = [System.IO.Path]::Combine($InstallPath, "win64")
    if (Test-Path -LiteralPath $win64Dir) {
        $InstalledWin64Dll = [System.IO.Path]::Combine($win64Dir, $NativeDllName)
        Copy-Item -LiteralPath $SourceDll -Destination $InstalledWin64Dll -Force
        Write-Host "Mirrored Native Governor -> $InstalledWin64Dll" -ForegroundColor Green
    }
}

$launchMode = Read-PZOLaunchMode
Apply-PZOConfiguration $launchMode
Write-Host "`n[SUCCESS] Installation & optimization complete! You can now start Project Zomboid." -ForegroundColor Cyan

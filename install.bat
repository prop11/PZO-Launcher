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
# ==============================================================================
# Project Zomboid Build 42 - Config & Engine Optimizer (PZO)
# Native PowerShell Engine Installer & Uninstaller
# ==============================================================================

Write-Host "=================================================================" -ForegroundColor Cyan
Write-Host " Project Zomboid Build 42 Engine Optimizer (v0.8.3-unstable.1)" -ForegroundColor Cyan
Write-Host " Native Configuration & Engine Agent Installer" -ForegroundColor Cyan
Write-Host "=================================================================" -ForegroundColor Cyan

$ScriptDir = if ($env:PZO_INSTALLER_DIR) { $env:PZO_INSTALLER_DIR.TrimEnd('\') } else { $PWD.Path }
$JarFileName    = "PZOptimEngine.jar"
$TargetFileName = "ProjectZomboid64.json"
$BackupFolder   = "Installer_Backups"
$ZomboidLuaDir  = [System.IO.Path]::Combine($HOME, "Zomboid\Lua")
$PzoStatusFile  = [System.IO.Path]::Combine($ZomboidLuaDir, "pzo_status.json")
$ZomboidModsDir = [System.IO.Path]::Combine($HOME, "Zomboid\mods")

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

# ==========================================
# JSON TEMPLATES (Defined upfront - No BOM)
# ==========================================
$Json5OrLess = @"
{
    "mainClass": "com/pzoptimizer/PZOEntrypoint",
    "classpath": [
        ".",
        "PZOptimEngine.jar",
        "projectzomboid.jar"
    ],
    "vmArgs": [
        "--enable-native-access=ALL-UNNAMED",
        "--add-exports=java.base/jdk.internal.misc=ALL-UNNAMED",
        "-Xms2048m",
        "-Xmx4096m",
        "-Dzomboid.steam=1",
        "-Dzomboid.znetlog=1",
        "-Djava.library.path=win64/;.",
        "-XX:-CreateCoredumpOnCrash",
        "-XX:-OmitStackTraceInFastThrow",
        "-XX:+PerfDisableSharedMem",
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
                "-XX:+UseZGC"
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
        "--enable-native-access=ALL-UNNAMED",
        "--add-exports=java.base/jdk.internal.misc=ALL-UNNAMED",
        "-Xms3072m",
        "-Xmx6144m",
        "-Dzomboid.steam=1",
        "-Dzomboid.znetlog=1",
        "-Djava.library.path=win64/;.",
        "-XX:-CreateCoredumpOnCrash",
        "-XX:-OmitStackTraceInFastThrow",
        "-XX:+PerfDisableSharedMem",
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
                "-XX:+UseZGC"
            ]
        }
    }
}
"@

$Json13To20 = @"
{
    "mainClass": "com/pzoptimizer/PZOEntrypoint",
    "classpath": [
        ".",
        "PZOptimEngine.jar",
        "projectzomboid.jar"
    ],
    "vmArgs": [
        "--enable-native-access=ALL-UNNAMED",
        "--add-exports=java.base/jdk.internal.misc=ALL-UNNAMED",
        "-Xms4096m",
        "-Xmx8192m",
        "-Dzomboid.steam=1",
        "-Dzomboid.znetlog=1",
        "-Djava.library.path=win64/;.",
        "-XX:-CreateCoredumpOnCrash",
        "-XX:-OmitStackTraceInFastThrow",
        "-XX:+PerfDisableSharedMem",
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
                "-XX:+UseZGC"
            ]
        }
    }
}
"@

$JsonAbove20 = @"
{
    "mainClass": "com/pzoptimizer/PZOEntrypoint",
    "classpath": [
        ".",
        "PZOptimEngine.jar",
        "projectzomboid.jar"
    ],
    "vmArgs": [
        "--enable-native-access=ALL-UNNAMED",
        "--add-exports=java.base/jdk.internal.misc=ALL-UNNAMED",
        "-Xms8192m",
        "-Xmx16384m",
        "-Dzomboid.steam=1",
        "-Dzomboid.znetlog=1",
        "-Djava.library.path=win64/;.",
        "-XX:-CreateCoredumpOnCrash",
        "-XX:-OmitStackTraceInFastThrow",
        "-XX:+PerfDisableSharedMem",
        "-XX:+UseZGC",
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
$TargetFilePath   = [System.IO.Path]::Combine($InstallPath, $TargetFileName)
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

# ==========================================
# ZOMBIEBUDDY COEXISTENCE & PRESERVATION
# ==========================================
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

function Apply-PZOConfiguration {
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

    if (-not (Test-Path -LiteralPath $BackupDir -ErrorAction SilentlyContinue)) {
        New-Item -ItemType Directory -Path $BackupDir -Force | Out-Null
    }

    if (Test-Path -LiteralPath $TargetFilePath -ErrorAction SilentlyContinue) {
        $Timestamp = Get-Date -Format "yyyyMMdd_HHmmss"
        Copy-Item -LiteralPath $TargetFilePath -Destination "$BackupDir\$($TargetFileName)_$Timestamp.bak" -Force -ErrorAction SilentlyContinue
        Write-Host "Backed up original $TargetFileName -> $BackupFolder" -ForegroundColor Gray
    }

    $UseG1GC = $false
    $chosenJson = ""
    if ($TotalRamGB -le 5) {
        Write-Host "Applying profile: <= 5 GB RAM (Heap: 2048m - 4096m)" -ForegroundColor Green
        $chosenJson = $Json5OrLess
    }
    elseif ($TotalRamGB -ge 6 -and $TotalRamGB -le 12) {
        Write-Host "Applying profile: 6 - 12 GB RAM (Heap: 3072m - 6144m)" -ForegroundColor Green
        $chosenJson = $Json6To12
    }
    elseif ($TotalRamGB -ge 13 -and $TotalRamGB -le 20) {
        Write-Host "Applying profile: 13 - 20 GB RAM (Heap: 4096m - 8192m)" -ForegroundColor Green
        $chosenJson = $Json13To20
    }
    else {
        Write-Host "Applying profile: > 20 GB RAM (Heap: 8192m - 16384m)" -ForegroundColor Green
        $chosenJson = $JsonAbove20
        $UseG1GC = $true
    }

    # If ZombieBuddy is active, preserve -agentlib:zbNative seamlessly in vmArgs
    if ($isZombieBuddyActive -and ($chosenJson -notmatch "zbNative")) {
        $chosenJson = $chosenJson.Replace('"vmArgs": [', '"vmArgs": [' + "`n        " + '"-agentlib:zbNative",')
    }

    Write-JsonNoBOM $TargetFilePath $chosenJson

    if (Test-Path -LiteralPath $TargetFilePath -ErrorAction SilentlyContinue) {
        Write-Host "[SUCCESS] Updated $TargetFileName with PZO Entrypoint & RAM settings." -ForegroundColor Green
    } else {
        Write-Host "[ERROR] Failed writing to $TargetFilePath. Check folder write permissions." -ForegroundColor Red
    }

    if (-not (Test-Path -LiteralPath $ZomboidLuaDir -ErrorAction SilentlyContinue)) {
        New-Item -ItemType Directory -Path $ZomboidLuaDir -Force | Out-Null
    }

    $StatusPayload = [ordered]@{
        optimized   = $true
        ram_gb      = [int]$TotalRamGB
        g1gc        = $UseG1GC
        pretouch    = $true
        zombiebuddy = $isZombieBuddyActive
        version     = "0.8.3-unstable.1"
    }

    $StatusJson = $StatusPayload | ConvertTo-Json -Compress
    Write-JsonNoBOM $PzoStatusFile $StatusJson
    Write-Host "Generated Lua bridge status: $PzoStatusFile" -ForegroundColor Green
}

# ==========================================
# EXISTING INSTALLATION CHECK (UPDATE / UNINSTALL)
# ==========================================
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

            Write-Host "`nRe-applying optimal configuration to ProjectZomboid64.json..." -ForegroundColor Cyan
            Apply-PZOConfiguration
            Write-Host "`n[SUCCESS] Update complete! Project Zomboid is ready." -ForegroundColor Green
            return
        }
        "2" {
            Write-Host "`nUninstalling PZOptimEngine..." -ForegroundColor Cyan
            
            if (Test-Path $InstalledJarPath) {
                Remove-Item -Path $InstalledJarPath -Force
                Write-Host "Removed: $JarFileName" -ForegroundColor Green
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

            if (Test-Path -LiteralPath $BackupDir -ErrorAction SilentlyContinue) {
                $latestBackup = Get-ChildItem -LiteralPath $BackupDir -Filter "$($TargetFileName)_*.bak" -ErrorAction SilentlyContinue | 
                                Sort-Object LastWriteTime -Descending | 
                                Select-Object -First 1

                if ($latestBackup) {
                    Copy-Item -Path $latestBackup.FullName -Destination $TargetFilePath -Force
                    Write-Host "Restored configuration: $($latestBackup.Name) -> $TargetFileName" -ForegroundColor Green
                } else {
                    Write-Host "Notice: No backup found in $BackupFolder. Leaving existing $TargetFileName intact." -ForegroundColor Yellow
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

# ==========================================
# FRESH INSTALLATION
# ==========================================
Write-Host "`nStarting fresh installation..." -ForegroundColor Cyan

if ($SourceJar -and (Test-Path -LiteralPath $SourceJar -ErrorAction SilentlyContinue)) {
    Copy-Item -LiteralPath $SourceJar -Destination $InstalledJarPath -Force
    Write-Host "Installed: $JarFileName -> $InstallPath" -ForegroundColor Green
} else {
    Write-Host "Error: '$JarFileName' was not found in the installer directory ($ScriptDir)." -ForegroundColor Red
    return
}

Apply-PZOConfiguration
Write-Host "`n[SUCCESS] Installation & optimization complete! You can now start Project Zomboid." -ForegroundColor Cyan

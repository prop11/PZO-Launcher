<# :
@echo off
setlocal
set "PZO_INSTALLER_DIR=%~dp0"
cd /d "%~dp0"
powershell -NoProfile -ExecutionPolicy Bypass -Command "$env:PZO_INSTALLER_DIR='%~dp0'; iex ((Get-Content -LiteralPath '%~f0') -join [Environment]::NewLine)"
pause
exit /b
#>

# ==========================================
# 1. PROCESS CHECK (Graceful Exit if Running)
# ==========================================
$PzProcesses = @("ProjectZomboid64", "ProjectZomboid32", "Zombie", "ProjectZomboid")
$RunningProcess = Get-Process -Name $PzProcesses -ErrorAction SilentlyContinue

if ($RunningProcess) {
    Write-Host "`n========================================================" -ForegroundColor Red
    Write-Host " [!] Project Zomboid is currently running." -ForegroundColor Yellow
    Write-Host " Please close the game completely before running this tool." -ForegroundColor White
    Write-Host "========================================================`n" -ForegroundColor Red
    return
}

# ==========================================
# 2. CONFIGURATION & PATHS
# ==========================================
$ScriptDir = if ($env:PZO_INSTALLER_DIR) { $env:PZO_INSTALLER_DIR.TrimEnd('\') } elseif ($PSScriptRoot) { $PSScriptRoot } else { $PWD.Path }
$JarFileName     = "PZOptimEngine.jar"
$TargetFileName  = "ProjectZomboid64.json"
$BackupFolder    = "Installer_Backups"

# Resolve user's Zomboid Lua directory
$ZomboidLuaDir   = [System.IO.Path]::Combine($env:USERPROFILE, "Zomboid\Lua")
$PzoStatusFile   = [System.IO.Path]::Combine($ZomboidLuaDir, "pzo_status.json")

# ==========================================
# 3. STEAM & PZ PATH AUTO-DETECTION
# ==========================================
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
    Write-Host "Searching for Project Zomboid installation..." -ForegroundColor Cyan

    $steamPath = $null
    $regKeys = @(
        "HKCU:\Software\Valve\Steam",
        "HKLM:\SOFTWARE\Valve\Steam",
        "HKLM:\SOFTWARE\WOW6432Node\Valve\Steam"
    )

    foreach ($reg in $regKeys) {
        if (Test-Path $reg -ErrorAction SilentlyContinue) {
            $steamPath = (Get-ItemProperty -Path $reg -Name "SteamPath" -ErrorAction SilentlyContinue).SteamPath
            if (-not $steamPath) {
                $steamPath = (Get-ItemProperty -Path $reg -Name "InstallPath" -ErrorAction SilentlyContinue).InstallPath
            }
            if ($steamPath) { break }
        }
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

    # Add standard Steam library roots on all mounted ready drives
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

# Fallback GUI browser if auto-detection fails
if (-not $InstallPath) {
    Write-Host "Auto-detection could not locate Project Zomboid." -ForegroundColor Yellow
    Write-Host "Please select your 'ProjectZomboid' folder..." -ForegroundColor Yellow
    Add-Type -AssemblyName System.Windows.Forms
    $dialog = New-Object System.Windows.Forms.FolderBrowserDialog
    $dialog.Description = "Select your 'ProjectZomboid' install directory"
    $dialog.ShowNewFolderButton = $false

    if ($dialog.ShowDialog() -eq [System.Windows.Forms.DialogResult]::OK) {
        $InstallPath = $dialog.SelectedPath
    } else {
        Write-Host "`nOperation cancelled." -ForegroundColor Red
        return
    }
}

Write-Host "Project Zomboid directory: $InstallPath" -ForegroundColor Green

$InstalledJarPath = [System.IO.Path]::Combine($InstallPath, $JarFileName)
$TargetFilePath   = [System.IO.Path]::Combine($InstallPath, $TargetFileName)
$BackupDir        = [System.IO.Path]::Combine($InstallPath, $BackupFolder)

# Search for PZOptimEngine.jar across candidate local locations
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
    Write-Host "`n[Notice] '$JarFileName' not found locally next to Install.bat." -ForegroundColor Yellow
    $downloadTarget = [System.IO.Path]::Combine($ScriptDir, $JarFileName)
    if (Download-PZOGitHubJar $downloadTarget) {
        $SourceJar = $downloadTarget
    }
}

# ==========================================
# 3.5 ZOMBIEBUDDY CONFLICT DETECTION & CLEANUP
# ==========================================
$zbJar = [System.IO.Path]::Combine($InstallPath, "ZombieBuddy.jar")
$zbJarAlt = [System.IO.Path]::Combine($InstallPath, "zombiebuddy.jar")
$zbDll = [System.IO.Path]::Combine($InstallPath, "zbNative.dll")
$zbDll64 = [System.IO.Path]::Combine($InstallPath, "zbNative64.dll")
$zbJson = [System.IO.Path]::Combine($InstallPath, "zombiebuddy.json")

$hasZbInJson = $false
if (Test-Path -LiteralPath $TargetFilePath) {
    $rawJson = Get-Content -LiteralPath $TargetFilePath -Raw -ErrorAction SilentlyContinue
    if ($rawJson -and ($rawJson -match "ZombieBuddy" -or $rawJson -match "zbNative")) {
        $hasZbInJson = $true
    }
}

$isZombieBuddyInstalled = (Test-Path -LiteralPath $zbJar) -or (Test-Path -LiteralPath $zbJarAlt) -or (Test-Path -LiteralPath $zbDll) -or (Test-Path -LiteralPath $zbDll64) -or (Test-Path -LiteralPath $zbJson) -or $hasZbInJson

if ($isZombieBuddyInstalled) {
    Write-Host "`n========================================================================" -ForegroundColor Yellow
    Write-Host "[!] CONFLICT DETECTED: ZombieBuddy is currently installed" -ForegroundColor Yellow
    Write-Host "========================================================================" -ForegroundColor Yellow
    Write-Host "ZombieBuddy.jar and PZO Optimizer both manage the main Java engine"
    Write-Host "entrypoint and cannot run simultaneously.`n"
    Write-Host "Good news: PZO v0.4.1+ natively runs all your ZombieBuddy Workshop mods" -ForegroundColor Cyan
    Write-Host "automatically without needing ZombieBuddy.jar or zbNative.dll!`n" -ForegroundColor Cyan
    Write-Host "1) Uninstall ZombieBuddy & Continue Installation (Recommended)" -ForegroundColor Green
    Write-Host "2) Cancel Installation"
    
    $zbChoice = Read-Host "`nEnter choice (1 or 2)"
    if ($zbChoice -eq "1") {
        Write-Host "`nUninstalling ZombieBuddy..." -ForegroundColor Cyan
        $zbFiles = @($zbJar, $zbJarAlt, $zbDll, $zbDll64, $zbJson)
        foreach ($f in $zbFiles) {
            if (Test-Path -LiteralPath $f -ErrorAction SilentlyContinue) {
                Remove-Item -LiteralPath $f -Force -ErrorAction SilentlyContinue
                Write-Host "  Removed: $([System.IO.Path]::GetFileName($f))" -ForegroundColor Green
            }
        }
        Write-Host "ZombieBuddy has been cleanly removed. Proceeding with PZO installation...`n" -ForegroundColor Green
    } else {
        Write-Host "`nInstallation cancelled to preserve existing ZombieBuddy setup." -ForegroundColor Yellow
        return
    }
}

# ==========================================
# 4. EXISTING INSTALLATION CHECK (UPDATE / UNINSTALL)
# ==========================================
if (Test-Path $InstalledJarPath) {
    Write-Host "`n[!] PZOptimEngine is already installed." -ForegroundColor Yellow
    Write-Host "1) Update    - Overwrite PZOptimEngine.jar with the new version"
    Write-Host "2) Uninstall - Remove the mod and restore original settings"
    Write-Host "3) Cancel"
    
    $choice = Read-Host "`nEnter choice (1, 2, or 3)"

    switch ($choice) {
        "1" {
            Write-Host "`nUpdating PZOptimEngine.jar to latest version..." -ForegroundColor Cyan
            $updated = Download-PZOGitHubJar $InstalledJarPath
            if (-not $updated -and $SourceJar -and (Test-Path $SourceJar)) {
                Copy-Item -Path $SourceJar -Destination $InstalledJarPath -Force
                Write-Host "    [SUCCESS] Updated from local file: $JarFileName -> $InstalledJarPath" -ForegroundColor Green
                $updated = $true
            }
            if ($updated) {
                Write-Host "`n[SUCCESS] Update complete! Project Zomboid is ready." -ForegroundColor Green
            } else {
                Write-Host "`n[ERROR] Could not download or find $JarFileName to update." -ForegroundColor Red
            }
            return
        }
        "2" {
            Write-Host "`nUninstalling PZOptimEngine..." -ForegroundColor Cyan
            
            # 1. Remove JAR
            if (Test-Path $InstalledJarPath) {
                Remove-Item -Path $InstalledJarPath -Force
                Write-Host "Removed: $JarFileName" -ForegroundColor Green
            }

            # 2. Clean up all PZO bridge files from Zomboid/Lua
            $pzoFiles = @("pzo_status.json", "pzo_update.json", "pzo_telemetry.json", "pzo_engine.log")
            foreach ($pf in $pzoFiles) {
                $fullPf = [System.IO.Path]::Combine($ZomboidLuaDir, $pf)
                if (Test-Path -LiteralPath $fullPf -ErrorAction SilentlyContinue) {
                    Remove-Item -LiteralPath $fullPf -Force -ErrorAction SilentlyContinue
                    Write-Host "Removed: $pf" -ForegroundColor Green
                }
            }

            # 3. Restore latest JSON backup
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
# 5. FRESH INSTALLATION PATH
# ==========================================
Write-Host "`nStarting fresh installation..." -ForegroundColor Cyan

# Detect RAM
$TotalRamBytes = (Get-CimInstance Win32_ComputerSystem).TotalPhysicalMemory
$TotalRamGB    = [Math]::Round($TotalRamBytes / 1GB)
Write-Host "Detected System RAM: $TotalRamGB GB" -ForegroundColor Yellow

# Backup existing JSON
if (-not (Test-Path $BackupDir)) {
    New-Item -ItemType Directory -Path $BackupDir -Force | Out-Null
}

if (Test-Path $TargetFilePath) {
    $Timestamp = Get-Date -Format "yyyyMMdd_HHmmss"
    Copy-Item -Path $TargetFilePath -Destination "$BackupDir\$($TargetFileName)_$Timestamp.bak"
    Write-Host "Backed up original $TargetFileName -> $BackupFolder" -ForegroundColor Gray
}

# Copy JAR
if (Test-Path $SourceJar) {
    Copy-Item -Path $SourceJar -Destination $InstallPath -Force
    Write-Host "Installed: $JarFileName -> $InstallPath" -ForegroundColor Green
} else {
    Write-Host "Error: '$JarFileName' was not found in the installer directory ($ScriptDir)." -ForegroundColor Red
    return
}

# ==========================================
# 6. JSON TEMPLATES
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
        "-Djava.awt.headless=true",
        "--enable-native-access=ALL-UNNAMED",
        "--add-exports=java.base/jdk.internal.misc=ALL-UNNAMED",
        "-Xms2048m",
        "-Xmx4096m",
        "-Dzomboid.steam=1",
        "-Dzomboid.znetlog=1",
        "-Djava.library.path=win64/;.",
        "-XX:-CreateCoredumpOnCrash",
        "-XX:-OmitStackTraceInFastThrow",
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
        "-Djava.awt.headless=true",
        "--enable-native-access=ALL-UNNAMED",
        "--add-exports=java.base/jdk.internal.misc=ALL-UNNAMED",
        "-Xms3072m",
        "-Xmx6144m",
        "-Dzomboid.steam=1",
        "-Dzomboid.znetlog=1",
        "-Djava.library.path=win64/;.",
        "-XX:-CreateCoredumpOnCrash",
        "-XX:-OmitStackTraceInFastThrow",
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
        "-Djava.awt.headless=true",
        "--enable-native-access=ALL-UNNAMED",
        "--add-exports=java.base/jdk.internal.misc=ALL-UNNAMED",
        "-Xms4096m",
        "-Xmx8192m",
        "-Dzomboid.steam=1",
        "-Dzomboid.znetlog=1",
        "-Djava.library.path=win64/;.",
        "-XX:-CreateCoredumpOnCrash",
        "-XX:-OmitStackTraceInFastThrow",
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
        "-Djava.awt.headless=true",
        "--enable-native-access=ALL-UNNAMED",
        "--add-exports=java.base/jdk.internal.misc=ALL-UNNAMED",
        "-Xmx16384m",
        "-Dzomboid.steam=1",
        "-Dzomboid.znetlog=1",
        "-Djava.library.path=win64/;.",
        "-XX:-CreateCoredumpOnCrash",
        "-XX:-OmitStackTraceInFastThrow",
        "-XX:+UseG1GC",
        "-XX:InitiatingHeapOccupancyPercent=45",
        "-XX:G1ReservePercent=15",
        "-XX:+AlwaysPreTouch"
    ]
}
"@

# ==========================================
# 7. WRITE CONFIGURATION (ProjectZomboid64.json)
# ==========================================
$UseG1GC = $false

if ($TotalRamGB -le 5) {
    Write-Host "Applying profile: <= 5 GB RAM (Heap: 2048m - 4096m)" -ForegroundColor Green
    Set-Content -Path $TargetFilePath -Value $Json5OrLess
}
elseif ($TotalRamGB -ge 6 -and $TotalRamGB -le 12) {
    Write-Host "Applying profile: 6 - 12 GB RAM (Heap: 3072m - 6144m)" -ForegroundColor Green
    Set-Content -Path $TargetFilePath -Value $Json6To12
}
elseif ($TotalRamGB -ge 13 -and $TotalRamGB -le 20) {
    Write-Host "Applying profile: 13 - 20 GB RAM (Heap: 4096m - 8192m)" -ForegroundColor Green
    Set-Content -Path $TargetFilePath -Value $Json13To20
}
else {
    Write-Host "Applying profile: > 20 GB RAM (Heap: 16384m + G1GC Tuning)" -ForegroundColor Green
    Set-Content -Path $TargetFilePath -Value $JsonAbove20
    $UseG1GC = $true
}

# ==========================================
# 8. WRITE STATUS FILE (Zomboid/Lua/pzo_status.json)
# ==========================================
if (-not (Test-Path $ZomboidLuaDir)) {
    New-Item -ItemType Directory -Path $ZomboidLuaDir -Force | Out-Null
}

$StatusPayload = [ordered]@{
    optimized = $true
    ram_gb    = [int]$TotalRamGB
    g1gc      = $UseG1GC
    pretouch  = $true
}

$StatusJson = $StatusPayload | ConvertTo-Json -Compress
Set-Content -Path $PzoStatusFile -Value $StatusJson
Write-Host "Generated Lua bridge status: $PzoStatusFile" -ForegroundColor Green

Write-Host "`nInstallation & optimization complete! You can now start Project Zomboid." -ForegroundColor Cyan
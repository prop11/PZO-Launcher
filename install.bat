<# :
@echo off
setlocal
cd /d "%~dp0"
powershell -NoProfile -ExecutionPolicy Bypass -Command "iex ((Get-Content -LiteralPath '%~f0') -join [Environment]::NewLine)"
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
$ScriptDir       = $PWD.Path
$JarFileName     = "PZOptimEngine.jar"
$TargetFileName  = "ProjectZomboid64.json"
$BackupFolder    = "Installer_Backups"

# Resolve user's Zomboid Lua directory
$ZomboidLuaDir   = Join-Path $env:USERPROFILE "Zomboid\Lua"
$PzoStatusFile   = Join-Path $ZomboidLuaDir "pzo_status.json"

# ==========================================
# 3. STEAM & PZ PATH AUTO-DETECTION
# ==========================================
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
        $candidateLibraries.Add((Join-Path $steamPath "steamapps"))
        
        $vdfPath = Join-Path $steamPath "steamapps\libraryfolders.vdf"
        if (Test-Path -LiteralPath $vdfPath -ErrorAction SilentlyContinue) {
            $vdfContent = Get-Content -LiteralPath $vdfPath -ErrorAction SilentlyContinue
            if ($vdfContent) {
                foreach ($line in $vdfContent) {
                    if ($line -match '"path"\s+"([^"]+)"') {
                        $libPath = $matches[1] -replace '\\\\', '\'
                        $appsFolder = Join-Path $libPath "steamapps"
                        if (-not $candidateLibraries.Contains($appsFolder)) {
                            $candidateLibraries.Add($appsFolder)
                        }
                    }
                }
            }
        }
    }

    # Add standard Steam library roots (A-Z)
    $drives = [System.IO.DriveInfo]::GetDrives() | Where-Object { $_.IsReady } | ForEach-Object { $_.Name }
    foreach ($drv in $drives) {
        $candidateLibraries.Add((Join-Path $drv "SteamLibrary\steamapps"))
        $candidateLibraries.Add((Join-Path $drv "Program Files (x86)\Steam\steamapps"))
        $candidateLibraries.Add((Join-Path $drv "Program Files\Steam\steamapps"))
    }

    foreach ($lib in $candidateLibraries) {
        if (-not [string]::IsNullOrWhiteSpace($lib)) {
            $driveRoot = [System.IO.Path]::GetPathRoot($lib)
            if ($driveRoot -and (Test-Path -LiteralPath $driveRoot -ErrorAction SilentlyContinue)) {
                $pzPath = Join-Path $lib "common\ProjectZomboid"
                $exePath = Join-Path $pzPath "ProjectZomboid64.exe"
                if (Test-Path -LiteralPath $exePath -ErrorAction SilentlyContinue) {
                    return $pzPath
                }
            }
        }
    }

    $gogPath = "C:\GOG Games\Project Zomboid"
    if (Test-Path -LiteralPath (Join-Path $gogPath "ProjectZomboid64.exe") -ErrorAction SilentlyContinue) {
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

$InstalledJarPath = Join-Path $InstallPath $JarFileName
$TargetFilePath   = Join-Path $InstallPath $TargetFileName
$BackupDir        = Join-Path $InstallPath $BackupFolder
$SourceJar        = Join-Path $ScriptDir $JarFileName
if (-not (Test-Path -LiteralPath $SourceJar -ErrorAction SilentlyContinue)) {
    $altJar = Join-Path $ScriptDir "dist\$JarFileName"
    if (Test-Path -LiteralPath $altJar -ErrorAction SilentlyContinue) {
        $SourceJar = $altJar
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
            Write-Host "`nUpdating PZOptimEngine.jar..." -ForegroundColor Cyan
            if (Test-Path $SourceJar) {
                Copy-Item -Path $SourceJar -Destination $InstallPath -Force
                Write-Host "Updated: $JarFileName -> $InstallPath" -ForegroundColor Green
            } else {
                Write-Host "Error: '$JarFileName' not found next to Install.bat." -ForegroundColor Red
            }
            Write-Host "`nUpdate complete!" -ForegroundColor Cyan
            return
        }
        "2" {
            Write-Host "`nUninstalling PZOptimEngine..." -ForegroundColor Cyan
            
            # 1. Remove JAR
            if (Test-Path $InstalledJarPath) {
                Remove-Item -Path $InstalledJarPath -Force
                Write-Host "Removed: $JarFileName" -ForegroundColor Green
            }

            # 2. Remove pzo_status.json from Zomboid/Lua
            if (Test-Path $PzoStatusFile) {
                Remove-Item -Path $PzoStatusFile -Force
                Write-Host "Removed: $PzoStatusFile" -ForegroundColor Green
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
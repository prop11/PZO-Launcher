@echo off
setlocal
set "PZO_DOWNGRADER_DIR=%~dp0"
title Project Zomboid Build 42 - PZO Version Downgrader

where powershell >nul 2>nul
if %errorlevel% neq 0 (
    echo [ERROR] PowerShell was not found on your system.
    echo Please install Windows PowerShell to run this utility.
    pause
    exit /b 1
)

powershell -NoProfile -ExecutionPolicy Bypass -Command "$f = Get-Content -LiteralPath '%~f0'; $start = 0; for ($i=0; $i -lt $f.Length; $i++) { if ($f[$i] -eq '# __START_POWERSHELL__') { $start = $i + 1; break } }; $ps = ($f[$start..($f.Length - 1)]) -join [Environment]::NewLine; [ScriptBlock]::Create($ps).Invoke()"
pause
exit /b %errorlevel%

# __START_POWERSHELL__
# PZO Version Downgrader for Windows

Write-Host "=================================================================" -ForegroundColor Cyan
Write-Host " Project Zomboid Build 42 Engine Optimizer (PZO)" -ForegroundColor Cyan
Write-Host " Version Downgrader Utility" -ForegroundColor Cyan
Write-Host "=================================================================" -ForegroundColor Cyan

$ScriptDir       = if ($env:PZO_DOWNGRADER_DIR) { $env:PZO_DOWNGRADER_DIR.TrimEnd('\') } else { $PWD.Path }
$JarFileName     = "PZOptimEngine.jar"
$NativeDllName   = "pzo_native64.dll"
$TargetFileName  = "ProjectZomboid64.json"
$BackupFolder    = "Installer_Backups"
$ZomboidLuaDir   = [System.IO.Path]::Combine($HOME, "Zomboid\Lua")
$PzoStatusFile   = [System.IO.Path]::Combine($ZomboidLuaDir, "pzo_status.json")

# Guard against running game process
$runningPZ = Get-Process -Name "ProjectZomboid64", "ProjectZomboid32" -ErrorAction SilentlyContinue
if ($runningPZ) {
    Write-Host "`n[!] Warning: Project Zomboid is currently running." -ForegroundColor Yellow
    Write-Host "    Please close Project Zomboid to prevent file permission locks." -ForegroundColor Yellow
    Write-Host "    Press Enter once the game is closed to continue..." -ForegroundColor Gray
    Read-Host
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
        Write-Host "Operation cancelled by user." -ForegroundColor Red
        return
    }
}

Write-Host "Project Zomboid directory: $InstallPath" -ForegroundColor Green

$InstalledJarPath = [System.IO.Path]::Combine($InstallPath, $JarFileName)
$InstalledDllPath = [System.IO.Path]::Combine($InstallPath, $NativeDllName)
$TargetFilePath   = [System.IO.Path]::Combine($InstallPath, $TargetFileName)
$BackupDir        = [System.IO.Path]::Combine($InstallPath, $BackupFolder)

# Detect currently installed version
$CurrentVersion = "Unknown"
if (Test-Path -LiteralPath $PzoStatusFile) {
    try {
        $statusJson = Get-Content -LiteralPath $PzoStatusFile -Raw -ErrorAction SilentlyContinue | ConvertFrom-Json
        if ($statusJson.version) {
            $CurrentVersion = [string]$statusJson.version
        }
    } catch {}
}

if ($CurrentVersion -eq "Unknown" -and (Test-Path -LiteralPath $InstalledJarPath)) {
    try {
        Add-Type -AssemblyName System.IO.Compression.FileSystem
        $zip = [System.IO.Compression.ZipFile]::OpenRead($InstalledJarPath)
        $entry = $zip.GetEntry("META-INF/MANIFEST.MF")
        if ($entry) {
            $stream = $entry.Open()
            $reader = New-Object System.IO.StreamReader($stream)
            $text = $reader.ReadToEnd()
            $reader.Close()
            $stream.Close()
            if ($text -match "Implementation-Version:\s*([^\r\n]+)") {
                $CurrentVersion = $matches[1].Trim()
            }
        }
        $zip.Dispose()
    } catch {}
}

Write-Host "`nCurrently installed PZO version: $CurrentVersion" -ForegroundColor Yellow

# Query GitHub Releases
Write-Host "Fetching available releases from GitHub..." -ForegroundColor Cyan
[Net.ServicePointManager]::SecurityProtocol = [Net.SecurityProtocolType]::Tls12 -bor [Net.SecurityProtocolType]::Tls13

$releases = $null
try {
    $headers = @{ "User-Agent" = "PZO-Downgrader" }
    $releases = Invoke-RestMethod -Uri "https://api.github.com/repos/prop11/PZO-Launcher/releases?per_page=30" -Headers $headers -TimeoutSec 15
} catch {
    Write-Host "[ERROR] Failed to query releases from GitHub: $($_.Exception.Message)" -ForegroundColor Red
    return
}

if (-not $releases -or $releases.Count -eq 0) {
    Write-Host "[ERROR] No releases found on GitHub repository." -ForegroundColor Red
    return
}

$cleanCurrent = $CurrentVersion.TrimStart('v','V').Trim().ToLower()

$eligibleReleases = @()
foreach ($rel in $releases) {
    if ($rel.draft -or $rel.prerelease) { continue }
    $tag = [string]$rel.tag_name
    if ($tag -match "(?i)unstable|beta|alpha|rc") { continue }
    
    $cleanTag = $tag.TrimStart('v','V').Trim().ToLower()
    if ($cleanCurrent -ne "unknown" -and $cleanTag -eq $cleanCurrent) {
        continue
    }

    $eligibleReleases += $rel
    if ($eligibleReleases.Count -ge 5) { break }
}

if ($eligibleReleases.Count -eq 0) {
    Write-Host "[!] No previous stable versions available to downgrade to." -ForegroundColor Yellow
    return
}

Write-Host "`nAvailable stable versions to downgrade to:" -ForegroundColor Cyan
for ($i = 0; $i -lt $eligibleReleases.Count; $i++) {
    $rel = $eligibleReleases[$i]
    $dateStr = if ($rel.published_at) { ([DateTime]$rel.published_at).ToString("yyyy-MM-dd") } else { "Unknown" }
    Write-Host ("  [{0}] {1,-10} (Published: {2})" -f ($i + 1), $rel.tag_name, $dateStr) -ForegroundColor White
}
Write-Host "  [C] Cancel" -ForegroundColor Gray

$selection = Read-Host "`nEnter selection (1-$($eligibleReleases.Count) or C to cancel)"
if ([string]::IsNullOrWhiteSpace($selection) -or $selection.Trim().ToUpper() -eq "C") {
    Write-Host "Operation cancelled." -ForegroundColor Yellow
    return
}

$choiceIndex = -1
if (-not [int]::TryParse($selection.Trim(), [ref]$choiceIndex) -or $choiceIndex -lt 1 -or $choiceIndex -gt $eligibleReleases.Count) {
    Write-Host "Invalid selection. Exiting." -ForegroundColor Red
    return
}

$chosenRelease = $eligibleReleases[$choiceIndex - 1]
$targetTag = $chosenRelease.tag_name
$cleanTargetVersion = $targetTag.TrimStart('v','V').Trim()

Write-Host "`nInitiating downgrade to $targetTag..." -ForegroundColor Cyan

# Prepare backup folder
$Timestamp = Get-Date -Format "yyyyMMdd_HHmmss"
$DowngradeBackup = [System.IO.Path]::Combine($BackupDir, "Downgrade_$Timestamp")
if (-not (Test-Path -LiteralPath $DowngradeBackup)) {
    New-Item -ItemType Directory -Path $DowngradeBackup -Force | Out-Null
}

# Backup current installed files
if (Test-Path -LiteralPath $InstalledJarPath) {
    Copy-Item -LiteralPath $InstalledJarPath -Destination (Join-Path $DowngradeBackup $JarFileName) -Force
    Write-Host "[+] Backed up current $JarFileName" -ForegroundColor Gray
}
if (Test-Path -LiteralPath $InstalledDllPath) {
    Copy-Item -LiteralPath $InstalledDllPath -Destination (Join-Path $DowngradeBackup $NativeDllName) -Force
    Write-Host "[+] Backed up current $NativeDllName" -ForegroundColor Gray
}
if (Test-Path -LiteralPath $TargetFilePath) {
    Copy-Item -LiteralPath $TargetFilePath -Destination (Join-Path $DowngradeBackup $TargetFileName) -Force
    Write-Host "[+] Backed up current $TargetFileName" -ForegroundColor Gray
}

# Download files from chosen release
function Download-Asset($tag, $fileName, $destPath) {
    $url = "https://github.com/prop11/PZO-Launcher/releases/download/$tag/$fileName"
    Write-Host "Downloading $fileName ($tag)..." -ForegroundColor Cyan
    Write-Host "URL: $url" -ForegroundColor Gray
    try {
        $webClient = New-Object System.Net.WebClient
        $webClient.Headers.Add("User-Agent", "PZO-Downgrader")
        $webClient.DownloadFile($url, $destPath)
        if (Test-Path -LiteralPath $destPath) {
            $sizeKB = [Math]::Round((Get-Item -LiteralPath $destPath).Length / 1KB, 1)
            Write-Host "[SUCCESS] Downloaded $fileName ($sizeKB KB)" -ForegroundColor Green
            return $true
        }
    } catch {
        Write-Host "[ERROR] Download failed: $($_.Exception.Message)" -ForegroundColor Red
    }
    return $false
}

$jarSuccess = Download-Asset $targetTag $JarFileName $InstalledJarPath
if (-not $jarSuccess) {
    Write-Host "`n[ERROR] Failed to download $JarFileName for version $targetTag." -ForegroundColor Red
    Write-Host "Restoring backup..." -ForegroundColor Yellow
    if (Test-Path (Join-Path $DowngradeBackup $JarFileName)) {
        Copy-Item (Join-Path $DowngradeBackup $JarFileName) $InstalledJarPath -Force
    }
    return
}

$dllSuccess = Download-Asset $targetTag $NativeDllName $InstalledDllPath
if ($dllSuccess) {
    $win64Dir = [System.IO.Path]::Combine($InstallPath, "win64")
    if (Test-Path -LiteralPath $win64Dir) {
        $win64Dll = [System.IO.Path]::Combine($win64Dir, $NativeDllName)
        Copy-Item -LiteralPath $InstalledDllPath -Destination $win64Dll -Force
        Write-Host "[+] Mirrored $NativeDllName -> win64 directory" -ForegroundColor Gray
    }
} else {
    Write-Host "[WARNING] Native library ($NativeDllName) download was unavailable for $targetTag." -ForegroundColor Yellow
}

# Update Lua bridge status file
try {
    if (-not (Test-Path -LiteralPath $ZomboidLuaDir)) {
        New-Item -ItemType Directory -Path $ZomboidLuaDir -Force | Out-Null
    }
    $statusObj = [ordered]@{
        optimized   = $true
        version     = $cleanTargetVersion
        downgraded  = $true
        target_tag  = $targetTag
    }
    $jsonContent = $statusObj | ConvertTo-Json -Compress
    $utf8NoBom = New-Object System.Text.UTF8Encoding($false)
    [System.IO.File]::WriteAllText($PzoStatusFile, $jsonContent, $utf8NoBom)
    Write-Host "[+] Updated PZO status file: $PzoStatusFile" -ForegroundColor Gray
} catch {}

Write-Host "`n=================================================================" -ForegroundColor Green
Write-Host " [SUCCESS] PZO successfully downgraded to $targetTag!" -ForegroundColor Green
Write-Host "=================================================================" -ForegroundColor Green
Write-Host "Location: $InstallPath" -ForegroundColor White
Write-Host "PZO Jar:  $InstalledJarPath" -ForegroundColor White
Write-Host "Backups:  $DowngradeBackup`n" -ForegroundColor Gray

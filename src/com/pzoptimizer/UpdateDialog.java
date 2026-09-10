package com.pzoptimizer;

import java.io.*;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.List;

/**
 * Project Zomboid Build 42 - Pre-Menu Native Interactive Update Prompt & Self-Updater.
 * Multi-Platform: Windows (WinForms), macOS (Cocoa/AppleScript), Linux & Steam Deck (Zenity/KDialog).
 */
public class UpdateDialog {
    private static final String CONFIG_FILE = "pzo_config.json";

    public static boolean promptIfUpdateAvailable(String latestVersion, String downloadUrl) {
        return promptIfUpdateAvailable(latestVersion, downloadUrl, null);
    }

    public static boolean promptIfUpdateAvailable(String latestVersion, String downloadUrl, String dllDownloadUrl) {
        if (latestVersion == null || isVersionIgnored(latestVersion)) {
            return false;
        }

        try {
            String os = System.getProperty("os.name", "").toLowerCase();
            String result = "SKIP";

            if (os.contains("win")) {
                result = showWindowsDialog(latestVersion);
            } else if (os.contains("mac")) {
                result = showMacDialog(latestVersion);
            } else {
                result = showLinuxDialog(latestVersion);
            }

            if ("UPDATE".equalsIgnoreCase(result)) {
                performAutoUpdateAndExit(latestVersion, downloadUrl, dllDownloadUrl);
                return true;
            } else if ("SKIP_IGNORE".equalsIgnoreCase(result)) {
                setIgnoredVersion(latestVersion);
            }
        } catch (Throwable t) {
            PZOLogger.error("UpdateDialog launch error: " + t.getMessage(), t);
        }
        return false;
    }

    /**
     * Checks if a native governor library exists in the game folder and verifies whether its
     * version matches the Java mod version. If missing, mismatched, or outdated, prompts the user to download.
     */
    public static boolean checkAndPromptNativeMismatch(String dllDownloadUrl) {
        boolean filePresent = PZONative.isNativeFilePresent();
        String installedVersion = PZONative.getInstalledNativeVersion();
        String expectedVersion = UpdateChecker.CURRENT_VERSION;

        // If file is present and versions match, native library is up-to-date and fully compatible
        if (filePresent && expectedVersion.equalsIgnoreCase(installedVersion)) {
            return false;
        }

        String ignoreKey = "native_" + expectedVersion;
        if (isVersionIgnored(ignoreKey)) {
            return false;
        }

        boolean isMissing = !filePresent || "Not Installed (Missing)".equalsIgnoreCase(installedVersion);
        if (isMissing) {
            PZOLogger.warn(String.format(
                "[UpdateDialog] Native governor binary not found in game folder: Expected [%s] (%s). Prompting user to download...",
                expectedVersion, PZONative.NATIVE_LIB_FILENAME
            ));
        } else {
            PZOLogger.warn(String.format(
                "[UpdateDialog] Native governor mismatch: Installed [%s], Expected [%s]. Prompting user to update...",
                installedVersion, expectedVersion
            ));
        }

        if (dllDownloadUrl == null || dllDownloadUrl.isEmpty() || dllDownloadUrl.contains("/releases/latest/")) {
            dllDownloadUrl = UpdateChecker.resolveNativeDownloadUrl(expectedVersion, 2500);
        }

        try {
            String os = System.getProperty("os.name", "").toLowerCase();
            String result = "SKIP";

            if (os.contains("win")) {
                result = showWindowsNativeMismatchDialog(installedVersion, expectedVersion);
            } else if (os.contains("mac")) {
                result = showMacNativeMismatchDialog(installedVersion, expectedVersion);
            } else {
                result = showLinuxNativeMismatchDialog(installedVersion, expectedVersion);
            }

            if ("UPDATE".equalsIgnoreCase(result)) {
                performNativeAutoUpdateAndExit(expectedVersion, dllDownloadUrl);
                return true;
            } else if ("SKIP_IGNORE".equalsIgnoreCase(result)) {
                setIgnoredVersion(ignoreKey);
            }
        } catch (Throwable t) {
            PZOLogger.error("Native update dialog launch error: " + t.getMessage(), t);
        }
        return false;
    }

    private static String showWindowsDialog(String latestVersion) {
        try {
            String psCode = String.format(
                "[void][System.Reflection.Assembly]::LoadWithPartialName('System.Windows.Forms');" +
                "[void][System.Reflection.Assembly]::LoadWithPartialName('System.Drawing');" +
                "$nl=[Environment]::NewLine;" +
                "$f=New-Object Windows.Forms.Form;" +
                "$f.Text='Project Zomboid Optimiser - Update Available';" +
                "$f.Size=New-Object Drawing.Size(480,240);" +
                "$f.StartPosition='CenterScreen';" +
                "$f.FormBorderStyle='FixedDialog';" +
                "$f.MaximizeBox=$false;$f.MinimizeBox=$false;$f.TopMost=$true;" +
                "$f.BackColor=[Drawing.Color]::FromArgb(30,30,30);" +
                "$f.ForeColor=[Drawing.Color]::White;" +
                "$t=New-Object Windows.Forms.Label;" +
                "$t.Text='[!] Project Zomboid Optimiser Update Available';" +
                "$t.Font=New-Object Drawing.Font('Segoe UI',12,[Drawing.FontStyle]::Bold);" +
                "$t.ForeColor=[Drawing.Color]::FromArgb(80,220,100);" +
                "$t.Location=New-Object Drawing.Point(20,15);$t.Size=New-Object Drawing.Size(430,25);$f.Controls.Add($t);" +
                "$i=New-Object Windows.Forms.Label;" +
                "$i.Text=('A newer release of PZOptimEngine is ready.' + $nl + $nl + 'Installed Version: v%s' + $nl + 'Latest Available: v%s');" +
                "$i.Font=New-Object Drawing.Font('Segoe UI',10);$i.ForeColor=[Drawing.Color]::FromArgb(220,220,220);" +
                "$i.Location=New-Object Drawing.Point(20,48);$i.Size=New-Object Drawing.Size(430,70);$f.Controls.Add($i);" +
                "$cb=New-Object Windows.Forms.CheckBox;" +
                "$cb.Text='Don''t remind me again for version %s';" +
                "$cb.Font=New-Object Drawing.Font('Segoe UI',9);$cb.ForeColor=[Drawing.Color]::FromArgb(170,170,170);" +
                "$cb.Location=New-Object Drawing.Point(23,120);$cb.Size=New-Object Drawing.Size(350,25);$f.Controls.Add($cb);" +
                "$bu=New-Object Windows.Forms.Button;$bu.Text='Update Now';" +
                "$bu.Font=New-Object Drawing.Font('Segoe UI',9,[Drawing.FontStyle]::Bold);$bu.BackColor=[Drawing.Color]::FromArgb(40,167,69);$bu.ForeColor=[Drawing.Color]::White;$bu.FlatStyle='Flat';" +
                "$bu.Location=New-Object Drawing.Point(345,155);$bu.Size=New-Object Drawing.Size(105,32);$bu.DialogResult=[Windows.Forms.DialogResult]::Yes;$f.Controls.Add($bu);" +
                "$bs=New-Object Windows.Forms.Button;$bs.Text='Skip / Launch Game';" +
                "$bs.Font=New-Object Drawing.Font('Segoe UI',9);$bs.BackColor=[Drawing.Color]::FromArgb(65,65,65);$bs.ForeColor=[Drawing.Color]::White;$bs.FlatStyle='Flat';" +
                "$bs.Location=New-Object Drawing.Point(195,155);$bs.Size=New-Object Drawing.Size(140,32);$bs.DialogResult=[Windows.Forms.DialogResult]::No;$f.Controls.Add($bs);" +
                "$f.AcceptButton=$bu;$r=$f.ShowDialog();" +
                "if($r -eq [Windows.Forms.DialogResult]::Yes){Write-Output 'UPDATE'}else{if($cb.Checked){Write-Output 'SKIP_IGNORE'}else{Write-Output 'SKIP'}}",
                UpdateChecker.CURRENT_VERSION, latestVersion, latestVersion
            );

            ProcessBuilder pb = new ProcessBuilder("powershell.exe", "-NoProfile", "-NonInteractive", "-WindowStyle", "Hidden", "-Command", psCode);
            Process p = pb.start();
            try (BufferedReader reader = new BufferedReader(new InputStreamReader(p.getInputStream()))) {
                String line;
                while ((line = reader.readLine()) != null) {
                    line = line.trim();
                    if ("UPDATE".equalsIgnoreCase(line) || "SKIP_IGNORE".equalsIgnoreCase(line) || "SKIP".equalsIgnoreCase(line)) {
                        return line;
                    }
                }
            }
            p.waitFor();
        } catch (Throwable t) {
            PZOLogger.error("Windows update dialog error: " + t.getMessage(), t);
        }
        return "SKIP";
    }

    private static String showMacDialog(String latestVersion) {
        try {
            String script = String.format(
                "set r to button returned of (display dialog \"[!] A newer release of Project Zomboid Optimiser is ready.\\n\\nInstalled Version: v%s\\nLatest Available: v%s\" " +
                "with title \"PZO Engine - Update Available\" buttons {\"Don't Remind Me\", \"Skip\", \"Update Now\"} default button \"Update Now\" with icon note)\n" +
                "return r",
                UpdateChecker.CURRENT_VERSION, latestVersion
            );
            Process p = new ProcessBuilder("osascript", "-e", script).start();
            try (BufferedReader reader = new BufferedReader(new InputStreamReader(p.getInputStream()))) {
                String out = reader.readLine();
                if (out != null) {
                    if (out.contains("Update Now")) return "UPDATE";
                    if (out.contains("Don't Remind Me")) return "SKIP_IGNORE";
                }
            }
            p.waitFor();
        } catch (Throwable ignored) {}
        return "SKIP";
    }

    private static String showLinuxDialog(String latestVersion) {
        try {
            Process p = new ProcessBuilder("zenity", "--question",
                "--title=PZO Engine - Update Available",
                "--text=[!] A newer release of Project Zomboid Optimiser is ready.\n\nInstalled Version: v" + UpdateChecker.CURRENT_VERSION + "\nLatest Available: v" + latestVersion + "\n\nWould you like to update now?",
                "--ok-label=Update Now", "--cancel-label=Skip / Launch Game",
                "--extra-button=Don't Remind Me").start();
            
            try (BufferedReader reader = new BufferedReader(new InputStreamReader(p.getInputStream()))) {
                String extra = reader.readLine();
                if (extra != null && extra.contains("Don't Remind Me")) {
                    return "SKIP_IGNORE";
                }
            }
            if (p.waitFor() == 0) return "UPDATE";
            return "SKIP";
        } catch (Throwable ignored) {}

        try {
            Process p = new ProcessBuilder("kdialog",
                "--title", "PZO Engine - Update Available",
                "--yesno", "[!] A newer release of Project Zomboid Optimiser is ready.\n\nInstalled Version: v" + UpdateChecker.CURRENT_VERSION + "\nLatest Available: v" + latestVersion + "\n\nWould you like to update now?",
                "--yes-label", "Update Now", "--no-label", "Skip / Launch Game").start();
            if (p.waitFor() == 0) return "UPDATE";
            return "SKIP";
        } catch (Throwable ignored) {}

        return "SKIP";
    }

    private static String showWindowsNativeMismatchDialog(String installedVersion, String expectedVersion) {
        try {
            String nativeFileName = PZONative.NATIVE_LIB_FILENAME;
            boolean isMissing = !PZONative.isNativeFilePresent() || installedVersion.contains("Missing") || installedVersion.contains("Not Installed");
            String formTitle = isMissing ? "Project Zomboid Optimiser - Native Library Setup" : "Project Zomboid Optimiser - Native Library Update Required";
            String titleText = isMissing ? "[!] Native Governor Library Missing" : "[!] Native Governor Library Mismatch Detected";
            String btnText = isMissing ? "Install Now" : "Update Now";
            String descText = isMissing
                ? String.format("No %s was detected in your game folder." +
                    "$nl$nlInstalling this native companion library unlocks 0.5ms timer locking, AVX2 SIMD culling, and kernel thread optimization." +
                    "$nl$nlStatus: %s$nlRequired for PZO Engine: v%s", nativeFileName, installedVersion, expectedVersion)
                : String.format("An outdated or mismatched %s was detected in your game folder." +
                    "$nl$nlInstalled DLL: %s$nlRequired for PZO Engine: v%s", nativeFileName, installedVersion, expectedVersion);

            String psCode = String.format(
                "[void][System.Reflection.Assembly]::LoadWithPartialName('System.Windows.Forms');" +
                "[void][System.Reflection.Assembly]::LoadWithPartialName('System.Drawing');" +
                "$nl=[Environment]::NewLine;" +
                "$f=New-Object Windows.Forms.Form;" +
                "$f.Text='%s';" +
                "$f.Size=New-Object Drawing.Size(530,280);" +
                "$f.StartPosition='CenterScreen';" +
                "$f.FormBorderStyle='FixedDialog';" +
                "$f.MaximizeBox=$false;$f.MinimizeBox=$false;$f.TopMost=$true;" +
                "$f.BackColor=[Drawing.Color]::FromArgb(30,30,30);" +
                "$f.ForeColor=[Drawing.Color]::White;" +
                "$t=New-Object Windows.Forms.Label;" +
                "$t.Text='%s';" +
                "$t.Font=New-Object Drawing.Font('Segoe UI',12,[Drawing.FontStyle]::Bold);" +
                "$t.ForeColor=[Drawing.Color]::FromArgb(255,185,50);" +
                "$t.Location=New-Object Drawing.Point(20,15);$t.Size=New-Object Drawing.Size(480,25);$f.Controls.Add($t);" +
                "$i=New-Object Windows.Forms.Label;" +
                "$i.Text=('%s');" +
                "$i.Font=New-Object Drawing.Font('Segoe UI',9.5);$i.ForeColor=[Drawing.Color]::FromArgb(220,220,220);" +
                "$i.Location=New-Object Drawing.Point(20,48);$i.Size=New-Object Drawing.Size(480,95);$f.Controls.Add($i);" +
                "$cb=New-Object Windows.Forms.CheckBox;" +
                "$cb.Text='Don''t remind me again for this version';" +
                "$cb.Font=New-Object Drawing.Font('Segoe UI',9);$cb.ForeColor=[Drawing.Color]::FromArgb(170,170,170);" +
                "$cb.Location=New-Object Drawing.Point(23,155);$cb.Size=New-Object Drawing.Size(350,25);$f.Controls.Add($cb);" +
                "$bu=New-Object Windows.Forms.Button;$bu.Text='%s';" +
                "$bu.Font=New-Object Drawing.Font('Segoe UI',9,[Drawing.FontStyle]::Bold);$bu.BackColor=[Drawing.Color]::FromArgb(40,167,69);$bu.ForeColor=[Drawing.Color]::White;$bu.FlatStyle='Flat';" +
                "$bu.Location=New-Object Drawing.Point(375,190);$bu.Size=New-Object Drawing.Size(125,32);$bu.DialogResult=[Windows.Forms.DialogResult]::Yes;$f.Controls.Add($bu);" +
                "$bs=New-Object Windows.Forms.Button;$bs.Text='Skip / Launch Game';" +
                "$bs.Font=New-Object Drawing.Font('Segoe UI',9);$bs.BackColor=[Drawing.Color]::FromArgb(65,65,65);$bs.ForeColor=[Drawing.Color]::White;$bs.FlatStyle='Flat';" +
                "$bs.Location=New-Object Drawing.Point(225,190);$bs.Size=New-Object Drawing.Size(140,32);$bs.DialogResult=[Windows.Forms.DialogResult]::No;$f.Controls.Add($bs);" +
                "$f.AcceptButton=$bu;$r=$f.ShowDialog();" +
                "if($r -eq [Windows.Forms.DialogResult]::Yes){Write-Output 'UPDATE'}else{if($cb.Checked){Write-Output 'SKIP_IGNORE'}else{Write-Output 'SKIP'}}",
                formTitle, titleText, descText.replace("'", "''"), btnText
            );

            ProcessBuilder pb = new ProcessBuilder("powershell.exe", "-NoProfile", "-NonInteractive", "-WindowStyle", "Hidden", "-Command", psCode);
            Process p = pb.start();
            try (BufferedReader reader = new BufferedReader(new InputStreamReader(p.getInputStream()))) {
                String line;
                while ((line = reader.readLine()) != null) {
                    line = line.trim();
                    if ("UPDATE".equalsIgnoreCase(line) || "SKIP_IGNORE".equalsIgnoreCase(line) || "SKIP".equalsIgnoreCase(line)) {
                        return line;
                    }
                }
            }
            p.waitFor();
        } catch (Throwable t) {
            PZOLogger.error("Windows native update dialog error: " + t.getMessage(), t);
        }
        return "SKIP";
    }

    private static String showMacNativeMismatchDialog(String installedVersion, String expectedVersion) {
        try {
            boolean isMissing = !PZONative.isNativeFilePresent() || installedVersion.contains("Missing") || installedVersion.contains("Not Installed");
            String btnText = isMissing ? "Install Now" : "Update Now";
            String promptText = isMissing
                ? String.format("[!] Native Governor Library Missing.\\n\\nNo %s was detected in your game bundle.\\n\\nInstalling this companion unlocks high-precision timer locking, AVX2 SIMD math, and kernel thread optimization.\\n\\nStatus: %s\\nRequired for PZO Engine: v%s\\n\\nWould you like to download and install %s now?",
                    PZONative.NATIVE_LIB_FILENAME, installedVersion, expectedVersion, PZONative.NATIVE_LIB_FILENAME)
                : String.format("[!] Native Governor Library Mismatch Detected.\\n\\nInstalled: %s\\nRequired for PZO Engine: v%s\\n\\nWould you like to update %s now?",
                    installedVersion, expectedVersion, PZONative.NATIVE_LIB_FILENAME);

            String script = String.format(
                "set r to button returned of (display dialog \"%s\" " +
                "with title \"PZO Engine - Native Library %s\" buttons {\"Don't Remind Me\", \"Skip\", \"%s\"} default button \"%s\" with icon caution)\n" +
                "return r",
                promptText, isMissing ? "Setup" : "Update", btnText, btnText
            );
            ProcessBuilder pb = new ProcessBuilder("osascript", "-e", script);
            Process p = pb.start();
            try (BufferedReader reader = new BufferedReader(new InputStreamReader(p.getInputStream()))) {
                String line = reader.readLine();
                if (line != null) {
                    line = line.trim();
                    if (btnText.equalsIgnoreCase(line) || "Update Now".equalsIgnoreCase(line) || "Install Now".equalsIgnoreCase(line)) return "UPDATE";
                    if ("Don't Remind Me".equalsIgnoreCase(line)) return "SKIP_IGNORE";
                }
            }
            p.waitFor();
        } catch (Throwable t) {
            PZOLogger.error("Mac native update dialog error: " + t.getMessage(), t);
        }
        return "SKIP";
    }

    private static String showLinuxNativeMismatchDialog(String installedVersion, String expectedVersion) {
        try {
            boolean isMissing = !PZONative.isNativeFilePresent() || installedVersion.contains("Missing") || installedVersion.contains("Not Installed");
            String btnText = isMissing ? "Install Now" : "Update Now";
            String text = isMissing
                ? String.format("Native Governor Library Missing!\n\nNo %s was detected in your game folder.\n\nInstalling this companion unlocks high-precision timer locking, AVX2 SIMD batch culling, and kernel thread priority.\n\nStatus: %s\nRequired for PZO Engine: v%s\n\nWould you like to download and install %s now?",
                    PZONative.NATIVE_LIB_FILENAME, installedVersion, expectedVersion, PZONative.NATIVE_LIB_FILENAME)
                : String.format("Native Governor Library Mismatch Detected!\n\nInstalled: %s\nRequired for PZO Engine: v%s\n\nWould you like to update %s now?",
                    installedVersion, expectedVersion, PZONative.NATIVE_LIB_FILENAME);

            ProcessBuilder pb = new ProcessBuilder("zenity", "--question", "--title=PZO Engine - Native Library " + (isMissing ? "Setup" : "Update"),
                "--text=" + text, "--ok-label=" + btnText, "--cancel-label=Skip / Launch Game", "--extra-button=Don't Remind Me");
            Process p = pb.start();
            try (BufferedReader reader = new BufferedReader(new InputStreamReader(p.getInputStream()))) {
                String extra = reader.readLine();
                if (extra != null && extra.contains("Don't Remind Me")) {
                    return "SKIP_IGNORE";
                }
            }
            if (p.waitFor() == 0) return "UPDATE";
        } catch (Throwable ignored) {
            try {
                boolean isMissing = !PZONative.isNativeFilePresent() || installedVersion.contains("Missing") || installedVersion.contains("Not Installed");
                String btnText = isMissing ? "Install Now" : "Update Now";
                String text = isMissing
                    ? String.format("Native Governor Library Missing!\n\nNo %s was detected in your game folder.\n\nStatus: %s\nRequired for PZO Engine: v%s\n\nWould you like to download and install %s now?",
                        PZONative.NATIVE_LIB_FILENAME, installedVersion, expectedVersion, PZONative.NATIVE_LIB_FILENAME)
                    : String.format("Native Governor Library Mismatch Detected!\n\nInstalled: %s\nRequired for PZO Engine: v%s\n\nWould you like to update %s now?",
                        installedVersion, expectedVersion, PZONative.NATIVE_LIB_FILENAME);

                ProcessBuilder pb = new ProcessBuilder("kdialog", "--title", "PZO Engine - Native Library " + (isMissing ? "Setup" : "Update"),
                    "--yesno", text, "--yes-label", btnText, "--no-label", "Skip / Launch Game");
                Process p = pb.start();
                if (p.waitFor() == 0) return "UPDATE";
            } catch (Throwable ignored2) {}
        }
        return "SKIP";
    }

    private static void performNativeAutoUpdateAndExit(String targetVersion, String dllDownloadUrl) {
        try {
            File currentJar = null;
            try {
                currentJar = new File(UpdateDialog.class.getProtectionDomain().getCodeSource().getLocation().toURI());
            } catch (Throwable ignored) {}

            if (currentJar == null || !currentJar.exists()) {
                currentJar = new File("PZOptimEngine.jar").getAbsoluteFile();
            }

            File gameDir = currentJar.getParentFile();
            if (gameDir == null) {
                gameDir = new File(".").getAbsoluteFile();
            }

            String nativeFileName = PZONative.NATIVE_LIB_FILENAME;
            File currentNative = new File(gameDir, nativeFileName);
            File newNative = new File(gameDir, nativeFileName + ".new");

            List<String> candidateUrls = new ArrayList<>();
            if (dllDownloadUrl != null && !dllDownloadUrl.isEmpty() && !dllDownloadUrl.contains("/releases/latest/")) {
                candidateUrls.add(dllDownloadUrl);
            }
            String resolved = UpdateChecker.resolveNativeDownloadUrl(targetVersion, 2500);
            if (resolved != null && !candidateUrls.contains(resolved)) {
                candidateUrls.add(resolved);
            }
            String cleanVer = targetVersion.trim();
            String vTag = cleanVer.startsWith("v") || cleanVer.startsWith("V") ? cleanVer : "V" + cleanVer;
            String directUrl = "https://github.com/prop11/PZO-Launcher/releases/download/" + vTag + "/" + nativeFileName;
            if (!candidateUrls.contains(directUrl)) {
                candidateUrls.add(directUrl);
            }
            String lowerVTag = "v" + cleanVer.replaceFirst("^[vV]", "");
            String directLowerUrl = "https://github.com/prop11/PZO-Launcher/releases/download/" + lowerVTag + "/" + nativeFileName;
            if (!candidateUrls.contains(directLowerUrl)) {
                candidateUrls.add(directLowerUrl);
            }
            String rawTagUrl = "https://github.com/prop11/PZO-Launcher/releases/download/" + cleanVer + "/" + nativeFileName;
            if (!candidateUrls.contains(rawTagUrl)) {
                candidateUrls.add(rawTagUrl);
            }

            boolean downloaded = false;
            for (String tryUrl : candidateUrls) {
                PZOLogger.info("Attempting native library download from: " + tryUrl);
                if (downloadFileWithRedirects(tryUrl, newNative) && newNative.exists() && newNative.length() > 1000) {
                    downloaded = true;
                    PZOLogger.success("Successfully downloaded " + newNative.length() + " bytes to " + newNative.getAbsolutePath() + " from: " + tryUrl);
                    break;
                } else {
                    if (newNative.exists()) newNative.delete();
                }
            }

            if (!downloaded || !newNative.exists() || newNative.length() < 1000) {
                if (newNative.exists()) newNative.delete();
                PZOLogger.error("Failed to download native library from all candidate sources.");
                showNoticePopup("Update Notice", "Could not download " + nativeFileName + " from GitHub Releases.\nPlease run install.bat to update manually.");
                return;
            }

            PZOLogger.success("Downloaded " + newNative.length() + " bytes to " + newNative.getAbsolutePath());

            String os = System.getProperty("os.name", "").toLowerCase();
            long pid = ProcessHandle.current().pid();
            File win64Dll = new File(gameDir, "win64" + File.separator + nativeFileName);

            File currentJson = new File(gameDir, ZomboidConfigMigrator.TARGET_JSON_NAME);
            File newJson = new File(gameDir, ZomboidConfigMigrator.TARGET_JSON_NAME + ".new");
            boolean hasNewJson = ZomboidConfigMigrator.prepareStagedUpdate(gameDir) && newJson.exists();

            if (os.contains("win")) {
                String psJsonUpdate = hasNewJson ? String.format(
                    "if (Test-Path -LiteralPath '%s') { " +
                    "    for ($i=0; $i -lt 30; $i++) { " +
                    "        try { Move-Item -LiteralPath '%s' -Destination '%s' -Force -ErrorAction Stop; break; } " +
                    "        catch { Start-Sleep -Milliseconds 200; } " +
                    "    } " +
                    "}; ",
                    newJson.getAbsolutePath().replace("'", "''"),
                    newJson.getAbsolutePath().replace("'", "''"),
                    currentJson.getAbsolutePath().replace("'", "''")
                ) : "";

                String psUpdater = String.format(
                    "$proc = Get-Process -Id %d -ErrorAction SilentlyContinue; " +
                    "if ($proc) { $proc.WaitForExit(15000); }; " +
                    "Start-Sleep -Milliseconds 500; " +
                    "if (Test-Path -LiteralPath '%s') { " +
                    "    if (Test-Path -LiteralPath '%s') { " +
                    "        for ($i=0; $i -lt 30; $i++) { " +
                    "            try { Copy-Item -LiteralPath '%s' -Destination '%s' -Force -ErrorAction Stop; break; } " +
                    "            catch { Start-Sleep -Milliseconds 200; } " +
                    "        } " +
                    "    }; " +
                    "    for ($i=0; $i -lt 30; $i++) { " +
                    "        try { Move-Item -LiteralPath '%s' -Destination '%s' -Force -ErrorAction Stop; break; } " +
                    "        catch { Start-Sleep -Milliseconds 200; } " +
                    "    } " +
                    "}; " +
                    "%s" +
                    "$nl=[Environment]::NewLine; " +
                    "[void][System.Reflection.Assembly]::LoadWithPartialName('System.Windows.Forms'); " +
                    "[Windows.Forms.MessageBox]::Show(('Native Governor (%s) and configuration have been successfully updated to v%s!' + $nl + $nl + 'Please restart Project Zomboid to apply native optimizations.'), 'Setup Complete', [Windows.Forms.MessageBoxButtons]::OK, [Windows.Forms.MessageBoxIcon]::Information)",
                    pid,
                    newNative.getAbsolutePath().replace("'", "''"),
                    win64Dll.getParentFile().getAbsolutePath().replace("'", "''"),
                    newNative.getAbsolutePath().replace("'", "''"),
                    win64Dll.getAbsolutePath().replace("'", "''"),
                    newNative.getAbsolutePath().replace("'", "''"),
                    currentNative.getAbsolutePath().replace("'", "''"),
                    psJsonUpdate,
                    nativeFileName,
                    targetVersion
                );
                new ProcessBuilder("powershell.exe", "-NoProfile", "-WindowStyle", "Hidden", "-Command", psUpdater).start();
            } else if (os.contains("mac")) {
                String macJsonUpdate = hasNewJson ? String.format(
                    " && (test -f \"%s\" && mv -f \"%s\" \"%s\" || true)",
                    newJson.getAbsolutePath(), newJson.getAbsolutePath(), currentJson.getAbsolutePath()
                ) : "";
                String macNativeUpdate = String.format(
                    " && cp -f \"%s\" \"%s\" && (test -d \"ProjectZomboid.app/Contents/Java\" && cp -f \"%s\" \"ProjectZomboid.app/Contents/Java/%s\" || true) && (test -d \"ProjectZomboid.app/Contents/MacOS\" && cp -f \"%s\" \"ProjectZomboid.app/Contents/MacOS/%s\" || true) && rm -f \"%s\"",
                    newNative.getAbsolutePath(), currentNative.getAbsolutePath(),
                    newNative.getAbsolutePath(), nativeFileName,
                    newNative.getAbsolutePath(), nativeFileName,
                    newNative.getAbsolutePath()
                );
                String shUpdater = String.format(
                    "while kill -0 %d 2>/dev/null; do sleep 0.2; done; sleep 0.5%s%s && osascript -e 'display notification \"Native Governor (%s) has been successfully installed/updated to v%s! Please restart Project Zomboid.\" with title \"Setup Complete\"'",
                    pid, macNativeUpdate, macJsonUpdate, nativeFileName, targetVersion
                );
                new ProcessBuilder("bash", "-c", shUpdater).start();
            } else {
                String linuxJsonUpdate = hasNewJson ? String.format(
                    " && (test -f \"%s\" && mv -f \"%s\" \"%s\" || true)",
                    newJson.getAbsolutePath(), newJson.getAbsolutePath(), currentJson.getAbsolutePath()
                ) : "";
                String linuxNativeUpdate = String.format(
                    " && cp -f \"%s\" \"%s\" && (test -d \"linux64\" && cp -f \"%s\" \"linux64/%s\" || true) && (test -d \"natives\" && cp -f \"%s\" \"natives/%s\" || true) && rm -f \"%s\"",
                    newNative.getAbsolutePath(), currentNative.getAbsolutePath(),
                    newNative.getAbsolutePath(), nativeFileName,
                    newNative.getAbsolutePath(), nativeFileName,
                    newNative.getAbsolutePath()
                );
                String shUpdater = String.format(
                    "while kill -0 %d 2>/dev/null; do sleep 0.2; done; sleep 0.5%s%s && (kdialog --msgbox \"Native Governor (%s) has been successfully installed/updated to v%s!\\n\\nPlease restart Project Zomboid.\" || zenity --info --text=\"Native Governor (%s) has been successfully installed/updated to v%s!\\n\\nPlease restart Project Zomboid.\" || notify-send \"PZO Native Setup Complete\" \"Please restart Project Zomboid.\")",
                    pid, linuxNativeUpdate, linuxJsonUpdate, nativeFileName, targetVersion, nativeFileName, targetVersion
                );
                new ProcessBuilder("bash", "-c", shUpdater).start();
            }

            PZOLogger.info("Exiting game process to allow atomic native library replacement...");
            System.exit(0);

        } catch (Throwable t) {
            PZOLogger.error("Native auto-update failed: " + t.getMessage(), t);
            showNoticePopup("Update Notice", "Native update error: " + t.getMessage() + "\nPlease update manually using install.bat / pzo_optimizer.sh.");
        }
    }

    private static void performAutoUpdateAndExit(String latestVersion, String downloadUrl, String dllDownloadUrl) {
        try {
            if (downloadUrl == null || downloadUrl.isEmpty()) {
                downloadUrl = "https://github.com/prop11/PZO-Launcher/releases/latest/download/PZOptimEngine.jar";
            }
            File currentJar = null;
            try {
                currentJar = new File(UpdateDialog.class.getProtectionDomain().getCodeSource().getLocation().toURI());
            } catch (Throwable ignored) {}

            if (currentJar == null || !currentJar.exists()) {
                currentJar = new File("PZOptimEngine.jar").getAbsoluteFile();
            }

            File gameDir = currentJar.getParentFile();
            if (gameDir == null) {
                gameDir = new File(".").getAbsoluteFile();
            }

            currentJar = new File(gameDir, "PZOptimEngine.jar");
            File newJar = new File(gameDir, "PZOptimEngine.jar.new");

            String cleanVer = latestVersion != null ? latestVersion.trim() : UpdateChecker.CURRENT_VERSION;
            String vTag = cleanVer.startsWith("v") || cleanVer.startsWith("V") ? cleanVer : "V" + cleanVer;

            String os = System.getProperty("os.name", "").toLowerCase();
            boolean isWin = os.contains("win");
            boolean isMac = os.contains("mac") || os.contains("darwin");
            String nativeFileName = isWin ? "pzo_native64.dll" : (isMac ? "libpzo_native64.dylib" : "libpzo_native64.so");

            File currentNative = new File(gameDir, nativeFileName);
            File newNative = new File(gameDir, nativeFileName + ".new");
            File win64Dll = new File(gameDir, "win64" + File.separator + nativeFileName);
            boolean nativeInstalled = PZONative.isNativeFilePresent() || currentNative.exists() || win64Dll.exists();
            boolean hasNewNative = false;

            // Step 1: Download matching native companion library first if native governor is installed or supported
            List<String> nativeCandidates = new ArrayList<>();
            if (dllDownloadUrl != null && !dllDownloadUrl.isEmpty() && !dllDownloadUrl.contains("/releases/latest/")) {
                nativeCandidates.add(dllDownloadUrl);
            }
            String resolvedNative = UpdateChecker.resolveNativeDownloadUrl(latestVersion, 2500);
            if (resolvedNative != null && !nativeCandidates.contains(resolvedNative)) {
                nativeCandidates.add(resolvedNative);
            }
            String directNativeUrl = "https://github.com/prop11/PZO-Launcher/releases/download/" + vTag + "/" + nativeFileName;
            if (!nativeCandidates.contains(directNativeUrl)) {
                nativeCandidates.add(directNativeUrl);
            }
            String directNativeLowerUrl = "https://github.com/prop11/PZO-Launcher/releases/download/v" + cleanVer.replaceFirst("^[vV]", "") + "/" + nativeFileName;
            if (!nativeCandidates.contains(directNativeLowerUrl)) {
                nativeCandidates.add(directNativeLowerUrl);
            }

            for (String tryNative : nativeCandidates) {
                PZOLogger.info("Downloading matching " + nativeFileName + " for v" + latestVersion + " from: " + tryNative);
                if (downloadFileWithRedirects(tryNative, newNative) && newNative.exists() && newNative.length() > 5000) {
                    hasNewNative = true;
                    PZOLogger.success("Downloaded " + newNative.length() + " bytes to " + newNative.getAbsolutePath());
                    break;
                } else {
                    if (newNative.exists()) newNative.delete();
                }
            }

            // CRITICAL: If native governor is installed on user's system, we MUST NOT perform a partial update!
            if (nativeInstalled && !hasNewNative) {
                if (newNative.exists()) newNative.delete();
                PZOLogger.error("Failed to download matching native library (" + nativeFileName + ") for version " + latestVersion + ". Aborting update to avoid library mismatch.");
                showNoticePopup("Update Notice", "Could not download the matching " + nativeFileName + " for version " + latestVersion + " from GitHub Releases.\n\nTo prevent version mismatch errors, the update has been cancelled. Please check your internet connection or update manually.");
                return;
            }

            // Step 2: Download PZOptimEngine.jar
            List<String> jarCandidates = new ArrayList<>();
            if (downloadUrl != null && !downloadUrl.isEmpty() && !downloadUrl.contains("/releases/latest/")) {
                jarCandidates.add(downloadUrl);
            }
            jarCandidates.add("https://github.com/prop11/PZO-Launcher/releases/download/" + vTag + "/PZOptimEngine.jar");
            jarCandidates.add("https://github.com/prop11/PZO-Launcher/releases/download/v" + cleanVer.replaceFirst("^[vV]", "") + "/PZOptimEngine.jar");
            jarCandidates.add("https://github.com/prop11/PZO-Launcher/releases/download/" + cleanVer + "/PZOptimEngine.jar");
            jarCandidates.add("https://github.com/prop11/PZO-Launcher/releases/latest/download/PZOptimEngine.jar");

            boolean jarOk = false;
            for (String tryJar : jarCandidates) {
                PZOLogger.info("Downloading latest PZOptimEngine.jar from: " + tryJar);
                if (downloadFileWithRedirects(tryJar, newJar) && newJar.exists() && newJar.length() > 10000) {
                    jarOk = true;
                    PZOLogger.success("Downloaded " + newJar.length() + " bytes to " + newJar.getAbsolutePath());
                    break;
                } else {
                    if (newJar.exists()) newJar.delete();
                }
            }

            if (!jarOk || !newJar.exists() || newJar.length() < 10000) {
                if (newNative.exists()) newNative.delete();
                showNoticePopup("Update Notice", "Automatic download failed for PZOptimEngine.jar. You can update manually using install.bat.");
                return;
            }

            long pid = ProcessHandle.current().pid();

            File currentJson = new File(gameDir, ZomboidConfigMigrator.TARGET_JSON_NAME);
            File newJson = new File(gameDir, ZomboidConfigMigrator.TARGET_JSON_NAME + ".new");
            boolean hasNewJson = ZomboidConfigMigrator.prepareStagedUpdate(gameDir) && newJson.exists();

            if (isWin) {
                String psJsonUpdate = hasNewJson ? String.format(
                    "if (Test-Path -LiteralPath '%s') { " +
                    "    for ($i=0; $i -lt 30; $i++) { " +
                    "        try { Move-Item -LiteralPath '%s' -Destination '%s' -Force -ErrorAction Stop; break; } " +
                    "        catch { Start-Sleep -Milliseconds 200; } " +
                    "    } " +
                    "}; ",
                    newJson.getAbsolutePath().replace("'", "''"),
                    newJson.getAbsolutePath().replace("'", "''"),
                    currentJson.getAbsolutePath().replace("'", "''")
                ) : "";

                String psDllUpdate = hasNewNative ? String.format(
                    "if (Test-Path -LiteralPath '%s') { " +
                    "    if (Test-Path -LiteralPath '%s') { " +
                    "        for ($i=0; $i -lt 30; $i++) { " +
                    "            try { Copy-Item -LiteralPath '%s' -Destination '%s' -Force -ErrorAction Stop; break; } " +
                    "            catch { Start-Sleep -Milliseconds 200; } " +
                    "        } " +
                    "    }; " +
                    "    for ($i=0; $i -lt 30; $i++) { " +
                    "        try { Move-Item -LiteralPath '%s' -Destination '%s' -Force -ErrorAction Stop; break; } " +
                    "        catch { Start-Sleep -Milliseconds 200; } " +
                    "    } " +
                    "}; ",
                    newNative.getAbsolutePath().replace("'", "''"),
                    win64Dll.getParentFile().getAbsolutePath().replace("'", "''"),
                    newNative.getAbsolutePath().replace("'", "''"),
                    win64Dll.getAbsolutePath().replace("'", "''"),
                    newNative.getAbsolutePath().replace("'", "''"),
                    currentNative.getAbsolutePath().replace("'", "''")
                ) : "";

                String msgTitle = hasNewNative ? "PZO Engine & Native Governor have been updated to v%s!" : "PZO Engine has been updated to v%s!";
                String psUpdater = String.format(
                    "$proc = Get-Process -Id %d -ErrorAction SilentlyContinue; " +
                    "if ($proc) { $proc.WaitForExit(15000); }; " +
                    "Start-Sleep -Milliseconds 500; " +
                    "if (Test-Path -LiteralPath '%s') { " +
                    "    for ($i=0; $i -lt 30; $i++) { " +
                    "        try { Move-Item -LiteralPath '%s' -Destination '%s' -Force -ErrorAction Stop; break; } " +
                    "        catch { Start-Sleep -Milliseconds 200; } " +
                    "    } " +
                    "}; " +
                    "%s" +
                    "%s" +
                    "$nl=[Environment]::NewLine; " +
                    "[void][System.Reflection.Assembly]::LoadWithPartialName('System.Windows.Forms'); " +
                    "[Windows.Forms.MessageBox]::Show(('" + msgTitle + "' + $nl + $nl + 'Please restart Project Zomboid to load the new build.'), 'Update Complete', [Windows.Forms.MessageBoxButtons]::OK, [Windows.Forms.MessageBoxIcon]::Information)",
                    pid,
                    newJar.getAbsolutePath().replace("'", "''"),
                    newJar.getAbsolutePath().replace("'", "''"),
                    currentJar.getAbsolutePath().replace("'", "''"),
                    psDllUpdate,
                    psJsonUpdate,
                    latestVersion
                );
                new ProcessBuilder("powershell.exe", "-NoProfile", "-WindowStyle", "Hidden", "-Command", psUpdater).start();
            } else if (isMac) {
                String macJsonUpdate = hasNewJson ? String.format(
                    " && (test -f \"%s\" && mv -f \"%s\" \"%s\" || true)",
                    newJson.getAbsolutePath(), newJson.getAbsolutePath(), currentJson.getAbsolutePath()
                ) : "";

                String macNativeUpdate = hasNewNative ? String.format(
                    " && cp -f \"%s\" \"%s\" && (test -d \"ProjectZomboid.app/Contents/Java\" && cp -f \"%s\" \"ProjectZomboid.app/Contents/Java/%s\" || true) && (test -d \"ProjectZomboid.app/Contents/MacOS\" && cp -f \"%s\" \"ProjectZomboid.app/Contents/MacOS/%s\" || true) && rm -f \"%s\"",
                    newNative.getAbsolutePath(), currentNative.getAbsolutePath(),
                    newNative.getAbsolutePath(), nativeFileName,
                    newNative.getAbsolutePath(), nativeFileName,
                    newNative.getAbsolutePath()
                ) : "";

                String shUpdater = String.format(
                    "while kill -0 %d 2>/dev/null; do sleep 0.2; done; sleep 0.5 && mv -f \"%s\" \"%s\"%s%s && osascript -e 'display notification \"PZO Engine & Native Governor have been updated to v%s! Please restart Project Zomboid.\" with title \"Update Complete\"'",
                    pid, newJar.getAbsolutePath(), currentJar.getAbsolutePath(), macNativeUpdate, macJsonUpdate, latestVersion
                );
                new ProcessBuilder("bash", "-c", shUpdater).start();
            } else {
                String linuxJsonUpdate = hasNewJson ? String.format(
                    " && (test -f \"%s\" && mv -f \"%s\" \"%s\" || true)",
                    newJson.getAbsolutePath(), newJson.getAbsolutePath(), currentJson.getAbsolutePath()
                ) : "";

                String linuxNativeUpdate = hasNewNative ? String.format(
                    " && cp -f \"%s\" \"%s\" && (test -d \"linux64\" && cp -f \"%s\" \"linux64/%s\" || true) && (test -d \"natives\" && cp -f \"%s\" \"natives/%s\" || true) && rm -f \"%s\"",
                    newNative.getAbsolutePath(), currentNative.getAbsolutePath(),
                    newNative.getAbsolutePath(), nativeFileName,
                    newNative.getAbsolutePath(), nativeFileName,
                    newNative.getAbsolutePath()
                ) : "";

                String shUpdater = String.format(
                    "while kill -0 %d 2>/dev/null; do sleep 0.2; done; sleep 0.5 && mv -f \"%s\" \"%s\"%s%s && (kdialog --msgbox \"PZO Engine & Native Governor have been updated to v%s!\\n\\nPlease restart Project Zomboid.\" || zenity --info --text=\"PZO Engine & Native Governor have been updated to v%s!\\n\\nPlease restart Project Zomboid.\" || notify-send \"PZO Engine Updated\" \"Please restart Project Zomboid.\")",
                    pid, newJar.getAbsolutePath(), currentJar.getAbsolutePath(), linuxNativeUpdate, linuxJsonUpdate, latestVersion, latestVersion
                );
                new ProcessBuilder("bash", "-c", shUpdater).start();
            }

            PZOLogger.info("Exiting game process to allow atomic file replacement...");
            System.exit(0);

        } catch (Throwable t) {
            PZOLogger.error("Auto-update failed: " + t.getMessage(), t);
            showNoticePopup("Update Notice", "Download error: " + t.getMessage() + "\nPlease update manually using install.bat / pzo_optimizer.sh.");
        }
    }

    private static boolean downloadFileWithRedirects(String initialUrl, File targetFile) {
        String currentUrl = initialUrl;
        int maxRedirects = 7;
        for (int i = 0; i < maxRedirects; i++) {
            HttpURLConnection conn = null;
            try {
                URL url = new URL(currentUrl);
                conn = (HttpURLConnection) url.openConnection();
                conn.setRequestProperty("User-Agent", "PZO-UpdateClient");
                conn.setRequestProperty("Accept", "*/*");
                conn.setConnectTimeout(10000);
                conn.setReadTimeout(60000);
                conn.setInstanceFollowRedirects(false);

                int code = conn.getResponseCode();
                if (code == HttpURLConnection.HTTP_MOVED_PERM || code == HttpURLConnection.HTTP_MOVED_TEMP
                    || code == HttpURLConnection.HTTP_SEE_OTHER || code == 307 || code == 308) {
                    String location = conn.getHeaderField("Location");
                    if (location != null && !location.isEmpty()) {
                        URL base = new URL(currentUrl);
                        URL next = new URL(base, location);
                        currentUrl = next.toExternalForm();
                        conn.disconnect();
                        continue;
                    }
                }

                if (code == HttpURLConnection.HTTP_OK) {
                    try (InputStream in = conn.getInputStream();
                         FileOutputStream out = new FileOutputStream(targetFile)) {
                        byte[] buffer = new byte[32768];
                        int bytesRead;
                        while ((bytesRead = in.read(buffer)) != -1) {
                            out.write(buffer, 0, bytesRead);
                        }
                        out.flush();
                    }
                    return targetFile.exists() && targetFile.length() > 0;
                } else {
                    PZOLogger.warn("Download HTTP status " + code + " from: " + currentUrl);
                    return false;
                }
            } catch (Throwable t) {
                PZOLogger.warn("Download attempt error for " + currentUrl + ": " + t.getMessage());
                return false;
            } finally {
                if (conn != null) {
                    try { conn.disconnect(); } catch (Throwable ignored) {}
                }
            }
        }
        return false;
    }

    private static void showNoticePopup(String title, String message) {
        try {
            String os = System.getProperty("os.name", "").toLowerCase();
            if (os.contains("win")) {
                String ps = String.format(
                    "$nl=[Environment]::NewLine;" +
                    "[void][System.Reflection.Assembly]::LoadWithPartialName('System.Windows.Forms');" +
                    "[Windows.Forms.MessageBox]::Show('%s'.Replace('`n', $nl), '%s', [Windows.Forms.MessageBoxButtons]::OK, [Windows.Forms.MessageBoxIcon]::Information)",
                    message.replace("'", "''"), title
                );
                new ProcessBuilder("powershell.exe", "-NoProfile", "-Command", ps).start().waitFor();
            } else if (os.contains("mac")) {
                String script = String.format(
                    "display dialog \"%s\" with title \"%s\" buttons {\"OK\"} default button \"OK\"",
                    message.replace("\n", "\\n"), title
                );
                new ProcessBuilder("osascript", "-e", script).start().waitFor();
            } else {
                new ProcessBuilder("zenity", "--info", "--title=" + title, "--text=" + message).start().waitFor();
            }
        } catch (Throwable ignored) {}
    }

    private static boolean isVersionIgnored(String version) {
        return PZOConfig.isVersionIgnored(version);
    }

    private static void setIgnoredVersion(String version) {
        PZOConfig.setIgnoredVersion(version);
    }
}

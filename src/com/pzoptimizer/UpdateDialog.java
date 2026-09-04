package com.pzoptimizer;

import java.io.*;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.file.Files;

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
        // 1. Try Zenity (GNOME, Ubuntu, Mint, Pop!_OS)
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

        // 2. Try KDialog (Steam Deck / KDE Plasma default)
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

    private static void performAutoUpdateAndExit(String latestVersion, String downloadUrl, String dllDownloadUrl) {
        try {
            if (downloadUrl == null || downloadUrl.isEmpty()) {
                downloadUrl = "https://github.com/prop11/PZO-Launcher/releases/latest/download/PZOptimEngine.jar";
            }
            File currentJar = new File("PZOptimEngine.jar").getAbsoluteFile();
            File newJar = new File("PZOptimEngine.jar.new").getAbsoluteFile();

            PZOLogger.info("Downloading latest PZOptimEngine.jar from: " + downloadUrl);
            boolean jarOk = downloadFileWithRedirects(downloadUrl, newJar);
            if (!jarOk || newJar.length() < 10000) {
                showNoticePopup("Update Notice", "Automatic download failed for PZOptimEngine.jar. You can update manually using install.bat.");
                return;
            }
            PZOLogger.success("Downloaded " + newJar.length() + " bytes to " + newJar.getAbsolutePath());

            // Handle Native Companion DLL (pzo_native64.dll)
            String os = System.getProperty("os.name", "").toLowerCase();
            File currentDll = new File("pzo_native64.dll").getAbsoluteFile();
            File newDll = new File("pzo_native64.dll.new").getAbsoluteFile();
            File win64Dll = new File("win64" + File.separator + "pzo_native64.dll").getAbsoluteFile();
            boolean hasNewDll = false;

            if (os.contains("win")) {
                if (dllDownloadUrl == null || dllDownloadUrl.isEmpty()) {
                    dllDownloadUrl = "https://github.com/prop11/PZO-Launcher/releases/latest/download/pzo_native64.dll";
                }
                PZOLogger.info("Downloading latest pzo_native64.dll from: " + dllDownloadUrl);
                boolean dllOk = downloadFileWithRedirects(dllDownloadUrl, newDll);
                if (dllOk && newDll.length() > 50000) {
                    hasNewDll = true;
                    PZOLogger.success("Downloaded " + newDll.length() + " bytes to " + newDll.getAbsolutePath());
                } else {
                    PZOLogger.warn("Notice: pzo_native64.dll could not be downloaded; proceeding with JAR update.");
                }
            }

            if (os.contains("win")) {
                String psDllUpdate = hasNewDll ? String.format(
                    "if (Test-Path -LiteralPath '%s') { Move-Item -LiteralPath '%s' -Destination '%s' -Force; }; " +
                    "if (Test-Path -LiteralPath '%s') { Copy-Item -LiteralPath '%s' -Destination '%s' -Force; }; ",
                    newDll.getAbsolutePath().replace("'", "''"),
                    newDll.getAbsolutePath().replace("'", "''"),
                    currentDll.getAbsolutePath().replace("'", "''"),
                    win64Dll.getParentFile().getAbsolutePath().replace("'", "''"),
                    currentDll.getAbsolutePath().replace("'", "''"),
                    win64Dll.getAbsolutePath().replace("'", "''")
                ) : "";

                String psUpdater = String.format(
                    "Start-Sleep -Milliseconds 800; " +
                    "Move-Item -LiteralPath '%s' -Destination '%s' -Force; " +
                    "%s" +
                    "$nl=[Environment]::NewLine; " +
                    "[void][System.Reflection.Assembly]::LoadWithPartialName('System.Windows.Forms'); " +
                    "[Windows.Forms.MessageBox]::Show(('PZO Engine & Native Governor have been updated to v%s!' + $nl + $nl + 'Please restart Project Zomboid to load the new build.'), 'Update Complete', [Windows.Forms.MessageBoxButtons]::OK, [Windows.Forms.MessageBoxIcon]::Information)",
                    newJar.getAbsolutePath().replace("'", "''"),
                    currentJar.getAbsolutePath().replace("'", "''"),
                    psDllUpdate,
                    latestVersion
                );
                new ProcessBuilder("powershell.exe", "-NoProfile", "-WindowStyle", "Hidden", "-Command", psUpdater).start();
            } else if (os.contains("mac")) {
                String shUpdater = String.format(
                    "sleep 1 && mv -f \"%s\" \"%s\" && osascript -e 'display notification \"PZO Engine has been updated to v%s! Please restart Project Zomboid.\" with title \"Update Complete\"'",
                    newJar.getAbsolutePath(), currentJar.getAbsolutePath(), latestVersion
                );
                new ProcessBuilder("bash", "-c", shUpdater).start();
            } else {
                // Linux & Steam Deck
                String shUpdater = String.format(
                    "sleep 1 && mv -f \"%s\" \"%s\" && (kdialog --msgbox \"PZO Engine has been updated to v%s!\\n\\nPlease restart Project Zomboid.\" || zenity --info --text=\"PZO Engine has been updated to v%s!\\n\\nPlease restart Project Zomboid.\" || notify-send \"PZO Engine Updated\" \"Please restart Project Zomboid.\")",
                    newJar.getAbsolutePath(), currentJar.getAbsolutePath(), latestVersion, latestVersion
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

    private static boolean downloadFileWithRedirects(String fileUrl, File targetFile) {
        try {
            URL url = new URL(fileUrl);
            HttpURLConnection conn = (HttpURLConnection) url.openConnection();
            conn.setRequestProperty("User-Agent", "PZO-UpdateClient");
            conn.setConnectTimeout(8000);
            conn.setReadTimeout(30000);
            conn.setInstanceFollowRedirects(true);

            int code = conn.getResponseCode();
            if (code == HttpURLConnection.HTTP_MOVED_PERM || code == HttpURLConnection.HTTP_MOVED_TEMP || code == 307 || code == 308) {
                String newUrl = conn.getHeaderField("Location");
                conn = (HttpURLConnection) new URL(newUrl).openConnection();
                conn.setRequestProperty("User-Agent", "PZO-UpdateClient");
                conn.setConnectTimeout(8000);
                conn.setReadTimeout(30000);
            }

            try (InputStream in = conn.getInputStream();
                 FileOutputStream out = new FileOutputStream(targetFile)) {
                byte[] buffer = new byte[16384];
                int bytesRead;
                while ((bytesRead = in.read(buffer)) != -1) {
                    out.write(buffer, 0, bytesRead);
                }
            }
            return targetFile.exists() && targetFile.length() > 0;
        } catch (Throwable t) {
            PZOLogger.warn("Download error for " + fileUrl + ": " + t.getMessage());
            return false;
        }
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

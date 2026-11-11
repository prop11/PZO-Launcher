package com.pzoptimizer;

import java.io.File;
import java.util.List;

/**
 * The window shown at launch when the last session didn't close cleanly: what the logs say caused it, and buttons to
 * make the report file, get help on Discord, or go back to an older PZO version.
 *
 * Nothing here runs on a normal launch beyond CrashFlag's small file. The window is a WinForms dialog in a PowerShell
 * child process like the update prompt, Windows only for now; other systems get the diagnosis in pzo_engine.log.
 * It closes itself after five minutes so an unattended launch still reaches the game.
 */
final class CrashWindow {

    static final String DISCORD_URL = "https://discord.gg/pxG8ZKYARD";
    static final String ISSUES_URL = "https://github.com/prop11/PZO-Launcher/issues";

    private static final int AUTO_CLOSE_MS = 300_000;
    private static final long PROCESS_TIMEOUT_SEC = 330;
    private static final int MAX_ROUNDS = 8;

    private CrashWindow() {}

    static void check(CrashFlag.Status st) {
        if (st == null || !st.crashed) return;
        File zomboid = PZOEngineBridge.getZomboidDir();
        // main() runs before the game reopens console.txt, so it still holds the crashed session
        Diagnoser.Result r = Diagnoser.diagnose(new File(zomboid, "console.txt"),
            new File(zomboid, "Lua" + File.separator + PZOLogger.PREVIOUS_LOG_NAME), st.crashLogs);
        PZOLogger.warn("[PZO] The last session did not close cleanly. Diagnosis: " + r.headline()
            + (r.evidence.isEmpty() ? "" : " | " + ReportBundle.scrub(clip(r.evidence, 300), null)));

        boolean windows = System.getProperty("os.name", "").toLowerCase(java.util.Locale.ROOT).contains("win");
        if (!windows || !PZOConfig.isCrashWindowEnabled() || BenchmarkHarness.isConfigured()) return;

        String body = body(st, r);
        String reportPath = null;
        String note = "";
        for (int round = 0; round < MAX_ROUNDS; round++) {
            String choice;
            try {
                choice = showWindow(body + note);
            } catch (Throwable t) {
                PZOLogger.warn("[CrashWindow] window failed: " + t);
                return;
            }
            if (choice.contains("NOSHOW")) PZOConfig.setCrashWindowEnabled(false);
            if (choice.startsWith("REPORT")) {
                if (reportPath == null) reportPath = ReportBundle.createBeforeGame(body);
                note = reportPath == null ? "\n\nThe report file could not be written."
                    : "\n\nReport file written (it should be showing in Explorer):\n" + ReportBundle.scrub(reportPath, null);
            } else if (choice.startsWith("HELP")) {
                if (reportPath == null) reportPath = ReportBundle.createBeforeGame(body);
                copyToClipboard(summary(st, r));
                PZOEntrypoint.openBrowser(DISCORD_URL);
                note = "\n\nA short summary is on the clipboard: paste it into the support channel on Discord and attach the report file"
                    + (reportPath == null ? "." : " (showing in Explorer).");
            } else if (choice.startsWith("DOWNGRADE")) {
                note = "\n\n" + downgrade();
            } else {
                return;
            }
        }
    }

    private static String clip(String s, int max) {
        return s.length() <= max ? s : s.substring(0, max) + "...";
    }

    /** One line to paste into Discord: version, verdict, top error. */
    static String summary(CrashFlag.Status st, Diagnoser.Result r) {
        return "PZO " + UpdateChecker.CURRENT_VERSION
            + (st.previousVersion.isEmpty() || st.previousVersion.equals(UpdateChecker.CURRENT_VERSION) ? "" : " (crashed on " + st.previousVersion + ")")
            + " | " + r.headline()
            + (r.evidence.isEmpty() ? "" : " | " + ReportBundle.scrub(clip(r.evidence, 300), null));
    }

    static String body(CrashFlag.Status st, Diagnoser.Result r) {
        StringBuilder b = new StringBuilder();
        b.append("Project Zomboid did not close cleanly last time (a crash, a freeze, or it was shut down from outside).\n\n");
        b.append("Most likely cause: ").append(r.headline()).append('\n');
        if (!r.evidence.isEmpty()) b.append("Seen in the logs: ").append(ReportBundle.scrub(clip(r.evidence, 300), null)).append('\n');
        if (!st.crashLogs.isEmpty()) b.append("Java crash logs found: ").append(st.crashLogs.size()).append('\n');
        if (!r.alsoSeen.isEmpty()) b.append("Also seen: ").append(String.join("; ", r.alsoSeen)).append('\n');
        if (!r.advice.isEmpty()) b.append("\nWhat to do: ").append(r.advice).append('\n');
        b.append('\n');
        switch (r.verdict) {
            case PZO, PZO_POSSIBLE -> b.append("Please report this so it can be fixed: click Make report file, then attach the zip to a new issue at ")
                .append(ISSUES_URL).append(" or post it in the PZO Discord (Get help opens it). Say what you were doing when it happened.");
            case MOD, INSTALL, USER -> b.append("This does not look like a PZO problem, so we may not be able to help with it. The report file is still"
                + " there if you need it. If you have been through the wiki, the Discord guides and the FAQ and are still stuck, reach out on Discord.");
            default -> b.append("The logs do not show a known cause. If it keeps happening, click Make report file and send it on GitHub or Discord.");
        }
        b.append("\n\nPZO ").append(UpdateChecker.CURRENT_VERSION);
        if (!st.previousVersion.isEmpty() && !st.previousVersion.equals(UpdateChecker.CURRENT_VERSION)) {
            b.append(" (the session that crashed ran ").append(st.previousVersion).append(')');
        }
        return b.toString();
    }

    // Returns a line for the window when the downgrade didn't go ahead; on success the updater exits the JVM
    private static String downgrade() {
        List<String[]> releases = UpdateChecker.listStableReleases(6000);
        if (releases.isEmpty()) return "Could not get the list of PZO versions from GitHub. Check the connection, or use downgrade.bat.";
        StringBuilder items = new StringBuilder();
        for (String[] rel : releases) {
            if (items.length() > 0) items.append('|');
            items.append(rel[0]).append(rel[1].isEmpty() ? "" : "  (" + rel[1] + ")");
        }
        String picked;
        try {
            picked = showVersionPicker(items.toString());
        } catch (Throwable t) {
            return "The version list could not be shown: " + t.getMessage();
        }
        if (picked == null) return "Downgrade cancelled.";
        String tag = picked.split("\\s+")[0];
        for (String[] rel : releases) {
            if (!rel[0].equals(tag)) continue;
            // Otherwise the older build's update prompt offers the newest version straight back
            UpdateDialog.setIgnoredVersion(releases.get(0)[0].replaceFirst("^[vV]", ""));
            PZOLogger.info("[CrashWindow] Downgrading to " + tag);
            UpdateDialog.performAutoUpdateAndExit(tag.replaceFirst("^[vV]", ""),
                "https://github.com/prop11/PZO-Launcher/releases/download/" + tag + "/PZOptimEngine.jar", null);
            return "The download of " + tag + " did not finish. Nothing was changed.";
        }
        return "Downgrade cancelled.";
    }

    private static void copyToClipboard(String text) {
        try {
            java.awt.datatransfer.StringSelection sel = new java.awt.datatransfer.StringSelection(text);
            java.awt.Toolkit.getDefaultToolkit().getSystemClipboard().setContents(sel, sel);
        } catch (Throwable t) {
            PZOLogger.warn("[CrashWindow] clipboard not available: " + t);
        }
    }

    private static String guards() {
        return UpdateDialog.PS_FORM_GUARDS.replace("$tm.Interval=" + UpdateDialog.DIALOG_AUTO_CLOSE_MS + ";", "$tm.Interval=" + Integer.getInteger("pzo.crashWindow.autoCloseMs", AUTO_CLOSE_MS) + ";");
    }

    // CONTINUE, REPORT, HELP or DOWNGRADE, with ";NOSHOW" added when the box is ticked. No double quotes in the script,
    // Java doesn't escape them when it builds the Windows command line; the text goes in an environment variable.
    private static String showWindow(String text) throws Exception {
        String ps =
            "[void][System.Reflection.Assembly]::LoadWithPartialName('System.Windows.Forms');" +
            "[void][System.Reflection.Assembly]::LoadWithPartialName('System.Drawing');" +
            "$f=New-Object Windows.Forms.Form;$f.Text='Project Zomboid Optimiser - Last session did not close cleanly';" +
            "$f.Size=New-Object Drawing.Size(700,540);" +
            "$f.StartPosition='CenterScreen';$f.FormBorderStyle='FixedDialog';" +
            "$f.MaximizeBox=$false;$f.MinimizeBox=$false;" + guards() +
            "$f.BackColor=[Drawing.Color]::FromArgb(30,30,30);$f.ForeColor=[Drawing.Color]::White;" +
            "$t=New-Object Windows.Forms.Label;$t.Text='[!] The last session did not close cleanly';" +
            "$t.Font=New-Object Drawing.Font('Segoe UI',12,[Drawing.FontStyle]::Bold);" +
            "$t.ForeColor=[Drawing.Color]::FromArgb(255,185,50);" +
            "$t.Location=New-Object Drawing.Point(20,15);$t.Size=New-Object Drawing.Size(650,25);$f.Controls.Add($t);" +
            "$i=New-Object Windows.Forms.TextBox;$i.Multiline=$true;$i.ReadOnly=$true;$i.BorderStyle='None';$i.ScrollBars='Vertical';" +
            "$i.Text=($env:PZO_DIALOG_TEXT -replace '\\n',[Environment]::NewLine);" +
            "$i.Font=New-Object Drawing.Font('Segoe UI',9.5);" +
            "$i.BackColor=[Drawing.Color]::FromArgb(30,30,30);$i.ForeColor=[Drawing.Color]::FromArgb(220,220,220);" +
            "$i.Location=New-Object Drawing.Point(20,48);$i.Size=New-Object Drawing.Size(650,345);$f.Controls.Add($i);" +
            "$cb=New-Object Windows.Forms.CheckBox;$cb.Text='Do not show this window after a crash';" +
            "$cb.Font=New-Object Drawing.Font('Segoe UI',9);$cb.ForeColor=[Drawing.Color]::FromArgb(170,170,170);" +
            "$cb.Location=New-Object Drawing.Point(22,402);$cb.Size=New-Object Drawing.Size(400,25);$f.Controls.Add($cb);" +
            button("$bd", "Downgrade PZO", 20, 150, "65,65,65", "Abort", false) +
            button("$bh", "Get help", 180, 130, "65,65,65", "Retry", false) +
            button("$br", "Make report file", 320, 160, "65,65,65", "Yes", false) +
            button("$bl", "Launch game", 520, 150, "40,167,69", "OK", true) +
            "$f.AcceptButton=$bl;$f.CancelButton=$bl;$f.ActiveControl=$bl;$r=$f.ShowDialog();" +
            "$o='CONTINUE';if($r -eq [Windows.Forms.DialogResult]::Yes){$o='REPORT'}" +
            "elseif($r -eq [Windows.Forms.DialogResult]::Retry){$o='HELP'}" +
            "elseif($r -eq [Windows.Forms.DialogResult]::Abort){$o='DOWNGRADE'};" +
            "if($cb.Checked){$o=$o+';NOSHOW'};Write-Output $o";
        String out = UpdateDialog.runDialogProcess(
            new ProcessBuilder("powershell.exe", "-NoProfile", "-NonInteractive", "-WindowStyle", "Hidden", "-Command", ps), text, PROCESS_TIMEOUT_SEC);
        int colon = out.indexOf(':');
        String answer = colon < 0 ? "" : out.substring(colon + 1);
        return answer.isEmpty() ? "CONTINUE" : answer;
    }

    private static String button(String var, String label, int x, int width, String rgb, String result, boolean bold) {
        return var + "=New-Object Windows.Forms.Button;" + var + ".Text='" + label + "';" +
            var + ".Font=New-Object Drawing.Font('Segoe UI',9" + (bold ? ",[Drawing.FontStyle]::Bold" : "") + ");" +
            var + ".BackColor=[Drawing.Color]::FromArgb(" + rgb + ");" + var + ".ForeColor=[Drawing.Color]::White;" + var + ".FlatStyle='Flat';" +
            var + ".Location=New-Object Drawing.Point(" + x + ",445);" + var + ".Size=New-Object Drawing.Size(" + width + ",34);" +
            var + ".DialogResult=[Windows.Forms.DialogResult]::" + result + ";$f.Controls.Add(" + var + ");";
    }

    // The picked "tag  (date)" entry, or null for cancel. The entries arrive as one |-separated environment variable.
    private static String showVersionPicker(String items) throws Exception {
        String ps =
            "[void][System.Reflection.Assembly]::LoadWithPartialName('System.Windows.Forms');" +
            "[void][System.Reflection.Assembly]::LoadWithPartialName('System.Drawing');" +
            "$f=New-Object Windows.Forms.Form;$f.Text='Project Zomboid Optimiser - Downgrade';" +
            "$f.Size=New-Object Drawing.Size(460,230);" +
            "$f.StartPosition='CenterScreen';$f.FormBorderStyle='FixedDialog';" +
            "$f.MaximizeBox=$false;$f.MinimizeBox=$false;" + guards() +
            "$f.BackColor=[Drawing.Color]::FromArgb(30,30,30);$f.ForeColor=[Drawing.Color]::White;" +
            "$t=New-Object Windows.Forms.Label;" +
            "$t.Text='Pick the PZO version to go back to. It is downloaded from GitHub and swapped in when this closes; start the game again afterwards.';" +
            "$t.Font=New-Object Drawing.Font('Segoe UI',9.5);$t.ForeColor=[Drawing.Color]::FromArgb(220,220,220);" +
            "$t.Location=New-Object Drawing.Point(20,15);$t.Size=New-Object Drawing.Size(410,60);$f.Controls.Add($t);" +
            "$c=New-Object Windows.Forms.ComboBox;$c.DropDownStyle='DropDownList';$c.Font=New-Object Drawing.Font('Segoe UI',10);" +
            "foreach($x in ($env:PZO_DIALOG_TEXT -split '\\|')){[void]$c.Items.Add($x)};$c.SelectedIndex=0;" +
            "$c.Location=New-Object Drawing.Point(20,85);$c.Size=New-Object Drawing.Size(410,28);$f.Controls.Add($c);" +
            "$bo=New-Object Windows.Forms.Button;$bo.Text='Downgrade';" +
            "$bo.Font=New-Object Drawing.Font('Segoe UI',9,[Drawing.FontStyle]::Bold);$bo.BackColor=[Drawing.Color]::FromArgb(150,45,45);$bo.ForeColor=[Drawing.Color]::White;$bo.FlatStyle='Flat';" +
            "$bo.Location=New-Object Drawing.Point(305,135);$bo.Size=New-Object Drawing.Size(125,32);$bo.DialogResult=[Windows.Forms.DialogResult]::Yes;$f.Controls.Add($bo);" +
            "$bc=New-Object Windows.Forms.Button;$bc.Text='Cancel';" +
            "$bc.Font=New-Object Drawing.Font('Segoe UI',9);$bc.BackColor=[Drawing.Color]::FromArgb(65,65,65);$bc.ForeColor=[Drawing.Color]::White;$bc.FlatStyle='Flat';" +
            "$bc.Location=New-Object Drawing.Point(170,135);$bc.Size=New-Object Drawing.Size(125,32);$bc.DialogResult=[Windows.Forms.DialogResult]::Cancel;$f.Controls.Add($bc);" +
            "$f.AcceptButton=$bc;$f.CancelButton=$bc;$f.ActiveControl=$c;$r=$f.ShowDialog();" +
            "if($r -eq [Windows.Forms.DialogResult]::Yes){Write-Output ('PICK=' + $c.SelectedItem)}else{Write-Output 'CANCEL'}";
        String out = UpdateDialog.runDialogProcess(
            new ProcessBuilder("powershell.exe", "-NoProfile", "-NonInteractive", "-WindowStyle", "Hidden", "-Command", ps), items, PROCESS_TIMEOUT_SEC);
        int at = out.indexOf("PICK=");
        return at < 0 ? null : out.substring(at + 5).trim();
    }
}

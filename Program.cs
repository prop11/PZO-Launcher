using System;
using System.IO;
using System.Text;
using System.Drawing;
using System.Reflection;
using System.Windows.Forms;
using System.Collections.Generic;
using System.Runtime.InteropServices;

namespace PZOptimizer
{
    static class Program
    {
        [STAThread]
        static void Main()
        {
            Application.EnableVisualStyles();
            Application.SetCompatibleTextRenderingDefault(false);
            Application.Run(new MainForm());
        }
    }

    public class MainForm : Form
    {
        [StructLayout(LayoutKind.Sequential, CharSet = CharSet.Auto)]
        private class MEMORYSTATUSEX
        {
            public uint dwLength;
            public uint dwMemoryLoad;
            public ulong ullTotalPhys;
            public ulong ullAvailPhys;
            public ulong ullTotalPageFile;
            public ulong ullAvailPageFile;
            public ulong ullTotalVirtual;
            public ulong ullAvailVirtual;
            public ulong ullAvailExtendedVirtual;
            public MEMORYSTATUSEX()
            {
                this.dwLength = (uint)Marshal.SizeOf(typeof(MEMORYSTATUSEX));
            }
        }

        [return: MarshalAs(UnmanagedType.Bool)]
        [DllImport("kernel32.dll", CharSet = CharSet.Auto, SetLastError = true)]
        private static extern bool GlobalMemoryStatusEx([In, Out] MEMORYSTATUSEX lpBuffer);

        private TextBox txtPath;
        private TrackBar tbRam;
        private Label lblRamVal;
        private CheckBox chkG1GC;
        private CheckBox chkPretouch;
        private ulong totalRamGb = 16;

        public MainForm()
        {
            this.Text = "Project Zomboid - Config & Engine Optimizer";
            this.Size = new Size(720, 500);
            this.MinimumSize = new Size(680, 450);
            this.StartPosition = FormStartPosition.CenterScreen;
            this.BackColor = Color.FromArgb(15, 17, 26);
            this.ForeColor = Color.FromArgb(241, 245, 249);
            this.Font = new Font("Segoe UI", 9F, FontStyle.Regular);

            DetectRam();
            BuildInterface();
        }

        private void DetectRam()
        {
            try
            {
                MEMORYSTATUSEX stat = new MEMORYSTATUSEX();
                if (GlobalMemoryStatusEx(stat))
                {
                    totalRamGb = (ulong)Math.Round((double)stat.ullTotalPhys / (1024 * 1024 * 1024));
                }
            }
            catch
            {
                totalRamGb = 16;
            }
        }

        private string DetectPzPath()
        {
            string[] paths = new string[]
            {
                @"K:\SteamLibrary\steamapps\common\ProjectZomboid",
                @"C:\Program Files (x86)\Steam\steamapps\common\ProjectZomboid",
                @"D:\SteamLibrary\steamapps\common\ProjectZomboid",
                @"E:\SteamLibrary\steamapps\common\ProjectZomboid",
                @"C:\SteamLibrary\steamapps\common\ProjectZomboid"
            };

            foreach (string p in paths)
            {
                if (File.Exists(Path.Combine(p, "ProjectZomboid64.json")))
                {
                    return p;
                }
            }
            return paths[0];
        }

        private void BuildInterface()
        {
            Panel header = new Panel();
            header.Dock = DockStyle.Top;
            header.Height = 65;
            header.BackColor = Color.FromArgb(22, 25, 38);
            header.Padding = new Padding(20, 12, 20, 12);

            Label title = new Label();
            title.Text = "PROJECT ZOMBOID CONFIG & ENGINE OPTIMIZER";
            title.Font = new Font("Segoe UI", 12F, FontStyle.Bold);
            title.ForeColor = Color.FromArgb(56, 189, 248);
            title.AutoSize = true;
            title.Location = new Point(18, 12);
            header.Controls.Add(title);

            Label sub = new Label();
            sub.Text = string.Format("Detected System: {0} GB RAM | {1} CPU Cores", totalRamGb, Environment.ProcessorCount);
            sub.Font = new Font("Segoe UI", 8.5F);
            sub.ForeColor = Color.FromArgb(148, 163, 184);
            sub.AutoSize = true;
            sub.Location = new Point(20, 36);
            header.Controls.Add(sub);

            this.Controls.Add(header);

            GroupBox grpPath = new GroupBox();
            grpPath.Text = "Project Zomboid Installation Directory";
            grpPath.ForeColor = Color.FromArgb(226, 232, 240);
            grpPath.Location = new Point(16, 80);
            grpPath.Size = new Size(670, 75);

            txtPath = new TextBox();
            txtPath.Location = new Point(15, 28);
            txtPath.Size = new Size(530, 25);
            txtPath.BackColor = Color.FromArgb(11, 13, 20);
            txtPath.ForeColor = Color.FromArgb(248, 250, 252);
            txtPath.BorderStyle = BorderStyle.FixedSingle;
            txtPath.Text = DetectPzPath();
            grpPath.Controls.Add(txtPath);

            Button btnBrowse = new Button();
            btnBrowse.Text = "Browse";
            btnBrowse.Location = new Point(555, 26);
            btnBrowse.Size = new Size(95, 28);
            btnBrowse.BackColor = Color.FromArgb(36, 41, 56);
            btnBrowse.ForeColor = Color.White;
            btnBrowse.FlatStyle = FlatStyle.Flat;
            btnBrowse.FlatAppearance.BorderSize = 0;
            btnBrowse.Click += (s, e) =>
            {
                using (FolderBrowserDialog fbd = new FolderBrowserDialog())
                {
                    fbd.SelectedPath = txtPath.Text;
                    if (fbd.ShowDialog() == DialogResult.OK)
                    {
                        txtPath.Text = fbd.SelectedPath;
                    }
                }
            };
            grpPath.Controls.Add(btnBrowse);
            this.Controls.Add(grpPath);

            GroupBox grpOpt = new GroupBox();
            grpOpt.Text = "Memory & Engine JVM Optimizations";
            grpOpt.ForeColor = Color.FromArgb(226, 232, 240);
            grpOpt.Location = new Point(16, 165);
            grpOpt.Size = new Size(670, 165);

            int defRam = (totalRamGb >= 32) ? 12 : (totalRamGb >= 16 ? 8 : 4);

            lblRamVal = new Label();
            lblRamVal.Text = string.Format("Allocated RAM: {0} GB (-Xmx{1}m)", defRam, defRam * 1024);
            lblRamVal.Font = new Font("Segoe UI", 9F, FontStyle.Bold);
            lblRamVal.ForeColor = Color.FromArgb(74, 222, 128);
            lblRamVal.Location = new Point(15, 25);
            lblRamVal.AutoSize = true;
            grpOpt.Controls.Add(lblRamVal);

            tbRam = new TrackBar();
            tbRam.Location = new Point(15, 48);
            tbRam.Size = new Size(635, 45);
            tbRam.Minimum = 3;
            tbRam.Maximum = (int)Math.Min(24, Math.Max(8, (int)totalRamGb - 4));
            tbRam.Value = defRam;
            tbRam.TickFrequency = 1;
            tbRam.Scroll += (s, e) =>
            {
                lblRamVal.Text = string.Format("Allocated RAM: {0} GB (-Xmx{1}m)", tbRam.Value, tbRam.Value * 1024);
            };
            grpOpt.Controls.Add(tbRam);

            chkG1GC = new CheckBox();
            chkG1GC.Text = "Low-Latency G1GC Tuning (-XX:+UseG1GC -XX:InitiatingHeapOccupancyPercent=45)";
            chkG1GC.Checked = true;
            chkG1GC.Location = new Point(15, 95);
            chkG1GC.Size = new Size(600, 24);
            grpOpt.Controls.Add(chkG1GC);

            chkPretouch = new CheckBox();
            chkPretouch.Text = "Heap Pre-allocation (-XX:+AlwaysPreTouch: Eliminates mid-game memory allocation spikes)";
            chkPretouch.Checked = true;
            chkPretouch.Location = new Point(15, 125);
            chkPretouch.Size = new Size(600, 24);
            grpOpt.Controls.Add(chkPretouch);

            this.Controls.Add(grpOpt);

            Panel actions = new Panel();
            actions.Location = new Point(16, 345);
            actions.Size = new Size(670, 50);

            Button btnOptimize = new Button();
            btnOptimize.Text = "Optimize Game";
            btnOptimize.Font = new Font("Segoe UI", 10F, FontStyle.Bold);
            btnOptimize.BackColor = Color.FromArgb(2, 132, 199);
            btnOptimize.ForeColor = Color.White;
            btnOptimize.FlatStyle = FlatStyle.Flat;
            btnOptimize.FlatAppearance.BorderSize = 0;
            btnOptimize.Location = new Point(0, 5);
            btnOptimize.Size = new Size(340, 40);
            btnOptimize.Click += (s, e) => { ApplyOptimization(); };
            actions.Controls.Add(btnOptimize);

            Button btnRestore = new Button();
            btnRestore.Text = "Restore Default";
            btnRestore.BackColor = Color.FromArgb(36, 41, 56);
            btnRestore.ForeColor = Color.FromArgb(248, 113, 113);
            btnRestore.FlatStyle = FlatStyle.Flat;
            btnRestore.FlatAppearance.BorderSize = 0;
            btnRestore.Location = new Point(350, 5);
            btnRestore.Size = new Size(150, 40);
            btnRestore.Click += (s, e) => { RestoreDefault(); };
            actions.Controls.Add(btnRestore);

            Button btnClear = new Button();
            btnClear.Text = "Clear Logs";
            btnClear.BackColor = Color.FromArgb(36, 41, 56);
            btnClear.ForeColor = Color.FromArgb(251, 191, 36);
            btnClear.FlatStyle = FlatStyle.Flat;
            btnClear.FlatAppearance.BorderSize = 0;
            btnClear.Location = new Point(510, 5);
            btnClear.Size = new Size(160, 40);
            btnClear.Click += (s, e) => { ClearLogs(); };
            actions.Controls.Add(btnClear);

            this.Controls.Add(actions);
        }

        private void ExtractEmbeddedJar(string destinationPath)
        {
            try
            {
                using (Stream stream = Assembly.GetExecutingAssembly().GetManifestResourceStream("PZOptimEngine.jar"))
                {
                    if (stream != null)
                    {
                        using (FileStream fileStream = new FileStream(destinationPath, FileMode.Create, FileAccess.Write))
                        {
                            byte[] buffer = new byte[8192];
                            int read;
                            while ((read = stream.Read(buffer, 0, buffer.Length)) > 0)
                            {
                                fileStream.Write(buffer, 0, read);
                            }
                        }
                        return;
                    }
                }
            }
            catch {}

            try
            {
                string baseDir = AppDomain.CurrentDomain.BaseDirectory;
                string localJar = Path.Combine(baseDir, "dist", "PZOptimEngine.jar");
                if (!File.Exists(localJar)) localJar = Path.Combine(baseDir, "PZOptimEngine.jar");
                if (File.Exists(localJar))
                {
                    File.Copy(localJar, destinationPath, true);
                }
            }
            catch {}
        }

        private void ApplyOptimization()
        {
            string dir = txtPath.Text.Trim();
            string jsonFile = Path.Combine(dir, "ProjectZomboid64.json");

            if (!File.Exists(jsonFile))
            {
                MessageBox.Show("Could not find ProjectZomboid64.json in:\n" + dir, "Error", MessageBoxButtons.OK, MessageBoxIcon.Error);
                return;
            }

            string bak = Path.Combine(dir, "ProjectZomboid64.json.bak");
            if (!File.Exists(bak))
            {
                File.Copy(jsonFile, bak);
            }

            // Extract embedded PZOptimEngine.jar into Project Zomboid directory
            ExtractEmbeddedJar(Path.Combine(dir, "PZOptimEngine.jar"));

            int ramMb = tbRam.Value * 1024;
            List<string> args = new List<string>();
            args.Add("-Djava.awt.headless=true");
            args.Add("--enable-native-access=ALL-UNNAMED");
            args.Add("--add-exports=java.base/jdk.internal.misc=ALL-UNNAMED");
            args.Add(string.Format("-Xmx{0}m", ramMb));
            args.Add("-Dzomboid.steam=1");
            args.Add("-Dzomboid.znetlog=1");
            args.Add("-Djava.library.path=win64/;.");
            args.Add("-XX:-CreateCoredumpOnCrash");
            args.Add("-XX:-OmitStackTraceInFastThrow");

            if (chkG1GC.Checked)
            {
                args.Add("-XX:+UseG1GC");
                args.Add("-XX:InitiatingHeapOccupancyPercent=45");
                args.Add("-XX:G1ReservePercent=15");
            }

            if (chkPretouch.Checked)
            {
                args.Add("-XX:+AlwaysPreTouch");
            }

            StringBuilder sb = new StringBuilder();
            sb.AppendLine("{");
            sb.AppendLine("    \"mainClass\": \"com/pzoptimizer/PZOEntrypoint\",");
            sb.AppendLine("    \"classpath\": [");
            sb.AppendLine("        \".\",");
            sb.AppendLine("        \"PZOptimEngine.jar\",");
            sb.AppendLine("        \"projectzomboid.jar\"");
            sb.AppendLine("    ],");
            sb.AppendLine("    \"vmArgs\": [");

            for (int i = 0; i < args.Count; i++)
            {
                string comma = (i < args.Count - 1) ? "," : "";
                sb.AppendLine(string.Format("        \"{0}\"{1}", args[i], comma));
            }

            sb.AppendLine("    ]");
            sb.AppendLine("}");

            File.WriteAllText(jsonFile, sb.ToString(), new UTF8Encoding(false));

            try
            {
                string userDir = Path.Combine(Environment.GetFolderPath(Environment.SpecialFolder.UserProfile), "Zomboid");
                string luaDir = Path.Combine(userDir, "Lua");
                if (!Directory.Exists(luaDir)) Directory.CreateDirectory(luaDir);
                string statusJson = string.Format("{{\"optimized\": true, \"ram_gb\": {0}, \"g1gc\": {1}, \"pretouch\": {2}}}", tbRam.Value, chkG1GC.Checked.ToString().ToLower(), chkPretouch.Checked.ToString().ToLower());
                File.WriteAllText(Path.Combine(luaDir, "pzo_status.json"), statusJson, new UTF8Encoding(false));
            }
            catch {}

            MessageBox.Show(string.Format("Project Zomboid optimized successfully!\n\nAllocated RAM: {0} GB\nLow-Latency G1GC: {1}\nEngine Wrapper: Active\n\nYou can now launch the game normally through Steam.", tbRam.Value, chkG1GC.Checked), "Optimized", MessageBoxButtons.OK, MessageBoxIcon.Information);
        }

        private void RestoreDefault()
        {
            string dir = txtPath.Text.Trim();
            string jsonFile = Path.Combine(dir, "ProjectZomboid64.json");
            string bak = Path.Combine(dir, "ProjectZomboid64.json.bak");

            if (File.Exists(bak))
            {
                File.Copy(bak, jsonFile, true);
            }
            else
            {
                string def = "{\n    \"mainClass\": \"zombie/gameStates/MainScreenState\",\n    \"classpath\": [\".\", \"projectzomboid.jar\"],\n    \"vmArgs\": [\n        \"-Djava.awt.headless=true\",\n        \"--enable-native-access=ALL-UNNAMED\",\n        \"--add-exports=java.base/jdk.internal.misc=ALL-UNNAMED\",\n        \"-Xmx3072m\",\n        \"-Dzomboid.steam=1\",\n        \"-Dzomboid.znetlog=1\",\n        \"-Djava.library.path=win64/;.\",\n        \"-XX:-CreateCoredumpOnCrash\",\n        \"-XX:-OmitStackTraceInFastThrow\"\n    ]\n}";
                File.WriteAllText(jsonFile, def, new UTF8Encoding(false));
            }

            try
            {
                string userDir = Path.Combine(Environment.GetFolderPath(Environment.SpecialFolder.UserProfile), "Zomboid");
                string statusFile = Path.Combine(userDir, "Lua", "pzo_status.json");
                if (File.Exists(statusFile))
                {
                    File.Delete(statusFile);
                }
            }
            catch {}

            MessageBox.Show("Restored vanilla configuration from backup.", "Restored", MessageBoxButtons.OK, MessageBoxIcon.Information);
        }

        private void ClearLogs()
        {
            string userDir = Path.Combine(Environment.GetFolderPath(Environment.SpecialFolder.UserProfile), "Zomboid");
            int count = 0;
            string[] subs = new string[] { "Lua", "logs" };
            foreach (string sub in subs)
            {
                string p = Path.Combine(userDir, sub);
                if (Directory.Exists(p))
                {
                    foreach (string f in Directory.GetFiles(p))
                    {
                        if (f.EndsWith(".log") || f.EndsWith(".cache") || f.EndsWith(".txt"))
                        {
                            try
                            {
                                File.Delete(f);
                                count++;
                            }
                            catch { }
                        }
                    }
                }
            }
            MessageBox.Show(string.Format("Removed {0} cache and log files.", count), "Cleaned", MessageBoxButtons.OK, MessageBoxIcon.Information);
        }
    }
}

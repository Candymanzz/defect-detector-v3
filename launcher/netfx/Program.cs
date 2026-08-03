using System;
using System.Collections.Generic;
using System.Diagnostics;
using System.IO;
using System.Net;
using System.Text;
using System.Threading;

namespace ImlLauncher
{
    internal static class Program
    {
        private static Process _orchestrator;
        private static string _repoRoot = "";
        private static int _exitCode;

        private static int Main(string[] args)
        {
            try
            {
                Console.OutputEncoding = Encoding.UTF8;
            }
            catch
            {
            }

            bool noFrontend = HasFlag(args, "--no-frontend") || HasFlag(args, "-NoFrontend");
            string configArg = GetOption(args, "--config") ?? GetOption(args, "-Config");

            try
            {
                _repoRoot = ResolveRepoRoot();
                Directory.SetCurrentDirectory(_repoRoot);
                Console.WriteLine("Defect Detector launcher");
                Console.WriteLine("  root: " + _repoRoot);

                Console.WriteLine();
                Console.WriteLine("==> Cleanup stale processes");
                StackCleanup.Run(_repoRoot, true);

                Console.WriteLine();
                Console.WriteLine("==> Environment");
                string javaHome = ResolveJavaHome();
                string javaExe = Path.Combine(javaHome, "bin", "java.exe");
                if (!File.Exists(javaExe))
                {
                    throw new FileNotFoundException("java.exe not found: " + javaExe);
                }
                PrependPath(Path.Combine(javaHome, "bin"));
                PrependPath(@"C:\Program Files\dotnet");
                PrependPath(@"C:\Program Files\nodejs");
                Environment.SetEnvironmentVariable("JAVA_HOME", javaHome);
                Console.WriteLine("  JAVA_HOME=" + javaHome);

                string configRel = string.IsNullOrWhiteSpace(configArg) ? @"config\config.yaml" : configArg;
                string configPath = Path.IsPathRooted(configRel)
                    ? configRel
                    : Path.GetFullPath(Path.Combine(_repoRoot, configRel));
                RequireFile(configPath, "Config");

                string orchestratorJar = Path.Combine(_repoRoot, "orchestrator-java", "target", "orchestrator-0.1.0-SNAPSHOT.jar");
                string geometryJar = Path.Combine(_repoRoot, "java-geometry-service", "target", "java-geometry-service-0.1.0-SNAPSHOT.jar");
                string pythonExe = Path.Combine(_repoRoot, "analisSurface", "backend", ".venv", "Scripts", "python.exe");
                string lightDll = Path.Combine(_repoRoot, "LightServer.v3", "bin", "Release", "net10.0", "LightServer.dll");
                string ioDll = Path.Combine(_repoRoot, "IoInputMonitor", "bin", "Release", "net10.0", "IoInputMonitor.dll");
                string workerDebug = Path.Combine(_repoRoot, "camera-worker", "build", "Debug", "camera_worker.exe");
                string workerRelease = Path.Combine(_repoRoot, "camera-worker", "build", "Release", "camera_worker.exe");
                string frontModules = Path.Combine(_repoRoot, "front-end", "node_modules");

                RequireFile(orchestratorJar, "Orchestrator JAR (run rebuild-and-run.ps1)");
                RequireFile(geometryJar, "Geometry JAR");
                RequireFile(pythonExe, "Python venv");
                RequireFile(lightDll, "LightServer.dll");
                RequireFile(ioDll, "IoInputMonitor.dll");
                if (!File.Exists(workerDebug) && !File.Exists(workerRelease))
                {
                    throw new FileNotFoundException("camera_worker.exe missing (build/Debug or build/Release)");
                }

                if (noFrontend)
                {
                    Environment.SetEnvironmentVariable("IML_FRONTEND_AUTOSTART", "false");
                    Console.WriteLine("  frontend autostart disabled");
                }
                else if (!Directory.Exists(frontModules))
                {
                    throw new DirectoryNotFoundException("front-end/node_modules missing (run rebuild-and-run.ps1)");
                }

                Console.WriteLine();
                Console.WriteLine("Starting orchestrator. Ctrl+C = stop all.");
                Console.WriteLine("  API : http://127.0.0.1:8099");
                Console.WriteLine("  WS  : ws://127.0.0.1:8765");
                if (!noFrontend)
                {
                    Console.WriteLine("  UI  : http://localhost:5173");
                }
                Console.WriteLine("  IoInputMonitor + LightServer + analisSurface + cameras — autostart");
                Console.WriteLine();

                Console.CancelKeyPress += OnCancel;

                ProcessStartInfo psi = new ProcessStartInfo();
                psi.FileName = javaExe;
                psi.Arguments = "-jar \"" + orchestratorJar + "\" \"" + configPath + "\"";
                psi.WorkingDirectory = _repoRoot;
                psi.UseShellExecute = false;
                _orchestrator = Process.Start(psi);
                if (_orchestrator == null)
                {
                    throw new InvalidOperationException("Failed to start java process");
                }
                Console.WriteLine("==> orchestrator pid=" + _orchestrator.Id);
                _orchestrator.WaitForExit();
                _exitCode = _orchestrator.ExitCode;
            }
            catch (Exception ex)
            {
                Console.Error.WriteLine("ERROR: " + ex.Message);
                _exitCode = 1;
            }
            finally
            {
                Console.WriteLine();
                Console.WriteLine("==> Cleanup");
                if (_orchestrator != null && !_orchestrator.HasExited)
                {
                    StackCleanup.KillProcessTree(_orchestrator.Id);
                }
                if (!string.IsNullOrEmpty(_repoRoot))
                {
                    StackCleanup.Run(_repoRoot, false);
                }
            }

            return _exitCode;
        }

        private static void OnCancel(object sender, ConsoleCancelEventArgs e)
        {
            e.Cancel = true;
            Console.WriteLine();
            Console.WriteLine("Ctrl+C — stopping...");
            if (_orchestrator != null && !_orchestrator.HasExited)
            {
                StackCleanup.KillProcessTree(_orchestrator.Id);
            }
        }

        private static string ResolveRepoRoot()
        {
            List<string> candidates = new List<string>();
            candidates.Add(AppDomain.CurrentDomain.BaseDirectory);
            candidates.Add(Directory.GetCurrentDirectory());
            try
            {
                string exe = Process.GetCurrentProcess().MainModule.FileName;
                if (!string.IsNullOrEmpty(exe))
                {
                    string dir = Path.GetDirectoryName(exe);
                    if (!string.IsNullOrEmpty(dir))
                    {
                        candidates.Add(dir);
                    }
                }
            }
            catch
            {
            }

            Dictionary<string, bool> seen = new Dictionary<string, bool>(StringComparer.OrdinalIgnoreCase);
            foreach (string start in candidates)
            {
                if (string.IsNullOrEmpty(start) || seen.ContainsKey(start))
                {
                    continue;
                }
                seen[start] = true;
                string cur = Path.GetFullPath(start);
                for (int i = 0; i < 6 && !string.IsNullOrEmpty(cur); i++)
                {
                    if (File.Exists(Path.Combine(cur, "config", "config.yaml"))
                        && Directory.Exists(Path.Combine(cur, "orchestrator-java")))
                    {
                        return cur;
                    }
                    DirectoryInfo parent = Directory.GetParent(cur);
                    cur = parent == null ? null : parent.FullName;
                }
            }

            throw new DirectoryNotFoundException(
                "Repo root not found (need config/config.yaml). Put DefectDetector.exe in the repo root.");
        }

        private static string ResolveJavaHome()
        {
            string env = Environment.GetEnvironmentVariable("JAVA_HOME");
            if (!string.IsNullOrWhiteSpace(env) && Directory.Exists(env))
            {
                return env;
            }
            string[] candidates = new string[]
            {
                @"C:\Tools\jdk-17",
                @"C:\dev-tools\jdk-17",
                @"C:\Program Files\Java\jdk-17",
                @"C:\Program Files\Eclipse Adoptium\jdk-17"
            };
            foreach (string candidate in candidates)
            {
                if (Directory.Exists(candidate))
                {
                    return candidate;
                }
            }
            string fromPath = FindJavaHomeFromPath();
            if (fromPath != null)
            {
                return fromPath;
            }
            throw new DirectoryNotFoundException("JDK not found. Set JAVA_HOME (need JDK 17+).");
        }

        private static string FindJavaHomeFromPath()
        {
            try
            {
                ProcessStartInfo psi = new ProcessStartInfo();
                psi.FileName = "where.exe";
                psi.Arguments = "java";
                psi.RedirectStandardOutput = true;
                psi.UseShellExecute = false;
                psi.CreateNoWindow = true;
                using (Process p = Process.Start(psi))
                {
                    if (p == null)
                    {
                        return null;
                    }
                    string output = p.StandardOutput.ReadToEnd();
                    p.WaitForExit(3000);
                    string[] lines = output.Split(new char[] { '\r', '\n' }, StringSplitOptions.RemoveEmptyEntries);
                    if (lines.Length == 0 || !File.Exists(lines[0]))
                    {
                        return null;
                    }
                    string bin = Path.GetDirectoryName(lines[0]);
                    if (bin == null)
                    {
                        return null;
                    }
                    DirectoryInfo home = Directory.GetParent(bin);
                    return home != null && Directory.Exists(home.FullName) ? home.FullName : null;
                }
            }
            catch
            {
                return null;
            }
        }

        private static void PrependPath(string dir)
        {
            if (!Directory.Exists(dir))
            {
                return;
            }
            string path = Environment.GetEnvironmentVariable("PATH") ?? "";
            if (path.IndexOf(dir, StringComparison.OrdinalIgnoreCase) >= 0)
            {
                return;
            }
            Environment.SetEnvironmentVariable("PATH", dir + ";" + path);
        }

        private static void RequireFile(string path, string label)
        {
            if (!File.Exists(path))
            {
                throw new FileNotFoundException(label + " missing: " + path);
            }
        }

        private static bool HasFlag(string[] args, string flag)
        {
            foreach (string a in args)
            {
                if (string.Equals(a, flag, StringComparison.OrdinalIgnoreCase))
                {
                    return true;
                }
            }
            return false;
        }

        private static string GetOption(string[] args, string name)
        {
            for (int i = 0; i < args.Length - 1; i++)
            {
                if (string.Equals(args[i], name, StringComparison.OrdinalIgnoreCase))
                {
                    return args[i + 1];
                }
            }
            return null;
        }
    }

    internal static class StackCleanup
    {
        private static readonly int[] Ports = new int[]
        {
            8000, 8001, 8002, 8003, 8004, 8005, 8006, 8007, 8008, 8009,
            8099, 8765, 5173, 5079, 5080, 8088
        };

        public static void Run(string repoRoot, bool quiet)
        {
            SendLightBankOff(quiet);
            string pidFile = Path.Combine(repoRoot, ".dev-stack.pids.json");
            if (File.Exists(pidFile))
            {
                try { File.Delete(pidFile); } catch { }
            }
            foreach (int port in Ports)
            {
                KillListeners(port);
            }
            if (!quiet)
            {
                Console.WriteLine("Стек остановлен.");
            }
        }

        private static void SendLightBankOff(bool quiet)
        {
            try
            {
                HttpWebRequest req = (HttpWebRequest)WebRequest.Create("http://127.0.0.1:5080/api/camera-flash/bank");
                req.Method = "POST";
                req.ContentType = "application/json; charset=utf-8";
                req.Timeout = 2000;
                byte[] body = Encoding.UTF8.GetBytes("{\"state\":\"off\"}");
                req.ContentLength = body.Length;
                using (Stream s = req.GetRequestStream())
                {
                    s.Write(body, 0, body.Length);
                }
                using (HttpWebResponse resp = (HttpWebResponse)req.GetResponse())
                {
                }
                if (!quiet)
                {
                    Console.WriteLine("LightServer bank Off отправлен.");
                }
            }
            catch
            {
            }
        }

        private static void KillListeners(int port)
        {
            try
            {
                ProcessStartInfo psi = new ProcessStartInfo();
                psi.FileName = "powershell.exe";
                psi.Arguments =
                    "-NoProfile -Command \"Get-NetTCPConnection -LocalPort " + port
                    + " -State Listen -ErrorAction SilentlyContinue | ForEach-Object { Stop-Process -Id $_.OwningProcess -Force -ErrorAction SilentlyContinue }\"";
                psi.UseShellExecute = false;
                psi.CreateNoWindow = true;
                using (Process p = Process.Start(psi))
                {
                    if (p != null)
                    {
                        p.WaitForExit(5000);
                    }
                }
            }
            catch
            {
            }
        }

        public static void KillProcessTree(int pid)
        {
            if (pid <= 0)
            {
                return;
            }
            try
            {
                ProcessStartInfo psi = new ProcessStartInfo();
                psi.FileName = "taskkill";
                psi.Arguments = "/PID " + pid + " /T /F";
                psi.UseShellExecute = false;
                psi.CreateNoWindow = true;
                using (Process p = Process.Start(psi))
                {
                    if (p != null)
                    {
                        p.WaitForExit(10000);
                    }
                }
            }
            catch
            {
                try
                {
                    Process.GetProcessById(pid).Kill();
                }
                catch
                {
                }
            }
        }
    }
}

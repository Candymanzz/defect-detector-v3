using System;
using System.Collections.Generic;
using System.Diagnostics;
using System.IO;
using System.Threading;

namespace ImlLauncher
{
    internal sealed class LaunchOptions
    {
        public bool NoFrontend { get; set; }
        public string ConfigArg { get; set; }
        public string RepoRoot { get; set; }
    }

    internal sealed class StartupController
    {
        private readonly LaunchOptions _options;
        private readonly ServiceStatusModel _model;
        private readonly Action _uiNotify;
        private readonly object _sync = new object();

        private Process _orchestrator;
        private Thread _worker;
        private Thread _poller;
        private volatile bool _stopRequested;
        private volatile bool _browserOpened;
        private volatile bool _running;
        private string _fatalError = "";
        private bool _workersHintFromLog;
        private bool _bootDoneFromLog;

        public StartupController(LaunchOptions options, ServiceStatusModel model, Action uiNotify)
        {
            _options = options;
            _model = model;
            _uiNotify = uiNotify;
        }

        public string FatalError
        {
            get { return _fatalError; }
        }

        public bool IsRunning
        {
            get { return _running; }
        }

        public void Start()
        {
            _worker = new Thread(RunStartup);
            _worker.IsBackground = true;
            _worker.Name = "launcher-startup";
            _worker.Start();
        }

        public void RequestStop()
        {
            _stopRequested = true;
            Process orch;
            lock (_sync)
            {
                orch = _orchestrator;
            }
            if (orch != null && !orch.HasExited)
            {
                StackCleanup.KillProcessTree(orch.Id);
            }
            if (!string.IsNullOrEmpty(_options.RepoRoot))
            {
                StackCleanup.Run(_options.RepoRoot, true);
            }
            _running = false;
            SetStep("Остановка…");
            Notify();
        }

        private void RunStartup()
        {
            try
            {
                _running = true;
                string root = _options.RepoRoot;
                Directory.SetCurrentDirectory(root);

                SetStep("Очистка старых процессов…");
                _model.Set(ServiceIds.Environment, ServiceState.Starting, "cleanup");
                Notify();
                StackCleanup.Run(root, true);
                if (_stopRequested) return;

                SetStep("Проверка окружения…");
                string javaHome = ResolveJavaHome();
                string javaExe = Path.Combine(javaHome, "bin", "java.exe");
                if (!File.Exists(javaExe))
                {
                    throw new FileNotFoundException("java.exe не найден: " + javaExe);
                }
                PrependPath(Path.Combine(javaHome, "bin"));
                PrependPath(@"C:\Program Files\dotnet");
                PrependPath(@"C:\Program Files\nodejs");
                Environment.SetEnvironmentVariable("JAVA_HOME", javaHome);

                string configRel = string.IsNullOrWhiteSpace(_options.ConfigArg)
                    ? @"config\config.yaml"
                    : _options.ConfigArg;
                string configPath = Path.IsPathRooted(configRel)
                    ? configRel
                    : Path.GetFullPath(Path.Combine(root, configRel));
                RequireFile(configPath, "Config");

                string orchestratorJar = Path.Combine(root, "orchestrator-java", "target", "orchestrator-0.1.0-SNAPSHOT.jar");
                string geometryJar = Path.Combine(root, "java-geometry-service", "target", "java-geometry-service-0.1.0-SNAPSHOT.jar");
                string pythonExe = Path.Combine(root, "analisSurface", "backend", ".venv", "Scripts", "python.exe");
                string lightDll = Path.Combine(root, "LightServer.v3", "bin", "Release", "net10.0", "LightServer.dll");
                string ioDll = Path.Combine(root, "IoInputMonitor", "bin", "Release", "net10.0", "IoInputMonitor.dll");
                string workerDebug = Path.Combine(root, "camera-worker", "build", "Debug", "camera_worker.exe");
                string workerRelease = Path.Combine(root, "camera-worker", "build", "Release", "camera_worker.exe");
                string frontModules = Path.Combine(root, "front-end", "node_modules");

                RequireFile(orchestratorJar, "Orchestrator JAR (запустите rebuild-and-run.ps1)");
                RequireFile(geometryJar, "Geometry JAR");
                RequireFile(pythonExe, "Python venv");
                RequireFile(lightDll, "LightServer.dll");
                RequireFile(ioDll, "IoInputMonitor.dll");
                if (!File.Exists(workerDebug) && !File.Exists(workerRelease))
                {
                    throw new FileNotFoundException("camera_worker.exe отсутствует (build/Debug или build/Release)");
                }

                if (_options.NoFrontend)
                {
                    Environment.SetEnvironmentVariable("IML_FRONTEND_AUTOSTART", "false");
                }
                else if (!Directory.Exists(frontModules))
                {
                    throw new DirectoryNotFoundException("front-end/node_modules отсутствует (запустите rebuild-and-run.ps1)");
                }

                _model.Set(ServiceIds.Environment, ServiceState.Ready, "JAVA_HOME=" + javaHome);
                MarkStartingChildren();
                SetStep("Запуск оркестратора…");
                Notify();

                ProcessStartInfo psi = new ProcessStartInfo();
                psi.FileName = javaExe;
                psi.Arguments = "-jar \"" + orchestratorJar + "\" \"" + configPath + "\"";
                psi.WorkingDirectory = root;
                psi.UseShellExecute = false;
                psi.RedirectStandardOutput = true;
                psi.RedirectStandardError = true;
                psi.CreateNoWindow = true;
                psi.StandardOutputEncoding = System.Text.Encoding.UTF8;
                psi.StandardErrorEncoding = System.Text.Encoding.UTF8;

                Process proc = Process.Start(psi);
                if (proc == null)
                {
                    throw new InvalidOperationException("Не удалось запустить java-процесс");
                }
                lock (_sync)
                {
                    _orchestrator = proc;
                }
                _model.Set(ServiceIds.Orchestrator, ServiceState.Starting, "pid=" + proc.Id);
                Notify();

                proc.OutputDataReceived += OnOrchestratorLine;
                proc.ErrorDataReceived += OnOrchestratorLine;
                proc.BeginOutputReadLine();
                proc.BeginErrorReadLine();

                _poller = new Thread(PollLoop);
                _poller.IsBackground = true;
                _poller.Name = "launcher-poll";
                _poller.Start();

                proc.WaitForExit();
                if (!_stopRequested)
                {
                    int code = proc.ExitCode;
                    if (code != 0)
                    {
                        _model.Set(ServiceIds.Orchestrator, ServiceState.Error, "exit " + code);
                        _fatalError = "Оркестратор завершился с кодом " + code;
                        SetStep("Ошибка запуска");
                    }
                    else
                    {
                        SetStep("Оркестратор остановлен");
                    }
                    Notify();
                }
            }
            catch (Exception ex)
            {
                _fatalError = ex.Message;
                _model.Set(ServiceIds.Environment, ServiceState.Error, ex.Message);
                SetStep("Ошибка: " + Truncate(ex.Message, 90));
                Notify();
            }
            finally
            {
                _running = false;
                if (!_stopRequested && !string.IsNullOrEmpty(_options.RepoRoot))
                {
                    // Don't auto-cleanup on crash mid-session if user still has window —
                    // but if orchestrator died, ports may be stale; soft cleanup is OK.
                }
                Notify();
            }
        }

        private void MarkStartingChildren()
        {
            _model.Set(ServiceIds.Orchestrator, ServiceState.Starting, "");
            _model.Set(ServiceIds.AnalisSurface, ServiceState.Starting, ":8000");
            _model.Set(ServiceIds.LightServer, ServiceState.Starting, ":5080");
            _model.Set(ServiceIds.IoInput, ServiceState.Starting, ":9101");
            if (!_options.NoFrontend)
            {
                _model.Set(ServiceIds.Frontend, ServiceState.Starting, ":5173");
            }
            _model.Set(ServiceIds.ClientWs, ServiceState.Starting, ":8765");
            _model.Set(ServiceIds.Workers, ServiceState.Starting, "geometry / positioning / cameras");
        }

        private void PollLoop()
        {
            while (!_stopRequested)
            {
                try
                {
                    ProbeServices();
                    MaybeOpenBrowser();
                }
                catch
                {
                }
                Thread.Sleep(400);
                Process orch;
                lock (_sync)
                {
                    orch = _orchestrator;
                }
                if (orch != null && orch.HasExited)
                {
                    break;
                }
            }
        }

        private void ProbeServices()
        {
            string detail;
            bool changed = false;

            if (HealthProbe.HttpBodyContains("http://127.0.0.1:8099/health", 800, "ok", out detail))
            {
                changed |= Promote(ServiceIds.Orchestrator, ServiceState.Ready, detail);
            }

            if (HealthProbe.HttpOk("http://127.0.0.1:8000/health", 800, out detail)
                || HealthProbe.HttpOk("http://127.0.0.1:8000/detector/health", 800, out detail))
            {
                changed |= Promote(ServiceIds.AnalisSurface, ServiceState.Ready, detail);
            }

            if (HealthProbe.HttpOk("http://127.0.0.1:5080/", 800, out detail)
                || HealthProbe.TcpOpen("127.0.0.1", 5080, 600, out detail))
            {
                changed |= Promote(ServiceIds.LightServer, ServiceState.Ready, detail);
            }

            if (HealthProbe.HttpOk("http://127.0.0.1:9101/line-direction", 800, out detail)
                || HealthProbe.TcpOpen("127.0.0.1", 9101, 600, out detail))
            {
                changed |= Promote(ServiceIds.IoInput, ServiceState.Ready, detail);
            }

            if (!_options.NoFrontend)
            {
                if (HealthProbe.HttpOk("http://127.0.0.1:5173/", 800, out detail)
                    || HealthProbe.TcpOpen("127.0.0.1", 5173, 600, out detail))
                {
                    changed |= Promote(ServiceIds.Frontend, ServiceState.Ready, detail);
                }
            }

            if (HealthProbe.TcpOpen("127.0.0.1", 8765, 600, out detail))
            {
                changed |= Promote(ServiceIds.ClientWs, ServiceState.Ready, detail);
            }

            if (_bootDoneFromLog || _workersHintFromLog)
            {
                changed |= Promote(ServiceIds.Workers, ServiceState.Ready,
                    _bootDoneFromLog ? "boot parallel done" : "по логам оркестратора");
            }
            else if (_model.Get(ServiceIds.Orchestrator) != null
                     && _model.Get(ServiceIds.Orchestrator).State == ServiceState.Ready
                     && _model.Get(ServiceIds.AnalisSurface) != null
                     && _model.Get(ServiceIds.AnalisSurface).State == ServiceState.Ready)
            {
                // Soft success once core HTTP is up — workers usually follow.
                changed |= Promote(ServiceIds.Workers, ServiceState.Ready, "ядро готово");
            }

            if (changed)
            {
                if (_model.CriticalReady)
                {
                    SetStep("Система готова");
                }
                else if (string.IsNullOrEmpty(_fatalError))
                {
                    SetStep("Поднимаются сервисы…");
                }
                Notify();
            }
        }

        private bool Promote(string id, ServiceState state, string detail)
        {
            ServiceItem item = _model.Get(id);
            if (item == null || item.State == state)
            {
                if (item != null && detail != null && detail != item.Detail && state == ServiceState.Ready)
                {
                    item.Detail = detail;
                    return true;
                }
                return false;
            }
            _model.Set(id, state, detail);
            return true;
        }

        private void MaybeOpenBrowser()
        {
            if (_browserOpened || _options.NoFrontend || !_model.CriticalReady)
            {
                return;
            }
            _browserOpened = true;
            try
            {
                Process.Start("http://localhost:5173/");
            }
            catch
            {
                try
                {
                    ProcessStartInfo psi = new ProcessStartInfo();
                    psi.FileName = "cmd.exe";
                    psi.Arguments = "/c start http://localhost:5173/";
                    psi.CreateNoWindow = true;
                    psi.UseShellExecute = false;
                    Process.Start(psi);
                }
                catch
                {
                }
            }
        }

        private void OnOrchestratorLine(object sender, DataReceivedEventArgs e)
        {
            if (e == null || string.IsNullOrEmpty(e.Data))
            {
                return;
            }
            string line = e.Data;
            string lower = line.ToLowerInvariant();

            if (lower.IndexOf("child services boot parallel done") >= 0)
            {
                _bootDoneFromLog = true;
                _model.Set(ServiceIds.Workers, ServiceState.Ready, "boot parallel done");
                Notify();
            }
            if (lower.IndexOf("lightserver") >= 0 || lower.IndexOf("light_server") >= 0)
            {
                if (lower.IndexOf("error") < 0 && lower.IndexOf("fail") < 0)
                {
                    _model.Set(ServiceIds.LightServer, ServiceState.Starting, Truncate(line, 70));
                }
            }
            if (lower.IndexOf("analissurface") >= 0 || lower.IndexOf("analis_surface") >= 0 || lower.IndexOf("uvicorn") >= 0)
            {
                if (lower.IndexOf("error") < 0)
                {
                    _model.Set(ServiceIds.AnalisSurface, ServiceState.Starting, Truncate(line, 70));
                }
            }
            if (lower.IndexOf("frontend") >= 0 || lower.IndexOf("vite") >= 0)
            {
                if (!_options.NoFrontend && lower.IndexOf("error") < 0)
                {
                    _model.Set(ServiceIds.Frontend, ServiceState.Starting, Truncate(line, 70));
                }
            }
            if (lower.IndexOf("camera") >= 0 || lower.IndexOf("geometry") >= 0 || lower.IndexOf("positioning") >= 0)
            {
                _workersHintFromLog = true;
                if (_model.Get(ServiceIds.Workers) != null
                    && _model.Get(ServiceIds.Workers).State != ServiceState.Ready)
                {
                    _model.Set(ServiceIds.Workers, ServiceState.Starting, Truncate(line, 70));
                    Notify();
                }
            }
            if (lower.IndexOf("io-input-monitor") >= 0 || lower.IndexOf("io_input_monitor") >= 0)
            {
                _model.Set(ServiceIds.IoInput, ServiceState.Starting, Truncate(line, 70));
            }

            // Fatal-looking lines for orchestrator itself
            if ((lower.IndexOf(" integration bootstrap failed") >= 0
                 || lower.IndexOf("fatal") >= 0)
                && lower.IndexOf("error") >= 0)
            {
                _model.Set(ServiceIds.Orchestrator, ServiceState.Error, Truncate(line, 90));
                Notify();
            }
        }

        private void SetStep(string text)
        {
            _model.StepText = text;
        }

        private void Notify()
        {
            Action a = _uiNotify;
            if (a != null)
            {
                try { a(); } catch { }
            }
        }

        private static string Truncate(string s, int max)
        {
            if (string.IsNullOrEmpty(s))
            {
                return "";
            }
            s = s.Trim();
            if (s.Length <= max)
            {
                return s;
            }
            return s.Substring(0, max - 1) + "…";
        }

        private static void RequireFile(string path, string label)
        {
            if (!File.Exists(path))
            {
                throw new FileNotFoundException(label + " отсутствует: " + path);
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

        internal static string ResolveRepoRoot()
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
                "Корень репозитория не найден (нужен config/config.yaml). Положите DefectDetector.exe в корень.");
        }

        internal static string ResolveJavaHome()
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
            throw new DirectoryNotFoundException("JDK не найден. Установите JAVA_HOME (JDK 17+).");
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
    }
}

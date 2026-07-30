using System;
using System.Diagnostics;
using System.IO;
using System.Linq;
using System.Text;

namespace DefectDetectorLauncher
{
    /// <summary>
    /// Double-click launcher: starts run.ps1 in the repo root (orchestrator stack).
    /// Place DefectDetector.exe next to run.cmd / run.ps1.
    /// </summary>
    internal static class Program
    {
        private const string Title = "Defect Detector";

        private static int Main(string[] args)
        {
            // Avoid OEM codepage mojibake for ASCII console output.
            try { Console.OutputEncoding = Encoding.UTF8; } catch { /* ignore */ }
            Console.Title = Title;
            try
            {
                string exePath = Process.GetCurrentProcess().MainModule != null
                    ? Process.GetCurrentProcess().MainModule.FileName
                    : typeof(Program).Assembly.Location;
                string exeDir = Path.GetDirectoryName(exePath) ?? Environment.CurrentDirectory;

                string repoRoot = FindRepoRoot(exeDir);
                if (repoRoot == null)
                {
                    Fail("Repo root not found (run.ps1 / config) near:\n" + exeDir);
                    return 1;
                }

                string runPs1 = Path.Combine(repoRoot, "run.ps1");
                if (!File.Exists(runPs1))
                {
                    Fail("run.ps1 missing in:\n" + repoRoot);
                    return 1;
                }

                Console.WriteLine("==> " + Title);
                Console.WriteLine("Root: " + repoRoot);
                Console.WriteLine("Starting stack. Ctrl+C to stop.");
                Console.WriteLine();

                // Call PowerShell directly (not via run.cmd) so -File encoding is stable.
                var psi = new ProcessStartInfo
                {
                    FileName = "powershell.exe",
                    Arguments = "-NoProfile -ExecutionPolicy Bypass -File "
                        + QuoteArg(runPs1)
                        + (args.Length > 0 ? " " + string.Join(" ", args.Select(QuoteArg)) : ""),
                    WorkingDirectory = repoRoot,
                    UseShellExecute = false,
                };

                using (var proc = Process.Start(psi))
                {
                    if (proc == null)
                    {
                        Fail("Failed to start PowerShell.");
                        return 1;
                    }

                    proc.WaitForExit();
                    return proc.ExitCode;
                }
            }
            catch (Exception ex)
            {
                Fail(ex.Message);
                return 1;
            }
        }

        private static string FindRepoRoot(string startDir)
        {
            var dir = new DirectoryInfo(startDir);
            while (dir != null)
            {
                bool hasRun = File.Exists(Path.Combine(dir.FullName, "run.cmd"))
                    || File.Exists(Path.Combine(dir.FullName, "run.ps1"));
                bool hasConfig = File.Exists(Path.Combine(dir.FullName, "config", "config.yaml"));
                if (hasRun && hasConfig)
                    return dir.FullName;
                dir = dir.Parent;
            }
            return null;
        }

        private static string QuoteArg(string arg)
        {
            if (arg.IndexOf(' ') >= 0 || arg.IndexOf('"') >= 0)
                return "\"" + arg.Replace("\"", "\\\"") + "\"";
            return arg;
        }

        private static void Fail(string message)
        {
            Console.Error.WriteLine();
            Console.Error.WriteLine("ERROR: " + message);
            Console.Error.WriteLine();
            Console.Error.WriteLine("Press Enter to exit...");
            try { Console.ReadLine(); } catch { /* ignore */ }
        }
    }
}

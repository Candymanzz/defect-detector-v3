using System;
using System.Diagnostics;
using System.IO;
using System.Net;
using System.Text;

namespace ImlLauncher
{
    internal static class StackCleanup
    {
        private static readonly int[] Ports = new int[]
        {
            8000, 8001, 8002, 8003, 8004, 8005, 8006, 8007, 8008, 8009,
            8099, 8765, 5173, 5079, 5080, 8088, 9101
        };

        public static void Run(string repoRoot, bool quiet)
        {
            SendLightBankOff();
            if (!string.IsNullOrEmpty(repoRoot))
            {
                string pidFile = Path.Combine(repoRoot, ".dev-stack.pids.json");
                if (File.Exists(pidFile))
                {
                    try { File.Delete(pidFile); } catch { }
                }
            }
            foreach (int port in Ports)
            {
                KillListeners(port);
            }
        }

        private static void SendLightBankOff()
        {
            try
            {
                HttpWebRequest req = (HttpWebRequest)WebRequest.Create("http://127.0.0.1:5080/api/camera-flash/bank");
                req.Method = "POST";
                req.ContentType = "application/json; charset=utf-8";
                req.Timeout = 2000;
                req.Proxy = null;
                byte[] body = Encoding.UTF8.GetBytes("{\"state\":\"off\"}");
                req.ContentLength = body.Length;
                using (Stream s = req.GetRequestStream())
                {
                    s.Write(body, 0, body.Length);
                }
                using (HttpWebResponse resp = (HttpWebResponse)req.GetResponse())
                {
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

using System.Diagnostics;
using System.Text;

namespace IoInputMonitor;

/// <summary>
/// При занятом COM: найти другой процесс с IoInputMonitor.dll и убить дерево
/// (сирота после crash / неполного stop оркестратора).
/// </summary>
internal static class IoSiblingProcessKiller
{
    public const string CommandMarker = "IoInputMonitor.dll";

    /// <summary>Пауза после kill, чтобы драйвер отпустил COM.</summary>
    public const int PostKillReleaseMs = 1500;

    /// <returns>Сколько процессов отправили в taskkill.</returns>
    public static int KillOtherInstances(Action<string>? log = null)
    {
        if (!OperatingSystem.IsWindows())
        {
            log?.Invoke("steal_com: не Windows — пропуск");
            return 0;
        }

        IReadOnlyList<int> pids;
        try
        {
            pids = FindDotnetSiblingPids();
        }
        catch (Exception ex)
        {
            log?.Invoke($"steal_com: не удалось перечислить процессы: {ex.Message}");
            return 0;
        }

        if (pids.Count == 0)
        {
            log?.Invoke(
                "steal_com: чужих IoInputMonitor не найдено " +
                "(COM busy без процесса — USB/ребут или MVS Client)");
            return 0;
        }

        int killed = 0;
        foreach (int pid in pids)
        {
            log?.Invoke($"steal_com: taskkill /T /F pid={pid} (cmdline содержит {CommandMarker})");
            if (TryTaskKill(pid))
                killed++;
        }

        return killed;
    }

    /// <summary>Фильтр для тестов: только dotnet/IoInputMonitor.exe, не self, маркер в cmdline.</summary>
    internal static List<int> FilterSiblingPids(
        IEnumerable<(int Pid, string Name, string? CommandLine)> processes,
        int selfPid)
    {
        var result = new List<int>();
        foreach ((int pid, string name, string? commandLine) in processes)
        {
            if (pid <= 0 || pid == selfPid)
                continue;
            if (string.IsNullOrWhiteSpace(commandLine))
                continue;
            if (!commandLine.Contains(CommandMarker, StringComparison.OrdinalIgnoreCase)
                && !commandLine.Contains("IoInputMonitor.exe", StringComparison.OrdinalIgnoreCase))
                continue;

            string exe = name.Trim();
            if (!exe.Equals("dotnet.exe", StringComparison.OrdinalIgnoreCase)
                && !exe.Equals("dotnet", StringComparison.OrdinalIgnoreCase)
                && !exe.Equals("IoInputMonitor.exe", StringComparison.OrdinalIgnoreCase)
                && !exe.Equals("IoInputMonitor", StringComparison.OrdinalIgnoreCase))
                continue;

            result.Add(pid);
        }

        return result;
    }

    private static IReadOnlyList<int> FindDotnetSiblingPids()
    {
        long selfPid = Environment.ProcessId;
        // Фильтр Name=dotnet.exe — PowerShell-пробник с маркером в -Command не попадает в выборку.
        string ps = """
            Get-CimInstance Win32_Process -Filter "Name = 'dotnet.exe' OR Name = 'IoInputMonitor.exe'" |
              Where-Object {
                $_.CommandLine -and (
                  $_.CommandLine -like '*IoInputMonitor.dll*' -or
                  $_.CommandLine -like '*IoInputMonitor.exe*'
                ) -and $_.ProcessId -ne __SELF__
              } |
              ForEach-Object { $_.ProcessId }
            """.Replace("__SELF__", selfPid.ToString());

        var psi = new ProcessStartInfo
        {
            FileName = "powershell.exe",
            ArgumentList = { "-NoProfile", "-NonInteractive", "-Command", ps },
            RedirectStandardOutput = true,
            RedirectStandardError = true,
            UseShellExecute = false,
            CreateNoWindow = true,
            StandardOutputEncoding = Encoding.UTF8
        };

        using var proc = Process.Start(psi)
            ?? throw new InvalidOperationException("не удалось запустить powershell");
        string stdout = proc.StandardOutput.ReadToEnd();
        _ = proc.StandardError.ReadToEnd();
        if (!proc.WaitForExit(15_000))
        {
            try { proc.Kill(entireProcessTree: true); } catch { /* ignore */ }
            throw new TimeoutException("powershell enumeration timed out");
        }

        var rows = new List<(int Pid, string Name, string? CommandLine)>();
        foreach (string line in stdout.Split('\n', StringSplitOptions.RemoveEmptyEntries | StringSplitOptions.TrimEntries))
        {
            if (!int.TryParse(line, out int pid) || pid <= 0)
                continue;
            // Уже отфильтровано в PS; имя подставляем для FilterSiblingPids / единообразия.
            rows.Add((pid, "dotnet.exe", CommandMarker));
        }

        return FilterSiblingPids(rows, (int)selfPid);
    }

    private static bool TryTaskKill(int pid)
    {
        try
        {
            var psi = new ProcessStartInfo
            {
                FileName = "taskkill.exe",
                ArgumentList = { "/PID", pid.ToString(), "/T", "/F" },
                RedirectStandardOutput = true,
                RedirectStandardError = true,
                UseShellExecute = false,
                CreateNoWindow = true
            };
            using var proc = Process.Start(psi);
            if (proc == null)
                return false;
            proc.StandardOutput.ReadToEnd();
            proc.StandardError.ReadToEnd();
            return proc.WaitForExit(10_000) && proc.ExitCode == 0;
        }
        catch
        {
            return false;
        }
    }
}

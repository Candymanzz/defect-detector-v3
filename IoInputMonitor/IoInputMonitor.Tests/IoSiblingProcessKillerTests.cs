using Xunit;

namespace IoInputMonitor.Tests;

public class IoSiblingProcessKillerTests
{
    [Fact]
    public void FilterSiblingPids_keepsDotnetWithDll_excludesSelfAndPowershell()
    {
        var rows = new (int Pid, string Name, string? CommandLine)[]
        {
            (100, "dotnet.exe", @"dotnet exec C:\x\IoInputMonitor.dll"),
            (200, "powershell.exe", "Where-Object { $_.CommandLine -like '*IoInputMonitor.dll*' }"),
            (300, "dotnet.exe", "dotnet run --project Other"),
            (400, "IoInputMonitor.exe", @"C:\x\IoInputMonitor.exe --com COM3"),
            (500, "dotnet.exe", null),
            (999, "dotnet.exe", @"dotnet exec IoInputMonitor.dll"), // self
        };

        List<int> pids = IoSiblingProcessKiller.FilterSiblingPids(rows, selfPid: 999);

        Assert.Equal([100, 400], pids);
    }

    [Fact]
    public void ParseFile_readsStealComOnBusy()
    {
        string path = Path.Combine(Path.GetTempPath(), $"io-steal-{Guid.NewGuid():N}.yaml");
        File.WriteAllText(path, """
            io_input:
              com_port: COM3
              inputs: [3]
              steal_com_on_busy: false
            """);

        try
        {
            IoInputOptions options = IoInputConfigLoader.ParseFile(path);
            Assert.False(options.StealComOnBusy);
        }
        finally
        {
            File.Delete(path);
        }
    }

    [Fact]
    public void ParseFile_defaultsStealComOnBusyTrue()
    {
        string path = Path.Combine(Path.GetTempPath(), $"io-steal-def-{Guid.NewGuid():N}.yaml");
        File.WriteAllText(path, """
            io_input:
              com_port: COM3
              inputs: [3]
            """);

        try
        {
            IoInputOptions options = IoInputConfigLoader.ParseFile(path);
            Assert.True(options.StealComOnBusy);
        }
        finally
        {
            File.Delete(path);
        }
    }
}

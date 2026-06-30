using YamlDotNet.Serialization;
using YamlDotNet.Serialization.NamingConventions;

namespace IoInputMonitor;

public sealed class IoInputOptions
{
    public string ComPort { get; set; } = "COM3";

    public int[] InputPorts { get; set; } = [3];

    public int PollIntervalMs { get; set; } = 100;
}

public sealed class IoInputConfigLoadResult
{
    public IoInputOptions Options { get; init; } = new();

    public string? ConfigPath { get; init; }

    public bool LoadedFromYaml { get; init; }
}

public static class IoInputConfigLoader
{
    public const string DefaultRelativePath = "config/blocks/52-io-input.yaml";
    public const string ConfigEnvVar = "IO_INPUT_CONFIG";
    public const string ConfigCliPrefix = "--io-config=";

    public static IoInputConfigLoadResult Load(string[] args)
    {
        string? explicitPath = ResolveExplicitPath(args);
        string? configPath = explicitPath ?? FindDefaultConfigFile();

        if (configPath == null || !File.Exists(configPath))
        {
            return new IoInputConfigLoadResult
            {
                Options = new IoInputOptions(),
                LoadedFromYaml = false
            };
        }

        IoInputOptions options = ParseFile(configPath);
        return new IoInputConfigLoadResult
        {
            Options = options,
            ConfigPath = Path.GetFullPath(configPath),
            LoadedFromYaml = true
        };
    }

    public static string? ResolveExplicitPath(string[] args)
    {
        string? fromEnv = Environment.GetEnvironmentVariable(ConfigEnvVar);
        if (!string.IsNullOrWhiteSpace(fromEnv))
            return fromEnv.Trim();

        foreach (string arg in args)
        {
            if (arg.StartsWith(ConfigCliPrefix, StringComparison.OrdinalIgnoreCase))
                return arg[ConfigCliPrefix.Length..].Trim('"');
        }

        return null;
    }

    public static string? FindDefaultConfigFile()
    {
        foreach (string startDir in CandidateStartDirectories())
        {
            string? found = WalkUpForConfig(startDir);
            if (found != null)
                return found;
        }

        return null;
    }

    private static IEnumerable<string> CandidateStartDirectories()
    {
        yield return Directory.GetCurrentDirectory();

        string baseDir = AppContext.BaseDirectory;
        if (!string.IsNullOrWhiteSpace(baseDir))
            yield return baseDir;
    }

    private static string? WalkUpForConfig(string startDir)
    {
        DirectoryInfo? dir = new(startDir);
        for (int i = 0; i < 8 && dir != null; i++, dir = dir.Parent)
        {
            string candidate = Path.Combine(
                dir.FullName,
                DefaultRelativePath.Replace('/', Path.DirectorySeparatorChar));
            if (File.Exists(candidate))
                return candidate;
        }

        return null;
    }

    internal static IoInputOptions ParseFile(string path)
    {
        string yaml = File.ReadAllText(path);
        var root = new DeserializerBuilder()
            .WithNamingConvention(UnderscoredNamingConvention.Instance)
            .IgnoreUnmatchedProperties()
            .Build()
            .Deserialize<IoInputYamlRoot>(yaml)
            ?? new IoInputYamlRoot();

        IoInputYamlSection section = root.IoInput ?? new IoInputYamlSection();
        int[] inputs = ParseInputPorts(section.Inputs);
        if (inputs.Length == 0)
            inputs = [3];

        return new IoInputOptions
        {
            ComPort = string.IsNullOrWhiteSpace(section.ComPort) ? "COM3" : section.ComPort.Trim(),
            InputPorts = inputs,
            PollIntervalMs = section.PollIntervalMs is >= 10 ? section.PollIntervalMs.Value : 100
        };
    }

    internal static int[] ParseInputPorts(IEnumerable<int>? raw)
    {
        if (raw == null)
            return [];

        var ports = new List<int>(8);
        foreach (int port in raw)
        {
            if (port is < 1 or > 8 || ports.Contains(port))
                continue;

            ports.Add(port);
        }

        return ports.ToArray();
    }

    private sealed class IoInputYamlRoot
    {
        public IoInputYamlSection? IoInput { get; set; }
    }

    private sealed class IoInputYamlSection
    {
        public string? ComPort { get; set; }

        public int? PollIntervalMs { get; set; }

        public List<int>? Inputs { get; set; }
    }
}

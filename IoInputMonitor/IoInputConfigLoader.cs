using YamlDotNet.Serialization;
using YamlDotNet.Serialization.NamingConventions;

namespace IoInputMonitor;

public enum IoInputEdgeMode
{
    Rising,
    Falling,
    Both
}

public sealed class IoInputOptions
{
    public string ComPort { get; set; } = "COM3";

    public int[] InputPorts { get; set; } = [3];

    public IoInputEdgeMode EdgeMode { get; set; } = IoInputEdgeMode.Both;

    public bool ConfigureSdk { get; set; } = true;

    public int DebounceMs { get; set; } = 50;

    public IoInputUdpPublishOptions UdpPublish { get; set; } = new();

    public WorkerTriggerPublishOptions WorkerTrigger { get; set; } = new();
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
            EdgeMode = ParseEdgeMode(section.Edge),
            ConfigureSdk = section.ConfigureSdk ?? true,
            DebounceMs = section.DebounceMs is >= 0 and <= 1000 ? section.DebounceMs.Value : 50,
            UdpPublish = ParseUdpPublish(section.Publish?.Udp, inputs),
            WorkerTrigger = ParseWorkerTrigger(section.Publish?.WorkerTrigger)
        };
    }

    private static WorkerTriggerPublishOptions ParseWorkerTrigger(IoInputWorkerTriggerYaml? raw)
    {
        if (raw == null)
            return new WorkerTriggerPublishOptions();

        int[] cameraIds = ParseCameraIds(raw.CameraIds);
        if (cameraIds.Length == 0)
            cameraIds = [0, 1, 2, 3, 4, 5, 6, 7, 8, 9];

        return new WorkerTriggerPublishOptions
        {
            Enabled = raw.Enabled ?? false,
            Host = string.IsNullOrWhiteSpace(raw.Host) ? "127.0.0.1" : raw.Host.Trim(),
            PortBase = raw.PortBase is > 0 and <= 65535 ? raw.PortBase.Value : 9210,
            CameraIds = cameraIds,
            RequireDirectionHigh = raw.RequireDirectionHigh ?? true,
            DirectionPort = raw.DirectionPort is >= 1 and <= 8 ? raw.DirectionPort.Value : 2,
            TriggerPort = raw.TriggerPort is >= 1 and <= 8 ? raw.TriggerPort.Value : 3
        };
    }

    internal static int[] ParseCameraIds(IEnumerable<int>? raw)
    {
        if (raw == null)
            return [];

        var ids = new List<int>(16);
        foreach (int id in raw)
        {
            if (id < 0 || ids.Contains(id))
                continue;
            ids.Add(id);
        }

        return ids.ToArray();
    }

    private static IoInputUdpPublishOptions ParseUdpPublish(IoInputUdpPublishYaml? raw, int[] defaultInputs)
    {
        if (raw == null)
            return new IoInputUdpPublishOptions();

        int[] publishInputs = ParseInputPorts(raw.Inputs);
        if (publishInputs.Length == 0)
            publishInputs = defaultInputs;

        return new IoInputUdpPublishOptions
        {
            Enabled = raw.Enabled ?? false,
            Host = string.IsNullOrWhiteSpace(raw.Host) ? "127.0.0.1" : raw.Host.Trim(),
            Port = raw.Port is > 0 and <= 65535 ? raw.Port.Value : 9100,
            Format = ParseUdpFormat(raw.Format),
            PublishInputs = publishInputs,
            SendInitialState = raw.SendInitialState ?? false,
            TriggerPort = raw.TriggerPort is >= 1 and <= 8 ? raw.TriggerPort.Value : 3,
            LowLatencyTrigger = raw.LowLatencyTrigger ?? true,
            SendInitialTriggerState = raw.SendInitialTriggerState ?? false
        };
    }

    internal static IoInputUdpPayloadFormat ParseUdpFormat(string? raw)
    {
        if (string.IsNullOrWhiteSpace(raw))
            return IoInputUdpPayloadFormat.Json;

        return raw.Trim().ToLowerInvariant() switch
        {
            "byte" => IoInputUdpPayloadFormat.Byte,
            "text" or "ascii" or "string" => IoInputUdpPayloadFormat.Text,
            "json" => IoInputUdpPayloadFormat.Json,
            "text_di" or "di_text" or "di" => IoInputUdpPayloadFormat.TextDi,
            "byte_di" or "di_byte" or "bytes" => IoInputUdpPayloadFormat.ByteDi,
            _ => IoInputUdpPayloadFormat.Json
        };
    }

    internal static IoInputEdgeMode ParseEdgeMode(string? raw)
    {
        if (string.IsNullOrWhiteSpace(raw))
            return IoInputEdgeMode.Rising;

        return raw.Trim().ToLowerInvariant() switch
        {
            "rising" => IoInputEdgeMode.Rising,
            "falling" => IoInputEdgeMode.Falling,
            "both" => IoInputEdgeMode.Both,
            _ => IoInputEdgeMode.Rising
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

        public List<int>? Inputs { get; set; }

        public string? Edge { get; set; }

        public bool? ConfigureSdk { get; set; }

        public int? DebounceMs { get; set; }

        public IoInputPublishYaml? Publish { get; set; }
    }

    private sealed class IoInputPublishYaml
    {
        public IoInputUdpPublishYaml? Udp { get; set; }

        public IoInputWorkerTriggerYaml? WorkerTrigger { get; set; }
    }

    private sealed class IoInputWorkerTriggerYaml
    {
        public bool? Enabled { get; set; }

        public string? Host { get; set; }

        public int? PortBase { get; set; }

        public List<int>? CameraIds { get; set; }

        public bool? RequireDirectionHigh { get; set; }

        public int? DirectionPort { get; set; }

        public int? TriggerPort { get; set; }
    }

    private sealed class IoInputUdpPublishYaml
    {
        public bool? Enabled { get; set; }

        public string? Host { get; set; }

        public int? Port { get; set; }

        public string? Format { get; set; }

        public List<int>? Inputs { get; set; }

        public bool? SendInitialState { get; set; }

        public int? TriggerPort { get; set; }

        public bool? LowLatencyTrigger { get; set; }

        public bool? SendInitialTriggerState { get; set; }
    }
}

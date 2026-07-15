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

    public IoCaptureOptions Capture { get; set; } = new();
}

public sealed class IoInputConfigLoadResult
{
    public IoInputOptions Options { get; init; } = new();

    public string? ConfigPath { get; init; }

    public bool LoadedFromYaml { get; init; }
}

/// <summary>Загрузка io_input из YAML; ищет config/blocks/52-io-input.yaml вверх от cwd и exe.</summary>
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

    // До 8 уровней вверх — чтобы находить config/ и из IoInputMonitor/, и из bin/Release/.
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
            Capture = ParseCapture(section.Capture)
        };
    }

    private static IoCaptureOptions ParseCapture(IoInputCaptureYaml? raw)
    {
        if (raw == null)
            return new IoCaptureOptions();

        return new IoCaptureOptions
        {
            Enabled = raw.Enabled ?? false,
            DirectionPort = raw.DirectionPort is >= 1 and <= 8 ? raw.DirectionPort.Value : 2,
            TriggerPort = raw.TriggerPort is >= 1 and <= 8 ? raw.TriggerPort.Value : 3,
            OutputPort = raw.OutputPort is >= 1 and <= 8 ? raw.OutputPort.Value : 5,
            OutputMode = ParseOutputMode(raw.OutputMode),
            TimerIndex = raw.TimerIndex is >= 1 and <= 8 ? raw.TimerIndex.Value : 1,
            PulseDurationMs = raw.PulseDurationMs is >= 1 and <= 65535 ? raw.PulseDurationMs.Value : 20,
            DirectionInvert = raw.DirectionInvert ?? false,
            RequireDirection = raw.RequireDirection ?? true,
            InitialDirection = string.IsNullOrWhiteSpace(raw.InitialDirection) ? "reverse" : raw.InitialDirection.Trim(),
            DirectionHttp = ParseDirectionHttp(raw.DirectionHttp)
        };
    }

    private static IoDirectionHttpOptions ParseDirectionHttp(IoDirectionHttpYaml? raw)
    {
        if (raw == null)
            return new IoDirectionHttpOptions();

        return new IoDirectionHttpOptions
        {
            Enabled = raw.Enabled ?? true,
            Host = string.IsNullOrWhiteSpace(raw.Host) ? "127.0.0.1" : raw.Host.Trim(),
            Port = raw.Port is > 0 and <= 65535 ? raw.Port.Value : 9101
        };
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

    internal static IoCaptureOutputMode ParseOutputMode(string? raw)
    {
        if (string.IsNullOrWhiteSpace(raw))
            return IoCaptureOutputMode.Timer;

        return raw.Trim().ToLowerInvariant() switch
        {
            "direct" or "do" or "setoutput" => IoCaptureOutputMode.Direct,
            "timer" or "software" => IoCaptureOutputMode.Timer,
            _ => IoCaptureOutputMode.Timer
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

        public IoInputCaptureYaml? Capture { get; set; }
    }

    private sealed class IoInputPublishYaml
    {
        public IoInputUdpPublishYaml? Udp { get; set; }
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

    private sealed class IoInputCaptureYaml
    {
        public bool? Enabled { get; set; }

        public int? DirectionPort { get; set; }

        public int? TriggerPort { get; set; }

        public int? OutputPort { get; set; }

        public string? OutputMode { get; set; }

        public int? TimerIndex { get; set; }

        public int? PulseDurationMs { get; set; }

        public bool? DirectionInvert { get; set; }

        public bool? RequireDirection { get; set; }

        public string? InitialDirection { get; set; }

        public IoDirectionHttpYaml? DirectionHttp { get; set; }
    }

    private sealed class IoDirectionHttpYaml
    {
        public bool? Enabled { get; set; }

        public string? Host { get; set; }

        public int? Port { get; set; }
    }
}

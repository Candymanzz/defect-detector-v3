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

    public IoRejectOptions Reject { get; set; } = new();
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
            Capture = ParseCapture(section.Capture),
            Reject = ParseReject(section.Reject)
        };
    }

    private static IoRejectOptions ParseReject(IoInputRejectYaml? raw)
    {
        if (raw == null)
            return new IoRejectOptions();

        return new IoRejectOptions
        {
            Enabled = raw.Enabled ?? false,
            ReadyOutputPort = raw.ReadyOutputPort is >= 1 and <= 8 ? raw.ReadyOutputPort.Value : 4,
            FaultOutputPort = raw.FaultOutputPort is >= 1 and <= 8 ? raw.FaultOutputPort.Value : 8,
            Line1OutputPort = raw.Line1OutputPort is >= 1 and <= 8 ? raw.Line1OutputPort.Value : 6,
            Line2OutputPort = raw.Line2OutputPort is >= 1 and <= 8 ? raw.Line2OutputPort.Value : 7,
            PulseDurationMs = raw.PulseDurationMs is >= 1 and <= 65535 ? raw.PulseDurationMs.Value : 200,
            ActiveHigh = raw.ActiveHigh ?? true
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
            PulseDelayMs = raw.PulseDelayMs is >= 0 and <= 5000 ? raw.PulseDelayMs.Value : 80,
            PulseRepeat = raw.PulseRepeat is >= 1 and <= 20 ? raw.PulseRepeat.Value : 3,
            PulseRepeatGapMs = raw.PulseRepeatGapMs is >= 0 and <= 2000 ? raw.PulseRepeatGapMs.Value : 80,
            ActiveHigh = ResolveActiveHigh(raw),
            Line0Edge = ResolveLine0Edge(raw),
            DirectionInvert = raw.DirectionInvert ?? false,
            RequireDirection = raw.RequireDirection ?? true,
            InitialDirection = string.IsNullOrWhiteSpace(raw.InitialDirection) ? "reverse" : raw.InitialDirection.Trim(),
            DirectionHttp = ParseDirectionHttp(raw.DirectionHttp)
        };
    }

    /// <summary>
    /// Согласование с камерами: falling (NPN DO «вкл» = линия ↓) или rising (PNP/высокий импульс).
    /// </summary>
    private static string ResolveLine0Edge(IoInputCaptureYaml raw)
    {
        string? edge = raw.Line0Edge ?? raw.PolarityEdge;
        if (string.IsNullOrWhiteSpace(edge))
            return "falling";

        return edge.Trim().ToLowerInvariant() switch
        {
            "rising" or "rise" or "high" => "rising",
            _ => "falling"
        };
    }

    private static bool ResolveActiveHigh(IoInputCaptureYaml raw)
    {
        if (raw.ActiveHigh.HasValue)
            return raw.ActiveHigh.Value;

        // По умолчанию SDK Level=1. Если камера RisingEdge а линия NPN «вкл=низ» — ставь active_high: false.
        return true;
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
            return IoCaptureOutputMode.Auto;

        return raw.Trim().ToLowerInvariant() switch
        {
            "direct" or "do" or "setoutput" => IoCaptureOutputMode.Direct,
            "timer" or "software" => IoCaptureOutputMode.Timer,
            "auto" or "any" => IoCaptureOutputMode.Auto,
            _ => IoCaptureOutputMode.Auto
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

        public IoInputRejectYaml? Reject { get; set; }
    }

    private sealed class IoInputRejectYaml
    {
        public bool? Enabled { get; set; }

        public int? ReadyOutputPort { get; set; }

        public int? FaultOutputPort { get; set; }

        public int? Line1OutputPort { get; set; }

        public int? Line2OutputPort { get; set; }

        public int? PulseDurationMs { get; set; }

        public bool? ActiveHigh { get; set; }
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

        public int? PulseDelayMs { get; set; }

        public int? PulseRepeat { get; set; }

        public int? PulseRepeatGapMs { get; set; }

        public bool? ActiveHigh { get; set; }

        /// <summary>rising|falling — полярность импульса DO ↔ Line0.</summary>
        public string? Line0Edge { get; set; }

        public string? PolarityEdge { get; set; }

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

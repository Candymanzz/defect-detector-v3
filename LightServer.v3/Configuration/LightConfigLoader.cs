using YamlDotNet.Serialization;
using YamlDotNet.Serialization.NamingConventions;

namespace LightServer.Configuration;

public sealed class LightHardwareLoadResult
{
    public LightHardwareOptions Options { get; init; } = new();

    public string? ConfigPath { get; init; }

    public bool LoadedFromYaml { get; init; }

    public string? Warning { get; init; }

    public bool HasYamlDevices =>
        LoadedFromYaml && Options.Devices.Any(static d => d.Enabled && d.IsCom && d.ComPort.Length > 0);
}

public static class LightConfigLoader
{
    public const string DefaultRelativePath = "config/blocks/51-light-hardware.yaml";
    public const string ConfigEnvVar = "LIGHT_HARDWARE_CONFIG";
    public const string ConfigCliPrefix = "--light-config=";

    public static LightHardwareLoadResult Load(string[] args)
    {
        string? explicitPath = ResolveExplicitPath(args);
        string? configPath = explicitPath ?? FindDefaultConfigFile();

        if (configPath == null || !File.Exists(configPath))
        {
            return new LightHardwareLoadResult
            {
                LoadedFromYaml = false,
                Warning = configPath == null
                    ? $"Файл {DefaultRelativePath} не найден — используется legacy ComLightDevices из appsettings.json."
                    : $"Конфиг вспышек не найден: {configPath} — используется legacy ComLightDevices из appsettings.json."
            };
        }

        try
        {
            LightHardwareOptions options = ParseFile(configPath);
            return new LightHardwareLoadResult
            {
                Options = options,
                ConfigPath = Path.GetFullPath(configPath),
                LoadedFromYaml = true
            };
        }
        catch (Exception ex)
        {
            return new LightHardwareLoadResult
            {
                LoadedFromYaml = false,
                ConfigPath = Path.GetFullPath(configPath),
                Warning = $"Не удалось прочитать {configPath}: {ex.Message}. Используется legacy ComLightDevices из appsettings.json."
            };
        }
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
            string candidate = Path.Combine(dir.FullName, DefaultRelativePath.Replace('/', Path.DirectorySeparatorChar));
            if (File.Exists(candidate))
                return candidate;
        }

        return null;
    }

    internal static LightHardwareOptions ParseFile(string path)
    {
        string yaml = File.ReadAllText(path);
        var root = new DeserializerBuilder()
            .WithNamingConvention(UnderscoredNamingConvention.Instance)
            .IgnoreUnmatchedProperties()
            .Build()
            .Deserialize<LightHardwareYamlRoot>(yaml)
            ?? new LightHardwareYamlRoot();

        LightHardwareYamlSection section = root.LightHardware ?? new LightHardwareYamlSection();
        var devices = new List<LightHardwareDeviceEntry>();
        foreach (LightHardwareYamlDevice raw in section.Devices ?? [])
        {
            if (string.IsNullOrWhiteSpace(raw.Id))
                continue;

            string type = (raw.Type ?? "com").Trim();
            devices.Add(new LightHardwareDeviceEntry
            {
                Id = raw.Id.Trim(),
                Enabled = raw.Enabled ?? true,
                Type = type,
                ComPort = (raw.ComPort ?? "").Trim(),
                Ip = string.IsNullOrWhiteSpace(raw.Ip) ? null : raw.Ip.Trim(),
                Channels = ParseDeviceChannels(raw.Channels)
            });
        }

        var routes = new List<LightCameraRouteEntry>();
        foreach (LightHardwareYamlCameraRoute raw in section.CameraRoutes ?? [])
        {
            if (raw.CameraNumber is null or <= 0 || string.IsNullOrWhiteSpace(raw.DeviceId))
                continue;

            int[] channels = ParseRouteChannels(raw.Channels);
            if (channels.Length == 0)
                continue;

            routes.Add(new LightCameraRouteEntry
            {
                CameraNumber = raw.CameraNumber.Value,
                DeviceId = raw.DeviceId.Trim(),
                Channels = channels
            });
        }

        return new LightHardwareOptions
        {
            InitializeOnStartup = section.InitializeOnStartup ?? true,
            Devices = devices.ToArray(),
            CameraRoutes = routes.ToArray()
        };
    }

    /// <summary>devices: channels: 2 → [1,2]; channels: [1,3] → явный список.</summary>
    internal static int[] ParseDeviceChannels(object? raw)
    {
        switch (raw)
        {
            case null:
                return [];
            case int count when count > 0:
                return Enumerable.Range(1, Math.Min(count, 4)).ToArray();
            case long count when count > 0:
                return Enumerable.Range(1, Math.Min((int)count, 4)).ToArray();
            case IEnumerable<object> objects:
                return ParseChannelList(objects);
            case System.Collections.IEnumerable enumerable when raw is not string:
                return ParseChannelList(enumerable.Cast<object>());
            default:
                if (int.TryParse(raw.ToString(), out int n) && n > 0)
                    return Enumerable.Range(1, Math.Min(n, 4)).ToArray();
                return [];
        }
    }

    /// <summary>camera_routes: channels: 2 → канал №2; channels: [1,2] → два канала (pair).</summary>
    internal static int[] ParseRouteChannels(object? raw)
    {
        switch (raw)
        {
            case null:
                return [];
            case int channel when channel is >= 1 and <= 4:
                return [channel];
            case long channel when channel is >= 1 and <= 4:
                return [(int)channel];
            case IEnumerable<object> objects:
                return ParseChannelList(objects);
            case System.Collections.IEnumerable enumerable when raw is not string:
                return ParseChannelList(enumerable.Cast<object>());
            default:
                if (int.TryParse(raw.ToString(), out int ch) && ch is >= 1 and <= 4)
                    return [ch];
                return [];
        }
    }

    private static int[] ParseChannelList(IEnumerable<object> values)
    {
        var channels = new List<int>(4);
        foreach (object value in values)
        {
            if (!TryParseChannelNumber(value, out int ch))
                continue;

            if (ch is >= 1 and <= 4 && !channels.Contains(ch))
                channels.Add(ch);
        }

        return channels.ToArray();
    }

    private static bool TryParseChannelNumber(object value, out int channel)
    {
        switch (value)
        {
            case int i:
                channel = i;
                return true;
            case long l:
                channel = (int)l;
                return true;
            case byte b:
                channel = b;
                return true;
            default:
                return int.TryParse(value.ToString(), out channel);
        }
    }

    private sealed class LightHardwareYamlRoot
    {
        public LightHardwareYamlSection? LightHardware { get; set; }
    }

    private sealed class LightHardwareYamlSection
    {
        public bool? InitializeOnStartup { get; set; }

        public List<LightHardwareYamlDevice>? Devices { get; set; }

        public List<LightHardwareYamlCameraRoute>? CameraRoutes { get; set; }
    }

    private sealed class LightHardwareYamlDevice
    {
        public string? Id { get; set; }

        public bool? Enabled { get; set; }

        public string? Type { get; set; }

        public string? ComPort { get; set; }

        public string? Ip { get; set; }

        public object? Channels { get; set; }
    }

    private sealed class LightHardwareYamlCameraRoute
    {
        public int? CameraNumber { get; set; }

        public string? DeviceId { get; set; }

        public object? Channels { get; set; }
    }
}

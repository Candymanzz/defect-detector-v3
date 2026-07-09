using LightServer.Configuration;

namespace LightServer.Services;

/// <summary>Lookup устройств и camera_routes из light_hardware.yaml (с hot-reload при изменении файла).</summary>
public sealed class LightHardwareRegistry
{
    private readonly object _sync = new();
    private LightHardwareOptions _options;
    private IReadOnlyDictionary<string, LightHardwareDeviceEntry> _devicesById;
    private IReadOnlyDictionary<int, LightCameraRouteEntry> _routesByCamera;
    private DateTime _configLastWriteUtc = DateTime.MinValue;

    public LightHardwareRegistry(LightHardwareLoadResult load)
    {
        _options = load.Options;
        ConfigPath = load.ConfigPath;
        LoadedFromYaml = load.LoadedFromYaml;
        (_devicesById, _routesByCamera) = BuildIndexes(_options);

        if (!string.IsNullOrEmpty(ConfigPath) && File.Exists(ConfigPath))
            _configLastWriteUtc = File.GetLastWriteTimeUtc(ConfigPath);
    }

    public LightHardwareOptions Options
    {
        get
        {
            EnsureFresh();
            return _options;
        }
    }

    public string? ConfigPath { get; private set; }

    public bool LoadedFromYaml { get; private set; }

    public void EnsureFresh()
    {
        // Hot-reload camera_routes/devices для /pair и /routes; COM-банк перечитывает только при рестарте.
        if (string.IsNullOrEmpty(ConfigPath) || !File.Exists(ConfigPath))
            return;

        DateTime writeUtc = File.GetLastWriteTimeUtc(ConfigPath);
        if (writeUtc <= _configLastWriteUtc)
            return;

        lock (_sync)
        {
            writeUtc = File.GetLastWriteTimeUtc(ConfigPath);
            if (writeUtc <= _configLastWriteUtc)
                return;

            try
            {
                LightHardwareOptions fresh = LightConfigLoader.ParseFile(ConfigPath);
                _options = fresh;
                (_devicesById, _routesByCamera) = BuildIndexes(fresh);
                LoadedFromYaml = true;
                _configLastWriteUtc = writeUtc;
            }
            catch
            {
                // Оставляем последний успешно загруженный конфиг.
            }
        }
    }

    public bool TryGetDevice(string deviceId, out LightHardwareDeviceEntry device)
    {
        EnsureFresh();
        return _devicesById.TryGetValue(deviceId, out device!);
    }

    public bool TryGetRoute(int cameraNumber, out LightCameraRouteEntry route)
    {
        EnsureFresh();
        return _routesByCamera.TryGetValue(cameraNumber, out route!);
    }

    public IReadOnlyList<LightCameraRouteEntry> CameraRoutes
    {
        get
        {
            EnsureFresh();
            return _options.CameraRoutes;
        }
    }

    public IEnumerable<int> PairCameraNumbers =>
        CameraRoutes.Where(static r => r.Channels.Length >= 2).Select(static r => r.CameraNumber);

    public IEnumerable<int> SingleCameraNumbers =>
        CameraRoutes.Where(static r => r.Channels.Length == 1).Select(static r => r.CameraNumber);

    private static (IReadOnlyDictionary<string, LightHardwareDeviceEntry>, IReadOnlyDictionary<int, LightCameraRouteEntry>) BuildIndexes(
        LightHardwareOptions options)
    {
        var devicesById = options.Devices
            .Where(static d => d.Id.Length > 0)
            .GroupBy(static d => d.Id, StringComparer.OrdinalIgnoreCase)
            .ToDictionary(static g => g.Key, static g => g.First(), StringComparer.OrdinalIgnoreCase);

        var routesByCamera = options.CameraRoutes
            .GroupBy(static r => r.CameraNumber)
            .ToDictionary(static g => g.Key, static g => g.First());

        return (devicesById, routesByCamera);
    }
}

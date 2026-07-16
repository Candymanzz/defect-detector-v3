using System.Collections.Concurrent;
using LightServer;
using Microsoft.Extensions.Logging;
using Microsoft.Extensions.Options;
using MvCameraControl;

namespace LightServer.Services;

/// <summary>
/// Банк Ethernet MV-LE: постоянные GigE-сессии, параллельный On/Off по IP (broadcast на устройстве).
/// COM не используется.
/// </summary>
public sealed class EthernetMvLeBank : IDisposable
{
    private const DeviceTLayerType NetworkLayers =
        DeviceTLayerType.MvGigEDevice
        | DeviceTLayerType.MvUsbDevice
        | DeviceTLayerType.MvGenTLCameraLinkDevice
        | DeviceTLayerType.MvGenTLCXPDevice
        | DeviceTLayerType.MvGenTLXoFDevice;

    private readonly LightHardwareRegistry _hardware;
    private readonly IOptions<SerialLightOptions> _serial;
    private readonly ILoggerFactory _loggerFactory;
    private readonly ILogger<EthernetMvLeBank> _log;
    private readonly object _initLock = new();
    private readonly Dictionary<string, IsolatedEthernetLight> _byIp = new(StringComparer.OrdinalIgnoreCase);
    private readonly Dictionary<string, string> _skipped = new(StringComparer.OrdinalIgnoreCase);
    private bool _initialized;
    private bool _disposed;

    public EthernetMvLeBank(
        LightHardwareRegistry hardware,
        IOptions<SerialLightOptions> serial,
        ILoggerFactory loggerFactory)
    {
        _hardware = hardware;
        _serial = serial;
        _loggerFactory = loggerFactory;
        _log = loggerFactory.CreateLogger<EthernetMvLeBank>();
    }

    public bool IsInitialized => _initialized && _byIp.Count > 0;

    public int ReadyCount => _byIp.Count;

    public IReadOnlyDictionary<string, string> Skipped => _skipped;

    public bool TryGet(string ipAddress, out IsolatedEthernetLight? light)
    {
        EnsureInitialized();
        return _byIp.TryGetValue(ipAddress.Trim(), out light);
    }

    public (bool ok, string? error) EnsureInitialized()
    {
        lock (_initLock)
        {
            if (_initialized && _byIp.Count > 0)
                return (true, null);
            if (_initialized && _byIp.Count == 0 && _skipped.Count > 0)
                return (false, "Ethernet bank: ни одно MV-LE не открыто.");

            return InitializeLocked();
        }
    }

    private (bool ok, string? error) InitializeLocked()
    {
        _hardware.EnsureFresh();
        DisposeLightsUnlocked();
        _skipped.Clear();

        var targets = _hardware.Options.Devices
            .Where(static d => d.Enabled && d.IsEthernet && !string.IsNullOrWhiteSpace(d.Ip))
            .GroupBy(static d => d.Ip!.Trim(), StringComparer.OrdinalIgnoreCase)
            .Select(static g => g.First())
            .ToList();

        if (targets.Count == 0)
        {
            _initialized = true;
            _log.LogInformation("Ethernet bank: нет ethernet-устройств в light_hardware.yaml");
            return (true, null);
        }

        int ret = DeviceEnumerator.EnumDevices(NetworkLayers, out List<IDeviceInfo> list);
        if (ret != MvError.MV_OK || list == null)
        {
            _initialized = false;
            return (false, $"Ethernet bank: EnumDevices 0x{ret:x8}");
        }

        _log.LogInformation("Ethernet bank: EnumDevices count={Count}, targets={Targets}", list.Count, targets.Count);

        foreach (LightHardwareDeviceEntry device in targets)
        {
            string ip = device.Ip!.Trim();
            int idx = FindByIp(list, ip);
            if (idx < 0)
            {
                _skipped[ip] = "не найден в EnumDevices";
                _log.LogWarning("Ethernet bank: {Id}@{Ip} не найден", device.Id, ip);
                continue;
            }

            var light = new IsolatedEthernetLight(
                device.Id,
                ip,
                device.Channels.Length > 0 ? device.Channels : [1, 2, 3, 4],
                _serial,
                _loggerFactory.CreateLogger<IsolatedEthernetLight>());

            var (ok, err) = light.OpenFromEnumeration(list, idx);
            if (!ok)
            {
                light.Dispose();
                _skipped[ip] = err ?? "open failed";
                _log.LogWarning("Ethernet bank: open failed {Error}", err);
                continue;
            }

            _byIp[ip] = light;
        }

        _initialized = true;
        if (_byIp.Count == 0)
            return (false, "Ethernet bank: не удалось открыть ни одного MV-LE.");

        _log.LogInformation(
            "Ethernet bank готов: {Ready}/{Total} ({Ips})",
            _byIp.Count,
            targets.Count,
            string.Join(", ", _byIp.Keys));
        return (true, null);
    }

    /// <summary>
    /// Параллельный On по всем GigE (broadcast на каждом IP). Постоянные сессии — без Open/Close.
    /// </summary>
    public IReadOnlyList<(string Ip, bool Ok, string Message)> ApplyAllOn(IReadOnlyDictionary<string, int[]>? brightnessByIp)
    {
        var (ready, err) = EnsureInitialized();
        if (!ready)
            return [("*", false, err ?? "not ready")];

        var lights = _byIp.Values.ToList();
        if (lights.Count == 0)
            return [("*", false, "no ethernet lights")];

        var results = new ConcurrentDictionary<string, (bool Ok, string Message)>(StringComparer.OrdinalIgnoreCase);

        Parallel.ForEach(lights, light =>
        {
            int[] brightness = ResolveBrightness(light, brightnessByIp);
            var (ok, msg) = light.ApplyDirectOn(brightness);
            results[light.IpAddress] = (ok, msg ?? (ok ? "On" : "On failed"));
        });

        return lights
            .Select(l =>
            {
                var r = results.GetValueOrDefault(l.IpAddress, (Ok: false, Message: "missing"));
                return (l.IpAddress, r.Ok, r.Message);
            })
            .ToList();
    }

    public IReadOnlyList<(string Ip, bool Ok, string Message)> ApplyAllOff()
    {
        var (ready, err) = EnsureInitialized();
        if (!ready)
            return [("*", false, err ?? "not ready")];

        var lights = _byIp.Values.ToList();
        if (lights.Count == 0)
            return [("*", true, "empty")];

        var results = new ConcurrentDictionary<string, (bool Ok, string Message)>(StringComparer.OrdinalIgnoreCase);

        Parallel.ForEach(lights, light =>
        {
            var (ok, msg) = light.ApplyOff();
            results[light.IpAddress] = (ok, msg ?? (ok ? "Off" : "Off failed"));
        });

        return lights
            .Select(l =>
            {
                var r = results.GetValueOrDefault(l.IpAddress, (Ok: false, Message: "missing"));
                return (l.IpAddress, r.Ok, r.Message);
            })
            .ToList();
    }

    /// <summary>On одного IP через открытую сессию (для /pair без повторного Open).</summary>
    public (bool ok, string? error) ApplyOnIp(string ip, int[] deviceChannels, int[] brightness)
    {
        EnsureInitialized();
        if (!_byIp.TryGetValue(ip.Trim(), out IsolatedEthernetLight? light))
            return (false, $"Ethernet bank: {ip} не в банке");

        int[] merged = MergeIntoSessionChannels(light.Channels, deviceChannels, brightness);
        CameraFlashBrightnessCache.RememberNetworkFull(ip, light.Channels, merged);
        return light.ApplyDirectOn(merged);
    }

    private static int[] MergeIntoSessionChannels(int[] sessionChannels, int[] sourceChannels, int[] sourceBrightness)
    {
        var merged = new int[sessionChannels.Length];
        Array.Fill(merged, 255);
        for (int i = 0; i < sourceChannels.Length && i < sourceBrightness.Length; i++)
        {
            int idx = Array.IndexOf(sessionChannels, sourceChannels[i]);
            if (idx >= 0)
                merged[idx] = sourceBrightness[i];
        }

        return merged;
    }

    private static int[] ResolveBrightness(
        IsolatedEthernetLight light,
        IReadOnlyDictionary<string, int[]>? brightnessByIp)
    {
        if (brightnessByIp != null && brightnessByIp.TryGetValue(light.IpAddress, out int[]? custom)
            && custom.Length > 0)
        {
            return custom.Length == light.Channels.Length
                ? custom
                : MergeIntoSessionChannels(light.Channels, light.Channels, custom);
        }

        return CameraFlashBrightnessCache.GetNetworkOrDefault(light.IpAddress, light.Channels.Length);
    }

    private static int FindByIp(List<IDeviceInfo> list, string ip)
    {
        for (int i = 0; i < list.Count; i++)
        {
            string? current = GetDeviceIp(list[i]);
            if (current != null && string.Equals(current, ip, StringComparison.OrdinalIgnoreCase))
                return i;
        }

        return -1;
    }

    private static string? GetDeviceIp(IDeviceInfo deviceInfo)
    {
        var prop = deviceInfo.GetType().GetProperty("CurrentIp");
        if (prop == null)
            return null;
        object? raw = prop.GetValue(deviceInfo);
        return raw switch
        {
            string s => s.Trim(),
            uint ui => $"{(ui >> 24) & 0xFF}.{(ui >> 16) & 0xFF}.{(ui >> 8) & 0xFF}.{ui & 0xFF}",
            int i => GetDeviceIpFromUint(unchecked((uint)i)),
            _ => raw?.ToString()?.Trim()
        };
    }

    private static string GetDeviceIpFromUint(uint ui) =>
        $"{(ui >> 24) & 0xFF}.{(ui >> 16) & 0xFF}.{(ui >> 8) & 0xFF}.{ui & 0xFF}";

    private void DisposeLightsUnlocked()
    {
        foreach (IsolatedEthernetLight light in _byIp.Values)
            light.Dispose();
        _byIp.Clear();
    }

    public void Dispose()
    {
        if (_disposed)
            return;
        _disposed = true;
        lock (_initLock)
            DisposeLightsUnlocked();
    }
}

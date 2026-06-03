using LightServer;
using Microsoft.Extensions.Options;
using MvCameraControl;

namespace LightServer.Services;

/// <summary>
/// Долгоживущие объекты MvCameraControl для COM MV-LE: кэш EnumDevices и опционально открытый <see cref="IDevice"/>.
/// Регистрируется в DI как Singleton (как <see cref="IoControllerComService"/>).
/// </summary>
public sealed class MvLeSerialLightSessions : IDisposable
{
    private readonly SerialLightOptions _options;
    private readonly object _lock = new();
    private bool _disposed;

    private CachedEnumeration? _enumeration;
    private readonly Dictionary<string, OpenSession> _openSessions = new(StringComparer.OrdinalIgnoreCase);
    private IReadOnlyList<string>? _pinnedPorts;

    private const string BrightnessNode = "LightBrightness";

    public MvLeSerialLightSessions(IOptions<SerialLightOptions> options) =>
        _options = options.Value;

    /// <summary>Перечисление с SetEnumSerialPorts; повторный вызов с тем же набором портов берёт кэш.</summary>
    public (bool ok, string? err, List<IDeviceInfo>? list) GetSerialDeviceList(IReadOnlyList<string> portList)
    {
        lock (_lock)
        {
            if (_disposed)
                return (false, "MvLeSerialLightSessions остановлен.", null);

            string portsKey = BuildPortsKey(portList);
            bool portsChanged = _enumeration == null
                || !string.Equals(_enumeration.PortsKey, portsKey, StringComparison.OrdinalIgnoreCase);

            if (portsChanged && _openSessions.Count > 0 && _pinnedPorts != null
                && !string.Equals(BuildPortsKey(_pinnedPorts), portsKey, StringComparison.OrdinalIgnoreCase))
            {
                return (false,
                    "Нельзя сменить набор COM-портов при открытых сессиях. Используйте POST /api/com/light/apply или перезапустите сервер.",
                    null);
            }

            if (portsChanged)
                _enumeration = null;

            if (_enumeration != null
                && _enumeration.PortsKey == portsKey
                && !_enumeration.IsExpired(_options.EnumCacheSeconds))
            {
                return (true, null, _enumeration.Devices);
            }

            var (ok, err, list) = EnumerateFresh(portList);
            if (!ok || list == null)
            {
                _enumeration = null;
                return (false, err, null);
            }

            _enumeration = new CachedEnumeration(portsKey, list, DateTime.UtcNow);
            return (true, null, list);
        }
    }

    /// <summary>Индекс устройства на COM из кэша перечисления (O(1)).</summary>
    public int ResolveDeviceIndex(List<IDeviceInfo> list, string comPort)
    {
        lock (_lock)
        {
            string comKey = NormalizeComKey(comPort);
            if (_enumeration?.TryGetIndex(comKey, out int cached) == true)
                return cached;

            return FindSerialDeviceIndex(list, comKey);
        }
    }

    /// <summary>
    /// <paramref name="keepOpen"/> — вернуть уже открытый device; иначе новый (caller закрывает после применения).
    /// </summary>
    public (IDevice? device, string? error, bool openedBySession, bool sessionCreated, MvLeDeviceCapabilities? caps, object? syncRoot) AcquireDevice(
        List<IDeviceInfo> list,
        int deviceIndex,
        string comPort,
        bool keepOpen)
    {
        if (deviceIndex < 0 || deviceIndex >= list.Count)
            return (null, $"Invalid deviceIndex {deviceIndex} (count {list.Count}).", false, false, null, null);

        if (!keepOpen)
        {
            IDevice ephemeral = DeviceFactory.CreateDevice(list[deviceIndex]);
            return (ephemeral, null, false, false, ProbeCapabilities(ephemeral), new object());
        }

        lock (_lock)
        {
            if (_disposed)
                return (null, "MvLeSerialLightSessions остановлен.", false, false, null, null);

            string comKey = NormalizeComKey(comPort);
            if (_openSessions.TryGetValue(comKey, out OpenSession? existing)
                && existing.DeviceIndex == deviceIndex)
            {
                return (existing.Device, null, true, false, existing.Capabilities, existing.SyncRoot);
            }

            ReleaseOpenSessionUnsafe(comKey);

            IDevice device = DeviceFactory.CreateDevice(list[deviceIndex]);
            int ret = device.Open();
            if (ret != MvError.MV_OK)
            {
                device.Dispose();
                InvalidateEnumeration();
                return (null, $"Open: 0x{ret:x8}", false, false, null, null);
            }

            var caps = ProbeCapabilities(device);
            var flashSync = MvLeFlashSync.Probe(device, _options.FlashSyncMode);
            var session = new OpenSession(comKey, deviceIndex, device, caps, flashSync);
            _openSessions[comKey] = session;
            return (device, null, true, true, caps, session.SyncRoot);
        }
    }

    /// <summary>Зафиксировать набор COM для перечисления (банк устройств при старте).</summary>
    public void PinEnumerationPorts(IReadOnlyList<string> portList)
    {
        lock (_lock)
            _pinnedPorts = portList
                .Select(NormalizeComPort)
                .Where(static p => p.Length > 0)
                .Distinct(StringComparer.OrdinalIgnoreCase)
                .OrderBy(static p => p, StringComparer.OrdinalIgnoreCase)
                .ToList();
    }

    public IReadOnlyList<string> GetPinnedPorts()
    {
        lock (_lock)
            return _pinnedPorts ?? [];
    }

    public IReadOnlyList<string> GetOpenComPorts()
    {
        lock (_lock)
            return _openSessions.Keys.OrderBy(static k => k, StringComparer.OrdinalIgnoreCase).ToList();
    }

    public OpenSession? GetOpenSession(string comPort)
    {
        lock (_lock)
        {
            string comKey = NormalizeComKey(comPort);
            return _openSessions.TryGetValue(comKey, out OpenSession? session) ? session : null;
        }
    }

    public void ReleaseOpenSessionUnless(string comPort)
    {
        // Совместимость со старым API: сессии по COM независимы, ничего не закрываем.
    }

    private void ReleaseOpenSessionUnsafe(string? comKey = null)
    {
        if (comKey == null)
        {
            foreach (OpenSession s in _openSessions.Values)
                s.Dispose();
            _openSessions.Clear();
            return;
        }

        string key = NormalizeComKey(comKey);
        if (_openSessions.Remove(key, out OpenSession? session))
            session.Dispose();
    }

    public void RecordApplyState(string comPort, int[] channels, string source, int[] brightness)
    {
        lock (_lock)
        {
            if (GetOpenSession(comPort) is { } session)
                session.ApplyState.Update(channels, source, brightness);
        }
    }

    private static MvLeDeviceCapabilities ProbeCapabilities(IDevice device) =>
        new(
            device.Parameters.GetEnumValue("LightControllerSelector", out IEnumValue _) == MvError.MV_OK,
            device.Parameters.GetIntValue(BrightnessNode, out IIntValue _) == MvError.MV_OK);

    public void InvalidateEnumeration()
    {
        lock (_lock)
            _enumeration = null;
    }

    public void InvalidateOnDeviceError(string? comPort = null)
    {
        lock (_lock)
        {
            _enumeration = null;
            if (comPort != null)
                ReleaseOpenSessionUnsafe(comPort);
            else
                ReleaseOpenSessionUnsafe();
        }
    }

    public void Dispose()
    {
        lock (_lock)
        {
            if (_disposed)
                return;
            _disposed = true;
            ReleaseOpenSessionUnsafe();
            _enumeration = null;
            _pinnedPorts = null;
        }
    }

    private (bool ok, string? err, List<IDeviceInfo>? list) EnumerateFresh(IReadOnlyList<string> userPortList)
    {
        var (resolveOk, resolveErr, sdkPorts) = ResolveSdkSerialPortNames(userPortList);
        if (!resolveOk)
            return (false, resolveErr, null);

        int setRet = DeviceEnumerator.SetEnumSerialPorts(sdkPorts);
        if (setRet != MvError.MV_OK)
            return (false, $"SetEnumSerialPorts([{string.Join(", ", sdkPorts)}]) failed: 0x{setRet:x8}", null);

        const DeviceTLayerType serialLayers =
            DeviceTLayerType.MvCameraLinkDevice
            | DeviceTLayerType.MvVirGigEDevice
            | DeviceTLayerType.MvVirUsbDevice;

        int ret = DeviceEnumerator.EnumDevices(serialLayers, out List<IDeviceInfo> list);
        if (ret != MvError.MV_OK)
            return (false, $"EnumDevices failed: 0x{ret:x8}", null);

        return (true, null, list);
    }

    private static (bool ok, string? err, List<string> ports) ResolveSdkSerialPortNames(IReadOnlyList<string> userPortList)
    {
        int hostRet = DeviceEnumerator.GetSerialPortList(out List<string> hostPorts);
        hostPorts ??= [];

        var resolved = new List<string>();
        foreach (string raw in userPortList)
        {
            string com = NormalizeComPort(raw);
            if (com.Length == 0)
                continue;

            string marker = $"COM_Port#{com}";
            string? sdkName = hostPorts.FirstOrDefault(h =>
                string.Equals(h, marker, StringComparison.OrdinalIgnoreCase)
                || h.EndsWith($"#{com}", StringComparison.OrdinalIgnoreCase));

            resolved.Add(sdkName ?? marker);
        }

        if (resolved.Count == 0)
            return (false, "Не указаны COM-порты для перечисления.", []);

        if (hostRet != MvError.MV_OK && hostPorts.Count == 0)
            return (true, null, resolved.Distinct(StringComparer.OrdinalIgnoreCase).ToList());

        return (true, null, resolved.Distinct(StringComparer.OrdinalIgnoreCase).ToList());
    }

    private static string BuildPortsKey(IReadOnlyList<string> portList) =>
        string.Join("|", portList.Select(NormalizeComPort).Where(static p => p.Length > 0).OrderBy(static p => p, StringComparer.OrdinalIgnoreCase));

    private static string NormalizeComKey(string comPort)
    {
        string com = NormalizeComPort(comPort);
        return com.Length > 0 ? com : comPort.Trim().ToUpperInvariant();
    }

    private static string NormalizeComPort(string p) => MvsComPortEnumerator.NormalizeComPort(p);

    private sealed class CachedEnumeration
    {
        private readonly Dictionary<string, int> _comToIndex;

        public string PortsKey { get; }
        public List<IDeviceInfo> Devices { get; }
        public DateTime CachedAt { get; }

        public CachedEnumeration(string portsKey, List<IDeviceInfo> devices, DateTime cachedAt)
        {
            PortsKey = portsKey;
            Devices = devices;
            CachedAt = cachedAt;
            _comToIndex = BuildComIndexMap(devices);
        }

        public bool TryGetIndex(string comKey, out int index) =>
            _comToIndex.TryGetValue(comKey, out index);

        public bool IsExpired(int cacheSeconds) =>
            cacheSeconds <= 0 || DateTime.UtcNow - CachedAt > TimeSpan.FromSeconds(cacheSeconds);

        /// <summary>Только PortID камеры — blob мог содержать чужие COM_Port# и ломать COM2/COM3.</summary>
        private static Dictionary<string, int> BuildComIndexMap(List<IDeviceInfo> list)
        {
            var map = new Dictionary<string, int>(StringComparer.OrdinalIgnoreCase);
            for (int i = 0; i < list.Count; i++)
            {
                if (list[i] is not ICamlDeviceInfo caml)
                    continue;

                string port = NormalizeComPort(caml.PortID);
                if (port.Length > 0)
                    map[port] = i;
            }

            return map;
        }

        private static IEnumerable<string> ExtractComPortsFromBlob(string blob)
        {
            int idx = 0;
            while (idx < blob.Length)
            {
                int start = blob.IndexOf("COM_Port#COM", idx, StringComparison.OrdinalIgnoreCase);
                if (start < 0)
                    break;

                start += "COM_Port#".Length;
                int end = start;
                while (end < blob.Length && (char.IsDigit(blob[end]) || char.IsLetter(blob[end])))
                    end++;

                if (end > start)
                {
                    string port = NormalizeComPort(blob[start..end]);
                    if (port.Length > 0)
                        yield return port;
                }

                idx = end;
            }
        }

        private static string BuildDeviceSearchBlob(IDeviceInfo d)
        {
            var parts = new List<string>(6);
            void Add(string? s)
            {
                if (!string.IsNullOrWhiteSpace(s))
                    parts.Add(s.Trim());
            }

            Add(d.UserDefinedName);
            Add(d.ModelName);
            Add(d.SerialNumber);
            Add(d.ManufacturerName);
            Add(d.TLayerType.ToString());
            if (d is ICamlDeviceInfo caml)
                Add(caml.PortID);

            return string.Join(" ", parts);
        }
    }

    private static int FindSerialDeviceIndex(List<IDeviceInfo> list, string com)
    {
        string marker = $"COM_Port#{com}";

        for (int i = 0; i < list.Count; i++)
        {
            IDeviceInfo d = list[i];
            if (d is ICamlDeviceInfo caml && string.Equals(NormalizeComPort(caml.PortID), com, StringComparison.OrdinalIgnoreCase))
                return i;

            if (BuildDeviceSearchBlobQuick(d).Contains(marker, StringComparison.OrdinalIgnoreCase))
                return i;
        }

        return -1;
    }

    private static string BuildDeviceSearchBlobQuick(IDeviceInfo d)
    {
        if (d is ICamlDeviceInfo caml && !string.IsNullOrWhiteSpace(caml.PortID))
            return $"{d.ModelName} {caml.PortID}";

        return d.ModelName ?? "";
    }

    public sealed class OpenSession : IDisposable
    {
        public string ComKey { get; }
        public int DeviceIndex { get; }
        public IDevice Device { get; }
        public MvLeDeviceCapabilities Capabilities { get; }
        public object SyncRoot { get; } = new();
        public MvLeApplyState ApplyState { get; } = new();
        public MvLeFlashSyncPlan FlashSync { get; }

        public OpenSession(string comKey, int deviceIndex, IDevice device, MvLeDeviceCapabilities capabilities, MvLeFlashSyncPlan flashSync)
        {
            ComKey = comKey;
            DeviceIndex = deviceIndex;
            Device = device;
            Capabilities = capabilities;
            FlashSync = flashSync;
        }

        public void Dispose()
        {
            try
            {
                Device.StreamGrabber.StopGrabbing();
                Device.Close();
            }
            catch
            {
                // ignore on shutdown
            }

            Device.Dispose();
        }
    }
}

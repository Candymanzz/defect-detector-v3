using System.Globalization;
using System.Text.Json;
using LightServer.Models;
using Microsoft.Extensions.Options;
using MvCameraControl;

namespace LightServer.Services;

public sealed class LightControlService
{
    private const string DebugLogPath = @"c:\Users\Administrator\Desktop\LightServer.v2\.cursor\debug.log";
    private const string BrightnessNode = "LightBrightness";
    private const int BrightnessMin = 0;
    private const int BrightnessMax = 255;
    private const int BrightnessDefaultOn = 255;

    /// <summary>Как в Test.exe — сеть/USB/GenTL, без Camera Link (COM перечисляется отдельно).</summary>
    private const DeviceTLayerType NetworkLayers =
        DeviceTLayerType.MvGigEDevice
        | DeviceTLayerType.MvUsbDevice
        | DeviceTLayerType.MvGenTLCameraLinkDevice
        | DeviceTLayerType.MvGenTLCXPDevice
        | DeviceTLayerType.MvGenTLXoFDevice;

    /// <summary>Устройства на COM после SetEnumSerialPorts.</summary>
    private const DeviceTLayerType SerialLayers =
        DeviceTLayerType.MvCameraLinkDevice
        | DeviceTLayerType.MvVirGigEDevice
        | DeviceTLayerType.MvVirUsbDevice;

    /// <summary>Слои для поиска COM_Port#COMx (как в MVS → Serial Port), фильтр портов не сбрасывать.</summary>
    private const DeviceTLayerType ComDiscoveryLayers =
        SerialLayers
        | DeviceTLayerType.MvGigEDevice
        | DeviceTLayerType.MvUsbDevice;

    private readonly SerialLightOptions _serialDefaults;
    private readonly object _sdkLock = new();

    public LightControlService(IOptions<SerialLightOptions> serialOptions) =>
        _serialDefaults = serialOptions.Value;

    public (bool ok, string? error, DeviceListResponse? data) ListNetworkDevices()
    {
        lock (_sdkLock)
        {
            ClearSerialPortFilter();
            return EnumToResponse(NetworkLayers);
        }
    }

    /// <param name="ports">Если null или пусто — из конфига SerialLight:EnumPorts.</param>
    public (bool ok, string? error, DeviceListResponse? data) ListSerialDevices(IReadOnlyList<string>? ports)
    {
        IReadOnlyList<string> portList = NormalizePortList(ports);
        #region agent log
        WriteDebugLog("run1", "H1", "LightControlService.ListSerialDevices:47", "ListSerialDevices entry", new
        {
            requestedPorts = ports?.ToArray(),
            normalizedPorts = portList.ToArray()
        });
        #endregion
        if (portList.Count == 0)
            return (false, "Укажите COM-порты: query ?ports=COM1,COM3 или SerialLight:EnumPorts в appsettings.json.", null);

        lock (_sdkLock)
        {
            var (enumOk, enumErr, devList) = EnumerateWithSerialPorts(portList);
            #region agent log
            WriteDebugLog("run1", "H2", "LightControlService.ListSerialDevices:64", "COM discovery enumeration", new
            {
                enumOk,
                enumErr,
                deviceCount = devList?.Count ?? -1,
                labels = devList?.Select(BuildDeviceSearchBlob).ToArray()
            });
            #endregion
            if (!enumOk || devList == null)
                return (false, enumErr, null);

            var devices = new List<DeviceInfoDto>();
            for (int i = 0; i < devList.Count; i++)
            {
                IDeviceInfo d = devList[i];
                string? matchedCom = TryMatchComPort(d, portList);
                if (matchedCom == null)
                    continue;

                devices.Add(new DeviceInfoDto
                {
                    Index = i,
                    TLayerType = d.TLayerType.ToString(),
                    ModelName = d.ModelName ?? "",
                    SerialNumber = d.SerialNumber ?? "",
                    ComPort = matchedCom
                });
            }

            devices = devices
                .OrderBy(static d => d.ComPort)
                .ThenBy(static d => d.ModelName)
                .ToList();
            #region agent log
            WriteDebugLog("run1", "H5", "LightControlService.ListSerialDevices:139", "ListSerialDevices final response", new
            {
                finalCount = devices.Count,
                devices = devices.Select(d => new
                {
                    d.TLayerType,
                    d.ModelName,
                    d.SerialNumber,
                    d.ComPort
                }).ToArray()
            });
            #endregion

            return (true, null, new DeviceListResponse { Count = devices.Count, Devices = devices });
        }
    }

    public (bool ok, string? error) SetLightNetwork(LightCommandRequest request)
    {
        lock (_sdkLock)
        {
            ClearSerialPortFilter();
            var (enumOk, enumErr, list) = EnumDevicesInternal(NetworkLayers);
            if (!enumOk || list == null)
                return (false, enumErr);

            if (request.DeviceIndex < 0 || request.DeviceIndex >= list.Count)
                return (false, $"Invalid deviceIndex {request.DeviceIndex} (count {list.Count}).");

            return ApplyLightToDevice(DeviceFactory.CreateDevice(list[request.DeviceIndex]), request);
        }
    }

    public (bool ok, string? error) SetLightSerial(LightCommandRequestCom request, IReadOnlyList<string>? enumPorts)
    {
        string? comPort = NormalizeOptionalInput(request.ComPort);
        string source = NormalizeSource(request.LightControllerSource);
        #region agent log
        WriteDebugLog("run1", "H1", "LightControlService.SetLightSerial:165", "SetLightSerial entry", new
        {
            requestComPort = request.ComPort,
            normalizedComPort = comPort,
            source,
            channels = request.Channels,
            enumPorts = enumPorts?.ToArray()
        });
        #endregion
        if (!IsSupportedSource(source))
            return (false, "lightControllerSource должен быть одним из: On, Off, In1..In4, Timer1..Timer4.");

        if (comPort == null)
            return (false, "Укажите comPort (например COM1).");

        IReadOnlyList<string> portList = NormalizePortList(enumPorts);
        if (portList.Count == 0)
            return (false, "Для COM укажите порты перечисления: ?ports=COM1,COM3 или SerialLight:EnumPorts в appsettings.json.");

        lock (_sdkLock)
        {
            var (enumOk, enumErr, list) = EnumerateWithSerialPorts(portList);
            if (!enumOk || list == null)
                return (false, enumErr);

            int idx = FindSerialDeviceIndex(list, comPort);
            #region agent log
            WriteDebugLog("run3", "H9", "LightControlService.SetLightSerial:237", "Find device on COM", new
            {
                comPort,
                idx,
                devices = list.Select(static d => BuildDeviceSearchBlob(d)).ToArray()
            });
            #endregion
            if (idx < 0)
                return (false, BuildComPortNotFoundMessage(comPort, list));

            var applyRequest = new LightCommandRequest
            {
                DeviceIndex = idx,
                LightControllerSource = source,
                Channels = request.Channels,
                Brightness = request.Brightness
            };

            var (applyOk, applyMsg) = ApplyLightToDevice(DeviceFactory.CreateDevice(list[idx]), applyRequest);
            if (applyOk)
                applyMsg = $"MvCameraControl ({BuildDeviceSearchBlob(list[idx])}): {applyMsg}";

            return (applyOk, applyMsg);
        }
    }

    /// <summary>Перечисление с SetEnumSerialPorts — имена портов как в GetSerialPortList (COM_Port#COM1).</summary>
    private (bool ok, string? err, List<IDeviceInfo>? list) EnumerateWithSerialPorts(IReadOnlyList<string> userPortList)
    {
        var (resolveOk, resolveErr, sdkPorts) = ResolveSdkSerialPortNames(userPortList);
        if (!resolveOk)
            return (false, resolveErr, null);

        #region agent log
        WriteDebugLog("run4", "H10", "LightControlService.EnumerateWithSerialPorts", "Resolved SDK serial port names", new
        {
            userPorts = userPortList.ToArray(),
            sdkPorts = sdkPorts.ToArray()
        });
        #endregion

        int setRet = DeviceEnumerator.SetEnumSerialPorts(sdkPorts);
        if (setRet != MvError.MV_OK)
            return (false, $"SetEnumSerialPorts([{string.Join(", ", sdkPorts)}]) failed: 0x{setRet:x8}", null);

        // Сначала только виртуальные COM-устройства (MV-LE), без смешения с GigE-камерой.
        var (serialOk, serialErr, serialList) = EnumDevicesInternal(SerialLayers);
        if (serialOk && serialList is { Count: > 0 })
            return (serialOk, serialErr, serialList);

        return EnumDevicesInternal(ComDiscoveryLayers);
    }

    /// <summary>COM1 из API → COM_Port#COM1 для SetEnumSerialPorts (как GetSerialPortList в MVS).</summary>
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

            if (sdkName != null)
                resolved.Add(sdkName);
            else
                resolved.Add(marker);
        }

        if (resolved.Count == 0)
            return (false, "Не указаны COM-порты для перечисления.", []);

        if (hostRet != MvError.MV_OK && hostPorts.Count == 0)
            return (true, null, resolved.Distinct(StringComparer.OrdinalIgnoreCase).ToList());

        return (true, null, resolved.Distinct(StringComparer.OrdinalIgnoreCase).ToList());
    }

    private static string BuildComPortNotFoundMessage(string comPort, List<IDeviceInfo> list)
    {
        string com = NormalizeComPort(comPort);
        string sdkPort = $"COM_Port#{com}";
        if (list.Count == 0)
            return $"На {sdkPort} ничего не найдено после SetEnumSerialPorts. Закройте MVS Client и повторите.";

        string seen = string.Join("; ", list.Select(static d => BuildDeviceSearchBlob(d)));
        return $"Устройство на {sdkPort} не найдено. EnumDevices: [{seen}]";
    }

    private static int FindSerialDeviceIndex(List<IDeviceInfo> list, string? comPort)
    {
        string? com = NormalizeOptionalInput(comPort);
        if (com == null)
            return -1;

        com = NormalizeComPort(com);
        string marker = $"COM_Port#{com}";

        for (int i = 0; i < list.Count; i++)
        {
            IDeviceInfo d = list[i];
            if (d is ICamlDeviceInfo caml && string.Equals(NormalizeComPort(caml.PortID), com, StringComparison.OrdinalIgnoreCase))
                return i;

            string blob = BuildDeviceSearchBlob(d);
            if (blob.Contains(marker, StringComparison.OrdinalIgnoreCase))
                return i;
        }

        // MV-LE200 на COM1: модель + COM в одной строке (как в дереве MVS).
        for (int i = 0; i < list.Count; i++)
        {
            string blob = BuildDeviceSearchBlob(list[i]);
            if (blob.Contains(com, StringComparison.OrdinalIgnoreCase)
                && blob.Contains("MV-LE", StringComparison.OrdinalIgnoreCase))
                return i;
        }

        return -1;
    }

    /// <summary>Строка как в MVS: COM_Port#COM1 MV-LE200-...(serial).</summary>
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

    private static string BuildDeviceKey(string model, string serial, string? comPort) =>
        $"{model}|{serial}|{comPort}";

    private static string? TryMatchComPort(IDeviceInfo d, IReadOnlyList<string> ports)
    {
        var normalizedPorts = ports.Select(NormalizeComPort).ToArray();

        if (d is ICamlDeviceInfo caml)
        {
            string camlPort = NormalizeComPort(caml.PortID);
            if (normalizedPorts.Contains(camlPort, StringComparer.OrdinalIgnoreCase))
                return camlPort;
        }

        string blob = BuildDeviceSearchBlob(d);
        foreach (string p in normalizedPorts)
        {
            string marker = $"COM_Port#{p}";
            if (blob.Contains(marker, StringComparison.OrdinalIgnoreCase))
                return p;
        }

        return null;
    }

    private IReadOnlyList<string> NormalizePortList(IReadOnlyList<string>? ports)
    {
        if (ports != null && ports.Count > 0)
            return ports.Select(NormalizeComPort).Where(static p => p.Length > 0).Distinct(StringComparer.OrdinalIgnoreCase).ToList();

        return _serialDefaults.EnumPorts
            .Select(NormalizeComPort)
            .Where(static p => p.Length > 0)
            .Distinct(StringComparer.OrdinalIgnoreCase)
            .ToList();
    }

    private static string NormalizeComPort(string p)
    {
        p = p.Trim();
        if (p.Length == 0)
            return "";

        if (p.StartsWith("COM", StringComparison.OrdinalIgnoreCase))
        {
            string tail = p.Length > 3 ? p[3..] : "";
            if (int.TryParse(tail, NumberStyles.Integer, CultureInfo.InvariantCulture, out int n) && n > 0)
                return "COM" + n;
        }

        if (int.TryParse(p, NumberStyles.Integer, CultureInfo.InvariantCulture, out int num) && num > 0)
            return "COM" + num;

        return p;
    }

    /// <summary>Сброс фильтра COM перед перечислением сети (пустой список).</summary>
    private static void ClearSerialPortFilter()
    {
        DeviceEnumerator.SetEnumSerialPorts(new List<string>());
    }

    private (bool ok, string? error, DeviceListResponse? data) EnumToResponse(DeviceTLayerType layers)
    {
        var (ok, err, list) = EnumDevicesInternal(layers);
        if (!ok || list == null)
            return (false, err, null);

        var devices = new List<DeviceInfoDto>(list.Count);
        for (int i = 0; i < list.Count; i++)
        {
            IDeviceInfo d = list[i];
            string? com = d is ICamlDeviceInfo caml ? caml.PortID : null;
            devices.Add(new DeviceInfoDto
            {
                Index = i,
                TLayerType = d.TLayerType.ToString(),
                ModelName = d.ModelName ?? "",
                SerialNumber = d.SerialNumber ?? "",
                ComPort = com
            });
        }

        return (true, null, new DeviceListResponse { Count = devices.Count, Devices = devices });
    }

    private static (bool ok, string? err, List<IDeviceInfo>? list) EnumDevicesInternal(DeviceTLayerType layers)
    {
        int ret = DeviceEnumerator.EnumDevices(layers, out List<IDeviceInfo> list);
        if (ret != MvError.MV_OK)
            return (false, $"EnumDevices failed: 0x{ret:x8}", null);

        return (true, null, list);
    }

    private static (bool ok, string? error) ApplyLightToDevice(IDevice device, LightCommandRequest request)
    {
        int ret = device.Open();
        if (ret != MvError.MV_OK)
        {
            device.Dispose();
            return (false, $"Open: 0x{ret:x8}");
        }

        if (device is IGigEDevice gige)
        {
            ret = gige.GetOptimalPacketSize(out int packetSize);
            if (packetSize > 0)
                device.Parameters.SetIntValue("GevSCPSPacketSize", packetSize);
        }

        try
        {
            string source = NormalizeSource(request.LightControllerSource);
            int[] channels = request.Channels is { Length: > 0 }
                ? request.Channels
                : [1, 2, 3, 4];

            if (request.Brightness is { Length: > 0 } && request.Brightness.Length != channels.Length)
                return (false, $"brightness length ({request.Brightness.Length}) must match channels ({channels.Length}).");

            if (!SupportsLightController(device))
                return (false, "Устройство не поддерживает LightControllerSelector (не MV-LE по этому API).");

            bool hasBrightnessNode = SupportsBrightness(device);
            var appliedBrightness = new int[channels.Length];

            for (int i = 0; i < channels.Length; i++)
            {
                int ch = channels[i];
                if (ch is < 1 or > 4)
                    return (false, $"Invalid channel {ch}. Use 1–4.");

                int brightness = ResolveBrightness(request, i, source, hasBrightnessNode);
                appliedBrightness[i] = brightness;

                if (!ApplyChannel(device, ch, source, brightness, hasBrightnessNode))
                    return (false, $"Failed channel {ch}, source {source}, brightness {brightness}.");
            }

            string brightPart = hasBrightnessNode
                ? $", brightness [{string.Join(", ", appliedBrightness)}]"
                : "";

            return (true, $"Channels [{string.Join(", ", channels)}] -> {source}{brightPart}.");
        }
        finally
        {
            device.StreamGrabber.StopGrabbing();
            device.Close();
            device.Dispose();
        }
    }

    private static int ResolveBrightness(LightCommandRequest request, int index, string source, bool hasNode)
    {
        if (!hasNode)
            return 0;

        if (request.Brightness is { Length: > 0 })
            return ClampBrightness(request.Brightness[index]);

        return source == "On" ? BrightnessDefaultOn : 0;
    }

    private static int ClampBrightness(int value) =>
        Math.Clamp(value, BrightnessMin, BrightnessMax);

    private static bool SupportsLightController(IDevice device) =>
        device.Parameters.GetEnumValue("LightControllerSelector", out IEnumValue _) == MvError.MV_OK;

    private static bool SupportsBrightness(IDevice device) =>
        device.Parameters.GetIntValue(BrightnessNode, out IIntValue _) == MvError.MV_OK;

    private static bool ApplyChannel(IDevice device, int channel, string source, int brightness, bool setBrightness)
    {
        string selector = channel.ToString(CultureInfo.InvariantCulture);

        int ret = device.Parameters.SetEnumValueByString("LightControllerSelector", selector);
        if (ret != MvError.MV_OK)
            ret = device.Parameters.SetEnumValue("LightControllerSelector", (uint)channel);
        if (ret != MvError.MV_OK)
            return false;

        if (setBrightness)
        {
            ret = device.Parameters.SetIntValue(BrightnessNode, brightness);
            if (ret != MvError.MV_OK)
                return false;
        }

        ret = device.Parameters.SetEnumValueByString("LightControllerSource", source);
        if (ret != MvError.MV_OK && TrySourceNumeric(source, out uint src))
            ret = device.Parameters.SetEnumValue("LightControllerSource", src);

        return ret == MvError.MV_OK;
    }

    private static string NormalizeSource(string source)
    {
        if (string.IsNullOrWhiteSpace(source))
            return "On";

        return source.Trim().ToUpperInvariant() switch
        {
            "ON" or "1" or "TRUE" => "On",
            "OFF" or "0" or "FALSE" => "Off",
            _ => source.Trim()
        };
    }

    private static bool TrySourceNumeric(string name, out uint value)
    {
        value = 0;
        switch (name.Trim().ToUpperInvariant())
        {
            case "ON": value = 1; return true;
            case "OFF": value = 255; return true;
            case "IN1": value = 2; return true;
            case "IN2": value = 3; return true;
            case "IN3": value = 4; return true;
            case "IN4": value = 5; return true;
            case "TIMER1": value = 14; return true;
            case "TIMER2": value = 15; return true;
            case "TIMER3": value = 16; return true;
            case "TIMER4": value = 17; return true;
            default: return false;
        }
    }

    private static string? NormalizeOptionalInput(string? value)
    {
        if (string.IsNullOrWhiteSpace(value))
            return null;

        string v = value.Trim();
        if (string.Equals(v, "string", StringComparison.OrdinalIgnoreCase)
            || string.Equals(v, "null", StringComparison.OrdinalIgnoreCase))
            return null;

        return v;
    }

    private static bool IsSupportedSource(string value) =>
        value.Equals("On", StringComparison.OrdinalIgnoreCase)
        || value.Equals("Off", StringComparison.OrdinalIgnoreCase)
        || value.Equals("In1", StringComparison.OrdinalIgnoreCase)
        || value.Equals("In2", StringComparison.OrdinalIgnoreCase)
        || value.Equals("In3", StringComparison.OrdinalIgnoreCase)
        || value.Equals("In4", StringComparison.OrdinalIgnoreCase)
        || value.Equals("Timer1", StringComparison.OrdinalIgnoreCase)
        || value.Equals("Timer2", StringComparison.OrdinalIgnoreCase)
        || value.Equals("Timer3", StringComparison.OrdinalIgnoreCase)
        || value.Equals("Timer4", StringComparison.OrdinalIgnoreCase);

    private static void WriteDebugLog(string runId, string hypothesisId, string location, string message, object data)
    {
        try
        {
            string? logDir = Path.GetDirectoryName(DebugLogPath);
            if (!string.IsNullOrWhiteSpace(logDir))
                Directory.CreateDirectory(logDir);

            var payload = new
            {
                id = Guid.NewGuid().ToString("N"),
                timestamp = DateTimeOffset.UtcNow.ToUnixTimeMilliseconds(),
                runId,
                hypothesisId,
                location,
                message,
                data
            };

            string line = JsonSerializer.Serialize(payload) + Environment.NewLine;
            File.AppendAllText(DebugLogPath, line);
        }
        catch (Exception ex)
        {
            Console.Error.WriteLine($"[agent-log-fail] {location}: {ex.GetType().Name}: {ex.Message}");
        }
    }
}

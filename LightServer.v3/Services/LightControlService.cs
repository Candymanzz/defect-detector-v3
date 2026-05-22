using System.Diagnostics;
using System.Globalization;
using LightServer.Models;
using Microsoft.Extensions.Logging;
using Microsoft.Extensions.Options;
using MvCameraControl;

namespace LightServer.Services;

public sealed class LightControlService
{
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
    private readonly MvLeSerialLightSessions _serialSessions;
    private readonly ILogger<LightControlService> _log;
    private readonly object _sdkLock = new();

    public LightControlService(
        IOptions<SerialLightOptions> serialOptions,
        MvLeSerialLightSessions serialSessions,
        ILogger<LightControlService> log)
    {
        _serialDefaults = serialOptions.Value;
        _serialSessions = serialSessions;
        _log = log;
        if (_serialDefaults.DisableSdkLock)
            _log.LogWarning("SerialLight:DisableSdkLock=true — SDK/COM без lock (эксперимент, возможны гонки и сбои)");
    }

    private void RunSdkLocked(Action action)
    {
        if (_serialDefaults.DisableSdkLock)
        {
            action();
            return;
        }

        lock (_sdkLock)
            action();
    }

    public (bool ok, string? error, DeviceListResponse? data) ListNetworkDevices()
    {
        (bool ok, string? error, DeviceListResponse? data) result = (false, "uninitialized", null);
        RunSdkLocked(() =>
        {
            ClearSerialPortFilter();
            result = EnumToResponse(NetworkLayers);
        });
        return result;
    }

    /// <param name="ports">Если null или пусто — из конфига SerialLight:EnumPorts.</param>
    public (bool ok, string? error, DeviceListResponse? data) ListSerialDevices(IReadOnlyList<string>? ports)
    {
        IReadOnlyList<string> portList = NormalizePortList(ports);
        if (portList.Count == 0)
            return (false, "Укажите COM-порты: query ?ports=COM1,COM3 или SerialLight:EnumPorts в appsettings.json.", null);

        (bool ok, string? error, DeviceListResponse? data) result = (false, "uninitialized", null);
        RunSdkLocked(() =>
        {
            var (enumOk, enumErr, devList) = _serialSessions.GetSerialDeviceList(portList);
            if (!enumOk || devList == null)
            {
                result = (false, enumErr, null);
                return;
            }

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

            result = (true, null, new DeviceListResponse { Count = devices.Count, Devices = devices });
        });
        return result;
    }

    public (bool ok, string? error) SetLightNetwork(LightCommandRequest request)
    {
        ClearSerialPortFilter();
        var (enumOk, enumErr, list) = EnumDevicesInternal(NetworkLayers);
        if (!enumOk || list == null)
            return (false, enumErr);

        if (request.DeviceIndex < 0 || request.DeviceIndex >= list.Count)
            return (false, $"Invalid deviceIndex {request.DeviceIndex} (count {list.Count}).");

        IDevice device = DeviceFactory.CreateDevice(list[request.DeviceIndex]);
        var caps = new MvLeDeviceCapabilities(
            device.Parameters.GetEnumValue("LightControllerSelector", out IEnumValue _) == MvError.MV_OK,
            device.Parameters.GetIntValue(BrightnessNode, out IIntValue _) == MvError.MV_OK);
        return ApplyLightToDevice(device, request, caps, new object(), alreadyOpen: false, leaveOpen: false);
    }

    public (bool ok, string? error) SetLightSerial(LightCommandRequestCom request, IReadOnlyList<string>? enumPorts)
    {
        string? comPort = NormalizeOptionalInput(request.ComPort);
        string source = NormalizeSource(request.LightControllerSource);
        if (!IsSupportedSource(source))
            return (false, "lightControllerSource должен быть одним из: On, Off, In1..In4, Timer1..Timer4.");

        if (comPort == null)
            return (false, "Укажите comPort (например COM1).");

        IReadOnlyList<string> portList = NormalizePortList(enumPorts);
        if (portList.Count == 0)
            return (false, "Для COM укажите порты перечисления: ?ports=COM1,COM3 или SerialLight:EnumPorts в appsettings.json.");


        (bool ok, string? error) result = (false, "uninitialized");
        RunSdkLocked(() =>
        {
            var sw = Stopwatch.StartNew();
            var (enumOk, enumErr, list) = _serialSessions.GetSerialDeviceList(portList);
            if (!enumOk || list == null)
            {
                result = (false, enumErr);
                return;
            }

            int idx = _serialSessions.ResolveDeviceIndex(list, comPort);
            if (idx < 0)
            {
                result = (false, BuildComPortNotFoundMessage(comPort, list));
                return;
            }

            var applyRequest = new LightCommandRequest
            {
                DeviceIndex = idx,
                LightControllerSource = source,
                Channels = request.Channels,
                Brightness = request.Brightness
            };

            bool keepOpen = _serialDefaults.KeepDeviceOpen;
            var (device, openErr, fromSession, sessionCreated, caps, syncRoot) =
                _serialSessions.AcquireDevice(list, idx, comPort, keepOpen);
            if (device == null)
            {
                result = (false, openErr);
                return;
            }

            MvLeDeviceCapabilities deviceCaps = caps ?? new MvLeDeviceCapabilities(false, false);
            object deviceLock = syncRoot ?? new object();
            MvLeSerialLightSessions.OpenSession? session = _serialSessions.GetOpenSession(comPort);

            MvLeFlashSyncPlan flashSync = session?.FlashSync
                ?? MvLeFlashSync.Probe(device, _serialDefaults.FlashSyncMode);
            flashSync.UseSdkLock = !_serialDefaults.DisableSdkLock;
            string lockNote = _serialDefaults.DisableSdkLock ? " no-sdk-lock" : "";

            if (!TryBuildChannelPlan(applyRequest, deviceCaps, source, session?.ApplyState, out int[] channels, out int[] appliedBrightness, out bool writeBrightness, out string? planErr))
            {
                result = (false, planErr);
                return;
            }

            if (sessionCreated
                && session != null
                && _serialDefaults.PreconfigureBrightnessOnOpen
                && deviceCaps.HasBrightness
                && source.Equals("On", StringComparison.OrdinalIgnoreCase))
            {
                int[] primeChannels = [1, 2, 3, 4];
                int[] primeBrightness = ExpandBrightness(appliedBrightness, primeChannels.Length);
                if (!MvLeFlashSync.PrepareHardware(device, deviceLock, flashSync, primeChannels, primeBrightness, out int primeFail))
                {
                    result = (false, $"Prime channels failed on ch{primeFail}.");
                    return;
                }

                if (flashSync.UseDeferredTimer || flashSync.UseHoldTimerRise)
                    session.ApplyState.MarkArmed(primeChannels, primeBrightness, flashSync.TimerArmSource);
                else
                    session.ApplyState.Update(primeChannels, "Off", primeBrightness);
            }
            else if (sessionCreated
                && session != null
                && _serialDefaults.PreconfigureBrightnessOnOpen
                && (flashSync.UseDeferredTimer || flashSync.UseHoldTimerRise))
            {
                int[] primeChannels = [1, 2, 3, 4];
                if (!MvLeFlashSync.PrepareHardware(device, deviceLock, flashSync, primeChannels, brightness: null, out int primeFail))
                {
                    result = (false, $"Prime channels failed on ch{primeFail}.");
                    return;
                }

                session.ApplyState.MarkArmed(primeChannels, [0, 0, 0, 0], flashSync.TimerArmSource);
            }

            if (session?.ApplyState.IsRedundant(channels, source, appliedBrightness, writeBrightness) == true)
            {
                _log.LogDebug("SetLightSerial {ComPort} {Source} {ElapsedMs}ms (unchanged, skipped)", comPort, source, sw.ElapsedMilliseconds);
                result = (true, $"Channels [{string.Join(", ", channels)}] -> {source} (unchanged, skipped){lockNote}.");
                return;
            }

            bool needPrepare = writeBrightness
                || (source.Equals("On", StringComparison.OrdinalIgnoreCase)
                    && (session?.ApplyState.WasOff == true
                        || !(session?.ApplyState.IsHardwareArmed == true && session.ApplyState.CanSkipBrightness(channels, appliedBrightness))));

            if ((flashSync.UseDeferredTimer || flashSync.UseHoldTimerRise)
                && source.Equals("On", StringComparison.OrdinalIgnoreCase)
                && session?.ApplyState.IsHardwareArmed == true
                && !needPrepare)
            {
                if (!MvLeFlashSync.Apply(device, deviceLock, flashSync, channels, appliedBrightness, source,
                        writeBrightness, session.ApplyState.IsHardwareArmed, _serialDefaults.SustainOnAfterTrigger, out int failedChannel, out string armedSyncMode))
                {
                    _serialSessions.InvalidateOnDeviceError();
                    result = (false,
                        $"Simultaneous On failed (need MVS Timer trigger or broadcast). Channel {failedChannel}. Try FlashSyncMode or close MVS Client.");
                    return;
                }

                if (keepOpen && session != null)
                    session.ApplyState.MarkArmed(channels, appliedBrightness, flashSync.TimerArmSource);

                _log.LogDebug("SetLightSerial {ComPort} On sync={SyncMode} (armed, no per-channel On) {ElapsedMs}ms",
                    comPort, armedSyncMode, sw.ElapsedMilliseconds);

                result = (true, $"Channels [{string.Join(", ", channels)}] -> On ({armedSyncMode}), brightness [{string.Join(", ", appliedBrightness)}]{lockNote}.");
                return;
            }

            if (!MvLeFlashSync.Apply(device, deviceLock, flashSync, channels, appliedBrightness, source,
                    writeBrightness, session?.ApplyState.IsHardwareArmed == true, _serialDefaults.SustainOnAfterTrigger, out int failedCh, out string syncMode))
            {
                _serialSessions.InvalidateOnDeviceError();
                if (source.Equals("On", StringComparison.OrdinalIgnoreCase))
                {
                    result = (false,
                        flashSync.UseDeferredTimer
                            ? "Simultaneous On unavailable: no Timer software trigger and no broadcast selector. Check MV-LE in MVS (Timer1 + trigger) or FlashSyncMode."
                            : "Hold On failed: no broadcast selector on device. Try FlashSyncMode Auto/Deferred or configure All channel in MVS.");
                    return;
                }

                result = (false, $"Failed channel {failedCh}, source {source}.");
                return;
            }

            if (keepOpen && session != null)
            {
                if (source.Equals("Off", StringComparison.OrdinalIgnoreCase))
                    session.ApplyState.RecordOffKeepingArm(channels);
                else if (source.Equals("On", StringComparison.OrdinalIgnoreCase)
                    && (flashSync.UseDeferredTimer || flashSync.UseHoldTimerRise))
                {
                    session.ApplyState.MarkArmed(channels, appliedBrightness, flashSync.TimerArmSource);
                }
                else
                {
                    _serialSessions.RecordApplyState(comPort, channels, source, appliedBrightness);
                }
            }

            _log.LogDebug("SetLightSerial {ComPort} {Source} sync={SyncMode} writeBrightness={WriteBrightness} brightness=[{Brightness}] {ElapsedMs}ms",
                comPort, source, syncMode, writeBrightness, string.Join(", ", appliedBrightness), sw.ElapsedMilliseconds);

            result = (true, $"Channels [{string.Join(", ", channels)}] -> {source} ({syncMode}), brightness [{string.Join(", ", appliedBrightness)}]{lockNote}.");
        });
        return result;
    }

    private static bool TryBuildChannelPlan(
        LightCommandRequest request,
        MvLeDeviceCapabilities caps,
        string source,
        MvLeApplyState? applyState,
        out int[] channels,
        out int[] appliedBrightness,
        out bool writeBrightness,
        out string? error)
    {
        channels = request.Channels is { Length: > 0 } ? request.Channels : [1, 2, 3, 4];
        appliedBrightness = new int[channels.Length];
        writeBrightness = false;
        error = null;

        if (!caps.HasLightController)
        {
            error = "Устройство не поддерживает LightControllerSelector (не MV-LE по этому API).";
            return false;
        }

        if (request.Brightness is { Length: > 0 }
            && request.Brightness.Length != 1
            && request.Brightness.Length != channels.Length)
        {
            error = $"brightness length ({request.Brightness.Length}) must be 1 or match channels ({channels.Length}).";
            return false;
        }

        bool wantBrightness = caps.HasBrightness && !source.Equals("Off", StringComparison.OrdinalIgnoreCase);
        for (int i = 0; i < channels.Length; i++)
        {
            int ch = channels[i];
            if (ch is < 1 or > 4)
            {
                error = $"Invalid channel {ch}. Use 1–4.";
                return false;
            }

            appliedBrightness[i] = ResolveBrightness(request, i, channels.Length, source, caps.HasBrightness);
        }

        writeBrightness = wantBrightness
            && (applyState == null || !applyState.CanSkipBrightness(channels, appliedBrightness));
        return true;
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

    private static (bool ok, string? error) ApplyLightToDevice(
        IDevice device,
        LightCommandRequest request,
        MvLeDeviceCapabilities caps,
        object syncRoot,
        bool alreadyOpen,
        bool leaveOpen)
    {
        if (!alreadyOpen)
        {
            int ret = device.Open();
            if (ret != MvError.MV_OK)
            {
                device.Dispose();
                return (false, $"Open: 0x{ret:x8}");
            }
        }

        if (device is IGigEDevice gige)
        {
            int ret = gige.GetOptimalPacketSize(out int packetSize);
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

            if (!caps.HasLightController)
                return (false, "Устройство не поддерживает LightControllerSelector (не MV-LE по этому API).");

            bool writeBrightness = caps.HasBrightness && !source.Equals("Off", StringComparison.OrdinalIgnoreCase);
            var appliedBrightness = new int[channels.Length];

            for (int i = 0; i < channels.Length; i++)
            {
                int ch = channels[i];
                if (ch is < 1 or > 4)
                    return (false, $"Invalid channel {ch}. Use 1–4.");

                appliedBrightness[i] = ResolveBrightness(request, i, channels.Length, source, caps.HasBrightness);
            }

            if (!ApplyAllChannels(device, syncRoot, channels, appliedBrightness, source, writeBrightness, out int failedChannel))
                return (false, $"Failed channel {failedChannel}, source {source}.");

            string brightPart = writeBrightness
                ? $", brightness [{string.Join(", ", appliedBrightness)}]"
                : "";

            return (true, $"Channels [{string.Join(", ", channels)}] -> {source}{brightPart}.");
        }
        finally
        {
            if (!leaveOpen)
            {
                device.StreamGrabber.StopGrabbing();
                device.Close();
                device.Dispose();
            }
        }
    }

    /// <summary>Один lock, последовательная запись — предсказуемое время без Parallel.For.</summary>
    private static bool ApplyAllChannels(
        IDevice device,
        object syncRoot,
        int[] channels,
        int[] brightness,
        string source,
        bool writeBrightness,
        out int failedChannel)
    {
        failedChannel = 0;
        bool hasSourceNumeric = TrySourceNumeric(source, out uint sourceNumeric);

        lock (syncRoot)
        {
            for (int i = 0; i < channels.Length; i++)
            {
                int ch = channels[i];
                if (!SelectChannel(device, ch))
                {
                    failedChannel = ch;
                    return false;
                }

                if (writeBrightness)
                {
                    if (device.Parameters.SetIntValue(BrightnessNode, brightness[i]) != MvError.MV_OK)
                    {
                        failedChannel = ch;
                        return false;
                    }
                }

                int ret = MvError.MV_E_UNKNOW;
                if (hasSourceNumeric)
                    ret = device.Parameters.SetEnumValue("LightControllerSource", sourceNumeric);

                if (ret != MvError.MV_OK)
                    ret = device.Parameters.SetEnumValueByString("LightControllerSource", source);

                if (ret != MvError.MV_OK)
                {
                    failedChannel = ch;
                    return false;
                }
            }
        }

        return true;
    }

    private static int ResolveBrightness(LightCommandRequest request, int index, int channelCount, string source, bool hasNode)
    {
        if (!hasNode)
            return 0;

        if (request.Brightness is { Length: 1 })
            return ClampBrightness(request.Brightness[0]);

        if (request.Brightness is { Length: > 1 })
            return ClampBrightness(request.Brightness[index]);

        return source == "On" ? BrightnessDefaultOn : 0;
    }

    private static int[] ExpandBrightness(int[] values, int length)
    {
        if (values.Length >= length)
            return values;

        var expanded = new int[length];
        int fill = values.Length > 0 ? values[0] : BrightnessDefaultOn;
        for (int i = 0; i < length; i++)
            expanded[i] = i < values.Length ? values[i] : fill;

        return expanded;
    }

    private static int ClampBrightness(int value) =>
        Math.Clamp(value, BrightnessMin, BrightnessMax);

    private static bool SelectChannel(IDevice device, int channel)
    {
        int ret = device.Parameters.SetEnumValue("LightControllerSelector", (uint)channel);
        if (ret == MvError.MV_OK)
            return true;

        string selector = channel.ToString(CultureInfo.InvariantCulture);
        return device.Parameters.SetEnumValueByString("LightControllerSelector", selector) == MvError.MV_OK;
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

}

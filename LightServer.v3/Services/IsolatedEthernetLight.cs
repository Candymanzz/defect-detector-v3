using LightServer;
using Microsoft.Extensions.Logging;
using Microsoft.Extensions.Options;
using MvCameraControl;

namespace LightServer.Services;

/// <summary>Один Ethernet MV-LE: долгоживущая GigE-сессия для bank On/Off.</summary>
public sealed class IsolatedEthernetLight : IDisposable
{
    private readonly SerialLightOptions _options;
    private readonly ILogger _log;
    private readonly object _sync = new();
    private IDevice? _device;
    private MvLeFlashSyncPlan? _flashPlan;
    private int[]? _lastBrightness;
    private bool _sourceOn;
    private bool _disposed;

    public string DeviceId { get; }
    public string IpAddress { get; }
    public int[] Channels { get; }
    public bool IsOpen => _device != null;

    public IsolatedEthernetLight(
        string deviceId,
        string ipAddress,
        int[] channels,
        IOptions<SerialLightOptions> options,
        ILogger log)
    {
        DeviceId = deviceId;
        IpAddress = NormalizeIp(ipAddress) ?? ipAddress.Trim();
        Channels = channels.Length > 0 ? channels : [1, 2, 3, 4];
        _options = options.Value;
        _log = log;
    }

    public (bool ok, string? error) OpenFromEnumeration(List<IDeviceInfo> list, int index)
    {
        if (_disposed)
            return (false, $"{DeviceId}: disposed");

        if (_device != null)
            return (true, null);

        if (index < 0 || index >= list.Count)
            return (false, $"{DeviceId}@{IpAddress}: bad enum index {index}");

        IDevice device = DeviceFactory.CreateDevice(list[index]);
        int ret = device.Open();
        if (ret != MvError.MV_OK)
        {
            device.Dispose();
            return (false, $"{DeviceId}@{IpAddress}: Open 0x{ret:x8}");
        }

        if (device is IGigEDevice gige)
        {
            if (gige.GetOptimalPacketSize(out int packetSize) == MvError.MV_OK && packetSize > 0)
                device.Parameters.SetIntValue("GevSCPSPacketSize", packetSize);
        }

        if (device.Parameters.GetEnumValue("LightControllerSelector", out IEnumValue _) != MvError.MV_OK)
        {
            // Некоторые MV-LE отвечают только через SetEnumValue — пробуем дальше.
            _log.LogDebug("{DeviceId}@{Ip}: LightControllerSelector GetEnumValue miss — продолжаем", DeviceId, IpAddress);
        }

        _device = device;
        _flashPlan = MvLeFlashSync.CreateBankDirectPlan(device);
        _flashPlan.UseSdkLock = !_options.DisableSdkLock;

        if (_options.PreconfigureBrightnessOnOpen)
        {
            int[] prime = new int[Channels.Length];
            Array.Fill(prime, 255);
            MvLeFlashSync.WriteChannelsBrightnessOnly(_device, _sync, _flashPlan.UseSdkLock, Channels, prime, out _);
            _lastBrightness = prime;
        }

        _log.LogInformation(
            "Ethernet bank: открыт {DeviceId}@{Ip} channels=[{Ch}] mode=bank-direct",
            DeviceId,
            IpAddress,
            string.Join(",", Channels));
        return (true, null);
    }

    public (bool ok, string? message) ApplyDirectOn(int[] brightness)
    {
        if (_device == null || _flashPlan == null)
            return (false, $"{DeviceId}@{IpAddress}: не открыт");

        int[] powers = brightness.Length == Channels.Length
            ? brightness
            : PadBrightness(brightness, Channels.Length);

        // Всегда Off→brightness→On: после яркости 0 иначе LED не поднимается.
        const bool forceOffFirst = true;
        _log.LogInformation(
            "Ethernet bank ApplyDirectOn {Ip} forceOffFirst={Force} brightness=[{B}]",
            IpAddress,
            forceOffFirst,
            string.Join(",", powers));
        MvLeFlashSyncPlan plan = ClonePlan(_flashPlan);
        if (!MvLeFlashSync.ApplyBankDirectOn(
                _device,
                _sync,
                plan,
                Channels,
                powers,
                writeBrightness: true,
                forceOffFirst,
                out int failCh,
                out string mode))
        {
            return (false, $"{DeviceId}@{IpAddress}: {mode} ch{failCh}");
        }

        int[]? readBack = MvLeFlashSync.ReadChannelsBrightness(_device, _sync, plan.UseSdkLock, Channels);
        _log.LogInformation(
            "Ethernet bank ApplyDirectOn done {Ip} mode={Mode} readBack=[{R}]",
            IpAddress,
            mode,
            readBack == null ? "?" : string.Join(",", readBack));

        _lastBrightness = (int[])powers.Clone();
        _sourceOn = true;
        return (true, mode);
    }

    public (bool ok, string? message) ApplyOff()
    {
        if (_device == null || _flashPlan == null)
            return (true, "already-off");

        MvLeFlashSyncPlan plan = ClonePlan(_flashPlan);
        if (!MvLeFlashSync.FireGroupedOff(_device, _sync, plan, Channels, out int failCh, out string mode))
            return (false, $"{DeviceId}@{IpAddress}: off ch{failCh} ({mode})");

        _sourceOn = false;
        return (true, mode);
    }

    /// <summary>
    /// Обновить яркость в живой сессии.
    /// Если банк уже On (always-on режим), яркость меняется сразу без Off/On цикла.
    /// Иначе значение подготавливается для следующего bank On.
    /// </summary>
    public (bool ok, string? message) WriteBrightness(int[] brightness)
    {
        if (_device == null || _flashPlan == null)
            return (false, $"{DeviceId}@{IpAddress}: не открыт");

        int[] powers = brightness.Length == Channels.Length
            ? brightness
            : PadBrightness(brightness, Channels.Length);

        if (_sourceOn)
        {
            if (!MvLeFlashSync.WriteChannelsBrightnessOnly(
                    _device,
                    _sync,
                    _flashPlan.UseSdkLock,
                    Channels,
                    powers,
                    out int liveFailCh))
            {
                return (false, $"{DeviceId}@{IpAddress}: brightness-live ch{liveFailCh}");
            }

            _lastBrightness = (int[])powers.Clone();
            _log.LogInformation(
                "Ethernet bank brightness live {Ip} powers=[{P}]",
                IpAddress,
                string.Join(",", powers));
            return (true, "brightness-live");
        }

        if (!MvLeFlashSync.WriteChannelsBrightnessOnly(
                _device,
                _sync,
                _flashPlan.UseSdkLock,
                Channels,
                powers,
                out int failCh))
        {
            return (false, $"{DeviceId}@{IpAddress}: brightness ch{failCh}");
        }

        // Следующий ApplyDirectOn обязан Off→write→On (forceOffFirst).
        _lastBrightness = null;
        _log.LogInformation(
            "Ethernet bank brightness primed {Ip} powers=[{P}]",
            IpAddress,
            string.Join(",", powers));
        return (true, "brightness-primed");
    }

    private bool BrightnessMatches(int[] brightness)
    {
        if (_lastBrightness == null || _lastBrightness.Length != brightness.Length)
            return false;
        for (int i = 0; i < brightness.Length; i++)
        {
            if (_lastBrightness[i] != brightness[i])
                return false;
        }

        return true;
    }

    private static int[] PadBrightness(int[] brightness, int len)
    {
        var outArr = new int[len];
        Array.Fill(outArr, 255);
        for (int i = 0; i < Math.Min(brightness.Length, len); i++)
            outArr[i] = brightness[i];
        return outArr;
    }

    private static MvLeFlashSyncPlan ClonePlan(MvLeFlashSyncPlan source)
    {
        var plan = new MvLeFlashSyncPlan
        {
            UseBroadcast = source.UseBroadcast,
            BroadcastSelectorValue = source.BroadcastSelectorValue,
            UseDeferredTimer = source.UseDeferredTimer,
            UseHoldTimerRise = source.UseHoldTimerRise,
            TimerArmSource = source.TimerArmSource,
            TimerTriggerCommand = source.TimerTriggerCommand,
            TimerDurationNode = source.TimerDurationNode,
            TimerDurationHoldValue = source.TimerDurationHoldValue,
            UseSdkLock = source.UseSdkLock,
            UseDirectImmediate = source.UseDirectImmediate,
            CachedBroadcastSelectorName = source.CachedBroadcastSelectorName,
            CachedBroadcastSelectorValue = source.CachedBroadcastSelectorValue
        };
        plan.BroadcastSelectorCandidates.AddRange(source.BroadcastSelectorCandidates);
        plan.TriggerCommandCandidates.AddRange(source.TriggerCommandCandidates);
        return plan;
    }

    private static string? NormalizeIp(string? raw)
    {
        if (string.IsNullOrWhiteSpace(raw))
            return null;
        return raw.Trim();
    }

    public void Dispose()
    {
        if (_disposed)
            return;
        _disposed = true;
        if (_device == null)
            return;
        try
        {
            // Иначе MV-LE остаётся On / GigE «занят» в MVS после Close без Off.
            ApplyOff();
        }
        catch
        {
            // ignore
        }

        try
        {
            _device.Close();
        }
        catch
        {
            // ignore
        }

        _device.Dispose();
        _device = null;
    }
}

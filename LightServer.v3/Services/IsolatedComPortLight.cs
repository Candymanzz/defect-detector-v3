using LightServer;
using Microsoft.Extensions.Logging;
using Microsoft.Extensions.Options;
using MvCameraControl;

namespace LightServer.Services;

/// <summary>Один COM = свой device, lock и план. Без общего кэша EnumDevices на все порты.</summary>
public sealed class IsolatedComPortLight : IDisposable
{
    private readonly SerialLightOptions _options;
    private readonly string? _timerArmOverride;
    private readonly ILogger<IsolatedComPortLight> _log;

    private readonly object _portSync = new();
    private IDevice? _device;
    private MvLeFlashSyncPlan? _flashPlan;
    private List<IDeviceInfo>? _bankDeviceList;
    private int _bankDeviceIndex = -1;
    private bool _disposed;
    private int[]? _lastAppliedBrightness;

    private const int OpenRetryCount = 3;
    private const int OpenRetryDelayMs = 150;

    public string ComPort { get; }
    public int[] Channels { get; }

    public bool IsOpen => _device != null;

    public IsolatedComPortLight(
        string comPort,
        int[] channels,
        string? timerArmSource,
        IOptions<SerialLightOptions> options,
        ILogger<IsolatedComPortLight> log)
    {
        ComPort = MvsComPortEnumerator.NormalizeComPort(comPort);
        Channels = channels;
        _timerArmOverride = string.IsNullOrWhiteSpace(timerArmSource) ? null : timerArmSource.Trim();
        _options = options.Value;
        _log = log;
    }

    /// <summary>Открыть по индексу из общего EnumDevices(все COM) — рекомендуемый путь банка.</summary>
    public (bool ok, string? error) OpenFromBankEnumeration(List<IDeviceInfo> deviceList, int deviceIndex)
    {
        _bankDeviceList = deviceList;
        _bankDeviceIndex = deviceIndex;
        return EnsureOpenCore();
    }

    public (bool ok, string? error) EnsureOpen() => EnsureOpenCore();

    private (bool ok, string? error) EnsureOpenCore()
    {
        if (_disposed)
            return (false, $"{ComPort}: сессия закрыта.");

        if (_device != null)
            return (true, null);

        List<IDeviceInfo> list;
        int idx;

        if (_bankDeviceList != null && _bankDeviceIndex >= 0)
        {
            list = _bankDeviceList;
            idx = _bankDeviceIndex;
        }
        else
        {
            var (enumOk, enumErr, foundIdx, enumList) = MvsComPortEnumerator.EnumerateSinglePort(ComPort);
            if (!enumOk || enumList == null)
                return (false, enumErr ?? "Enum failed.");
            list = enumList;
            idx = foundIdx;
        }

        if (idx < 0 || idx >= list.Count)
            return (false, $"{ComPort}: неверный индекс {idx} (enum count={list.Count}).");

        string devDesc = MvsComPortEnumerator.DescribeDevice(list[idx]);
        IDevice device = DeviceFactory.CreateDevice(list[idx]);

        int ret = MvError.MV_E_UNKNOW;
        for (int attempt = 1; attempt <= OpenRetryCount; attempt++)
        {
            ret = device.Open();
            if (ret == MvError.MV_OK)
                break;

            if (attempt < OpenRetryCount)
                Thread.Sleep(OpenRetryDelayMs * attempt);
        }

        if (ret != MvError.MV_OK)
        {
            device.Dispose();
            string hint = ret == unchecked((int)0x800000FF)
                ? " (MV_E_UNKNOW: закройте MVS Client, проверьте кабель/порт, не занят ли COM3)"
                : "";
            return (false, $"{ComPort}: Open 0x{ret:x8}{hint} [{devDesc}]");
        }

        if (device.Parameters.GetEnumValue("LightControllerSelector", out IEnumValue _) != MvError.MV_OK)
        {
            device.Dispose();
            return (false, $"{ComPort}: нет LightControllerSelector. [{devDesc}]");
        }

        _device = device;
        // BankFlashMode On/Direct → broadcast On; Trigger → Timer arm + software trigger (две фазы в банке).
        _flashPlan = UseBankDirectOn()
            ? MvLeFlashSync.CreateBankDirectPlan(device)
            : MvLeFlashSync.ResolveBankFlashPlan(device, _options.FlashSyncMode);

        if (!UseBankDirectOn())
        {
            MvLeFlashSync.ProbeTimerArmSource(device, _flashPlan, _timerArmOverride);
            MvLeFlashSync.ProbeTimerDurationNode(device, _flashPlan);
        }

        if (UseBankDirectOn() && _options.PreconfigureBrightnessOnOpen)
        {
            int[] prime = new int[Channels.Length];
            Array.Fill(prime, 255);
            var primePlan = ClonePlan(_flashPlan);
            primePlan.UseSdkLock = !_options.DisableSdkLock;
            MvLeFlashSync.WriteChannelsBrightnessOnly(_device!, _portSync, primePlan.UseSdkLock, Channels, prime, out _);
            _lastAppliedBrightness = (int[])prime.Clone();
        }

        string modeTag = UseBankDirectOn() ? "bank-On" : $"bank-Trigger:{_flashPlan.TimerArmSource}";
        _log.LogInformation("{ComPort}: открыт idx={Idx}/{Count} mode={Mode} {Device}", ComPort, idx, list.Count, modeTag, devDesc);
        return (true, null);
    }

    private bool UseBankDirectOn() => MvLeFlashSync.IsBankDirectOnMode(_options.BankFlashMode);

    public (bool ok, string? message) PrepareFlash(int[] brightness)
    {
        var (ready, readyErr) = EnsureOpenCore();
        if (!ready)
            return (false, readyErr);

        MvLeFlashSyncPlan plan = ClonePlan(_flashPlan!);
        plan.UseSdkLock = !_options.DisableSdkLock;

        if (UseBankDirectOn())
        {
            if (!MvLeFlashSync.PrepareBankDirect(_device!, _portSync, plan, Channels, brightness, out int directPrepFail))
            {
                _log.LogWarning("{ComPort}: direct prep failed ch{Ch}", ComPort, directPrepFail);
                return (false, $"{ComPort}: direct prep ch{directPrepFail}");
            }

            return (true, "prep:direct-off");
        }

        ApplyBankFlashTiming(plan);

        if (!MvLeFlashSync.PrepareBankFlash(_device!, _portSync, plan, Channels, brightness, out int prepFail))
        {
            _log.LogWarning("{ComPort}: trigger prep failed ch{Ch} arm={Arm}", ComPort, prepFail, plan.TimerArmSource);
            return (false, $"{ComPort}: prepare ch{prepFail} arm={plan.TimerArmSource}");
        }

        return (true, $"prep:{plan.TimerArmSource}");
    }

    public (bool ok, string? message) FireFlash()
    {
        if (_device == null || _flashPlan == null)
            return (false, $"{ComPort}: не открыт");

        MvLeFlashSyncPlan plan = ClonePlan(_flashPlan);
        plan.UseSdkLock = !_options.DisableSdkLock;

        if (UseBankDirectOn())
        {
            if (!MvLeFlashSync.FireBankDirectOn(_device, _portSync, plan, Channels, out int failCh, out string mode))
            {
                _log.LogWarning("{ComPort}: direct on failed ch{Ch} {Mode}", ComPort, failCh, mode);
                return (false, $"{ComPort}: {mode} ch{failCh}");
            }

            _log.LogInformation("{ComPort}: On {Mode}", ComPort, mode);
            return (true, mode);
        }

        ApplyBankFlashTiming(plan);

        if (!MvLeFlashSync.FireBankTriggerOnly(_device, _portSync, plan, out string triggerCmd))
        {
            _log.LogWarning("{ComPort}: trigger failed arm={Arm}", ComPort, plan.TimerArmSource);
            return (false, $"{ComPort}: trigger miss arm={plan.TimerArmSource}");
        }

        string triggerMode = $"bank-trigger:{triggerCmd}";
        _log.LogInformation("{ComPort}: On {Mode}", ComPort, triggerMode);
        return (true, triggerMode);
    }

    /// <summary>Быстрый On (один проход SDK) для BankFlashMode On/Direct/Broadcast.</summary>
    public (bool ok, string? message) ApplyDirectOn(int[] brightness)
    {
        var (ready, readyErr) = EnsureOpenCore();
        if (!ready)
            return (false, readyErr);

        if (_device == null || _flashPlan == null)
            return (false, $"{ComPort}: не открыт");

        MvLeFlashSyncPlan plan = ClonePlan(_flashPlan);
        plan.UseSdkLock = !_options.DisableSdkLock;
        bool writeBrightness = !BrightnessMatchesLast(brightness);

        if (!MvLeFlashSync.ApplyBankDirectOn(
                _device,
                _portSync,
                plan,
                Channels,
                brightness,
                writeBrightness,
                forceOffFirst: false,
                out int failCh,
                out string mode))
        {
            _log.LogWarning("{ComPort}: direct on failed ch{Ch} {Mode}", ComPort, failCh, mode);
            return (false, $"{ComPort}: {mode} ch{failCh}");
        }

        if (writeBrightness)
            _lastAppliedBrightness = (int[])brightness.Clone();

        _log.LogInformation("{ComPort}: On {Mode}", ComPort, mode);
        return (true, mode);
    }

    public (bool ok, string? message) ApplyOn(int[] brightness)
    {
        if (UseBankDirectOn())
            return ApplyDirectOn(brightness);

        var (prepOk, prepMsg) = PrepareFlash(brightness);
        if (!prepOk)
            return (false, prepMsg);
        return FireFlash();
    }

    /// <summary>
    /// Обновить яркость отдельных каналов на уже открытой COM-сессии банка.
    /// Не открывает вторую сессию (в отличие от legacy ApplyComPort).
    /// </summary>
    public (bool ok, string? message) ApplyChannelBrightness(int[] updateChannels, int[] powers, bool turnOn = true)
    {
        if (updateChannels.Length == 0 || powers.Length != updateChannels.Length)
            return (false, $"{ComPort}: channels/brightness length mismatch.");

        var (ready, readyErr) = EnsureOpenCore();
        if (!ready)
            return (false, readyErr);

        int[] merged = _lastAppliedBrightness is { Length: > 0 } && _lastAppliedBrightness.Length == Channels.Length
            ? (int[])_lastAppliedBrightness.Clone()
            : Enumerable.Repeat(255, Channels.Length).ToArray();

        for (int i = 0; i < updateChannels.Length; i++)
        {
            int idx = Array.IndexOf(Channels, updateChannels[i]);
            if (idx < 0)
                return (false, $"{ComPort}: канал {updateChannels[i]} не в device channels [{string.Join(",", Channels)}].");
            merged[idx] = powers[i];
        }

        return turnOn ? ApplyOn(merged) : WriteBrightness(merged);
    }

    /// <summary>Только яркость в регистрах (без On) — как Ethernet bank WriteBrightness.</summary>
    public (bool ok, string? message) WriteBrightness(int[] brightness)
    {
        var (ready, readyErr) = EnsureOpenCore();
        if (!ready)
            return (false, readyErr);

        if (_device == null || _flashPlan == null)
            return (false, $"{ComPort}: не открыт");

        int[] powers = brightness.Length == Channels.Length
            ? brightness
            : PadBrightness(brightness, Channels.Length);

        MvLeFlashSyncPlan plan = ClonePlan(_flashPlan);
        plan.UseSdkLock = !_options.DisableSdkLock;
        if (!MvLeFlashSync.WriteChannelsBrightnessOnly(
                _device,
                _portSync,
                plan.UseSdkLock,
                Channels,
                powers,
                out int failCh))
        {
            return (false, $"{ComPort}: brightness ch{failCh}");
        }

        _lastAppliedBrightness = (int[])powers.Clone();
        _log.LogInformation("{ComPort}: brightness powers=[{P}]", ComPort, string.Join(",", powers));
        return (true, "brightness");
    }

    private static int[] PadBrightness(int[] brightness, int len)
    {
        var outArr = new int[len];
        Array.Fill(outArr, 255);
        for (int i = 0; i < Math.Min(brightness.Length, len); i++)
            outArr[i] = brightness[i];
        return outArr;
    }

    private bool BrightnessMatchesLast(int[] brightness)
    {
        if (_lastAppliedBrightness == null || _lastAppliedBrightness.Length != brightness.Length)
            return false;

        for (int i = 0; i < brightness.Length; i++)
        {
            if (_lastAppliedBrightness[i] != brightness[i])
                return false;
        }

        return true;
    }

    public (bool ok, string? message) ApplyOff()
    {
        if (_device == null)
            return (true, "already-off");

        MvLeFlashSyncPlan plan = ClonePlan(_flashPlan!);
        plan.UseSdkLock = !_options.DisableSdkLock;
        if (!MvLeFlashSync.FireGroupedOff(_device, _portSync, plan, Channels, out int failCh, out string mode))
            return (false, $"{ComPort}: off ch{failCh} ({mode})");

        _log.LogInformation("{ComPort}: Off {Mode}", ComPort, mode);
        return (true, mode);
    }

    private void ApplyBankFlashTiming(MvLeFlashSyncPlan plan)
    {
        if (_options.BankSustainOnAfterTrigger || _options.BankFlashDurationMs <= 0)
            return;

        if (string.IsNullOrEmpty(plan.TimerDurationNode))
            return;

        plan.TimerDurationHoldValue = Math.Clamp(_options.BankFlashDurationMs, 1, int.MaxValue);
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
            UseSdkLock = false,
            UseDirectImmediate = source.UseDirectImmediate,
            CachedBroadcastSelectorName = source.CachedBroadcastSelectorName,
            CachedBroadcastSelectorValue = source.CachedBroadcastSelectorValue
        };
        plan.BroadcastSelectorCandidates.AddRange(source.BroadcastSelectorCandidates);
        plan.TriggerCommandCandidates.AddRange(source.TriggerCommandCandidates);
        return plan;
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
            _device.Close();
        }
        catch
        {
            // ignore
        }

        _device.Dispose();
        _device = null;
        _flashPlan = null;
    }
}

using System.Globalization;
using MvCameraControl;

namespace LightServer.Services;

/// <summary>
/// MV-LE Hold: trigger (вспышка разом) + broadcast или быстрый On по каналам — свет всегда остаётся включённым.
/// </summary>
public sealed class MvLeFlashSyncPlan
{
    public bool UseBroadcast { get; set; }
    public uint? BroadcastSelectorValue { get; set; }
    public List<uint> BroadcastSelectorCandidates { get; } = [];
    public bool UseDeferredTimer { get; set; } = true;
    /// <summary>Hold: зажигание одним timer trigger (не broadcast On).</summary>
    public bool UseHoldTimerRise { get; set; }
    public string TimerArmSource { get; set; } = "Timer1";
    public string? TimerTriggerCommand { get; set; }
    public string? TimerDurationNode { get; set; }
    public int TimerDurationHoldValue { get; set; } = 60_000;
    public List<string> TriggerCommandCandidates { get; } = [];
    public bool UseSdkLock { get; set; } = true;
}

public static class MvLeFlashSync
{
    private static void WithSdkLock(object syncRoot, bool useLock, Action action)
    {
        if (!useLock)
        {
            action();
            return;
        }

        lock (syncRoot)
            action();
    }

    private const string BrightnessNode = "LightBrightness";
    private const string SelectorNode = "LightControllerSelector";
    private const string SourceNode = "LightControllerSource";

    private static readonly string[] BroadcastSelectorNames =
    [
        "All",
        "ChannelAll",
        "LightControllerAll",
        "SelectorAll",
        "LightAll"
    ];

    private static readonly string[] DefaultTriggerCommandCandidates =
    [
        "TimerTriggerSoftware",
        "TriggerSoftwareExecute",
        "TriggerSoftware",
        "DeviceTriggerSoftware",
        "LightSoftwareTrigger",
        "SoftwareTriggerCommand",
        "ExecuteSoftwareTrigger",
        "LightControlTrigger",
        "SoftTrigger",
        "Timer1Trigger",
        "AcquisitionStart",
        "LineTriggerSoftware"
    ];

    private static readonly string[] TimerDurationNodeCandidates =
    [
        "Timer1Duration",
        "TimerDuration",
        "LightDuration",
        "PulseWidth",
        "StrobeDuration",
        "Timer1PulseWidth",
        "Timer1OnDuration",
        "OutputPulseWidth",
        "LightPulseDuration"
    ];

    public static MvLeFlashSyncPlan Probe(IDevice device, string flashSyncMode)
    {
        var plan = new MvLeFlashSyncPlan();
        string mode = flashSyncMode.Trim();

        if (mode.Equals("Direct", StringComparison.OrdinalIgnoreCase)
            || mode.Equals("Sequential", StringComparison.OrdinalIgnoreCase))
        {
            plan.UseDeferredTimer = false;
            return plan;
        }

        bool holdLike = mode.Equals("Hold", StringComparison.OrdinalIgnoreCase)
            || mode.Equals("Broadcast", StringComparison.OrdinalIgnoreCase);

        plan.UseDeferredTimer = !holdLike
            && !mode.Equals("Direct", StringComparison.OrdinalIgnoreCase)
            && !mode.Equals("Sequential", StringComparison.OrdinalIgnoreCase);

        if (holdLike
            || mode.Equals("Auto", StringComparison.OrdinalIgnoreCase)
            || mode.Equals("Deferred", StringComparison.OrdinalIgnoreCase))
        {
            CollectBroadcastSelectors(device, plan);
        }

        foreach (string cmd in DefaultTriggerCommandCandidates)
        {
            if (device.Parameters.GetNodeInterfaceType(cmd, out XmlInterfaceType t) == MvError.MV_OK
                && t == XmlInterfaceType.ICommand)
            {
                plan.TriggerCommandCandidates.Add(cmd);
            }
        }

        if (holdLike)
        {
            plan.UseHoldTimerRise = plan.TriggerCommandCandidates.Count > 0;
            ProbeTimerDurationNode(device, plan);
        }

        return plan;
    }

    /// <summary>Timer1 при Open / перед On — свет не горит до одного software trigger.</summary>
    public static string ResolvePrimeSource(MvLeFlashSyncPlan plan) =>
        plan.UseDeferredTimer || plan.UseHoldTimerRise ? plan.TimerArmSource : "Off";

    public static bool Apply(
        IDevice device,
        object syncRoot,
        MvLeFlashSyncPlan plan,
        int[] channels,
        int[] brightness,
        string source,
        bool writeBrightness,
        bool hardwareTimerArmed,
        bool sustainOnAfterTrigger,
        out int failedChannel,
        out string modeTag)
    {
        failedChannel = 0;
        modeTag = plan.UseDeferredTimer ? "deferred" : "hold";

        if (!plan.UseDeferredTimer)
        {
            if (source.Equals("On", StringComparison.OrdinalIgnoreCase))
                return ApplyOnHold(device, syncRoot, plan, channels, brightness, writeBrightness, hardwareTimerArmed, sustainOnAfterTrigger, out failedChannel, out modeTag);

            if (source.Equals("Off", StringComparison.OrdinalIgnoreCase))
                return ApplyOffHold(device, syncRoot, plan, channels, out failedChannel, out modeTag);
        }

        if (source.Equals("On", StringComparison.OrdinalIgnoreCase))
            return ApplyOnDeferred(device, syncRoot, plan, channels, brightness, writeBrightness, sustainOnAfterTrigger, out failedChannel, out modeTag);

        if (source.Equals("Off", StringComparison.OrdinalIgnoreCase))
            return ApplyOffHold(device, syncRoot, plan, channels, out failedChannel, out modeTag);

        return ApplyImmediateSource(device, syncRoot, plan, channels, brightness, source, writeBrightness, out failedChannel, out modeTag);
    }

    /// <summary>Подготовка при Open: яркость (опционально) + Timer1/Off.</summary>
    public static bool PrepareHardware(
        IDevice device,
        object syncRoot,
        MvLeFlashSyncPlan plan,
        int[] channels,
        int[]? brightness,
        out int failedChannel)
    {
        return PrepareChannels(device, syncRoot, plan.UseSdkLock, channels, brightness, ResolvePrimeSource(plan), out failedChannel);
    }

    /// <summary>
    /// Hold: фаза 1 — все каналы Timer1+яркость (собрать), фаза 2 — один trigger, фаза 3 — broadcast On (без On по каналам).
    /// </summary>
    private static bool ApplyOnHold(
        IDevice device,
        object syncRoot,
        MvLeFlashSyncPlan plan,
        int[] channels,
        int[] brightness,
        bool writeBrightness,
        bool hardwareTimerArmed,
        bool sustainOnAfterTrigger,
        out int failedChannel,
        out string modeTag)
    {
        failedChannel = 0;
        modeTag = "hold-on";
        RefreshHoldPlan(device, plan);

        bool needAssemble = writeBrightness || !hardwareTimerArmed;
        if (needAssemble)
        {
            if (!PrepareChannels(device, syncRoot, plan.UseSdkLock, channels, brightness, plan.TimerArmSource, out failedChannel))
            {
                modeTag = "hold-assemble-fail";
                return false;
            }

            modeTag = "hold-assembled";
        }
        else
        {
            modeTag = "hold-pre-armed";
        }

        TryApplyTimerHoldDuration(device, syncRoot, plan, channels, out string durationVia);

        if (!TryFireHoldTrigger(device, syncRoot, plan, out string triggerCmd))
        {
            modeTag = $"{modeTag}+trigger-miss";
            if (TryApplyBroadcastSource(device, syncRoot, plan, "On", out failedChannel, out string broadcastVia))
            {
                modeTag = $"{modeTag}+broadcast-fallback:{broadcastVia}";
                return true;
            }

            if (!PrepareChannels(device, syncRoot, plan.UseSdkLock, channels, brightness: null, "On", out failedChannel))
            {
                modeTag = $"{modeTag}+seq-fail";
                return false;
            }

            modeTag = $"{modeTag}+seq-fallback";
            return true;
        }

        modeTag = $"{modeTag}+trigger:{triggerCmd}";

        if (sustainOnAfterTrigger
            && TryApplyBroadcastSource(device, syncRoot, plan, "On", out failedChannel, out string sustainVia))
        {
            modeTag = $"{modeTag}+sustain-broadcast:{sustainVia}";
            return true;
        }

        if (plan.TimerDurationNode != null)
        {
            modeTag = string.IsNullOrEmpty(durationVia)
                ? $"{modeTag}+timer-hold"
                : $"{modeTag}+timer-hold:{durationVia}";
            return true;
        }

        if (!sustainOnAfterTrigger)
            return true;

        if (PrepareChannels(device, syncRoot, plan.UseSdkLock, channels, brightness: null, "On", out failedChannel))
        {
            modeTag = $"{modeTag}+sustain-seq-fallback";
            return true;
        }

        modeTag = $"{modeTag}+sustain-fail";
        return false;
    }

    private static bool ApplyOffHold(
        IDevice device,
        object syncRoot,
        MvLeFlashSyncPlan plan,
        int[] channels,
        out int failedChannel,
        out string modeTag)
    {
        modeTag = "hold-off";

        if (TryApplyBroadcastSource(device, syncRoot, plan, "Off", out failedChannel, out string broadcastVia))
        {
            modeTag = $"hold-broadcast-off:{broadcastVia}";
            return true;
        }

        if (!PrepareChannels(device, syncRoot, plan.UseSdkLock, channels, brightness: null, "Off", out failedChannel))
        {
            modeTag = "hold-seq-off";
            return false;
        }

        modeTag = "hold-seq-off";
        return true;
    }

    private static bool ApplyOnDeferred(
        IDevice device,
        object syncRoot,
        MvLeFlashSyncPlan plan,
        int[] channels,
        int[] brightness,
        bool writeBrightness,
        bool sustainOnAfterTrigger,
        out int failedChannel,
        out string modeTag)
    {
        failedChannel = 0;
        modeTag = "deferred-on";

        if (writeBrightness || plan.UseDeferredTimer)
        {
            if (!PrepareChannels(device, syncRoot, plan.UseSdkLock, channels, brightness, plan.TimerArmSource, out failedChannel))
            {
                modeTag = "deferred-prepare-fail";
                return false;
            }
        }

        if (TryFireTimerTrigger(device, syncRoot, plan, timerOnly: true, out string triggerCmd))
        {
            modeTag = $"deferred-trigger:{triggerCmd}";
            if (sustainOnAfterTrigger && TrySustainOn(device, syncRoot, plan, channels, sequentialFallback: true, out failedChannel, out string sustainTag))
                modeTag = $"{modeTag}+{sustainTag}";

            return true;
        }

        if (TryApplyBroadcastSource(device, syncRoot, plan, "On", out failedChannel, out string broadcastVia))
        {
            modeTag = $"deferred-broadcast-on:{broadcastVia}";
            return true;
        }

        failedChannel = channels.Length > 0 ? channels[0] : 1;
        modeTag = "deferred-unavailable";
        return false;
    }

    /// <summary>После импульса Timer — один раз перевести все каналы в On (удержание).</summary>
    private static bool TrySustainOn(
        IDevice device,
        object syncRoot,
        MvLeFlashSyncPlan plan,
        int[] channels,
        bool sequentialFallback,
        out int failedChannel,
        out string modeTag)
    {
        failedChannel = 0;
        modeTag = "sustain";

        if (TryApplyBroadcastSource(device, syncRoot, plan, "On", out failedChannel, out string broadcastVia))
        {
            modeTag = $"sustain-broadcast:{broadcastVia}";
            return true;
        }

        if (!sequentialFallback)
            return false;

        if (!PrepareChannels(device, syncRoot, plan.UseSdkLock, channels, brightness: null, "On", out failedChannel))
            return false;

        modeTag = "sustain-seq";
        return true;
    }

    /// <summary>Кэш плана при Open мог быть пустым — перечитываем trigger/broadcast перед On.</summary>
    private static void RefreshHoldPlan(IDevice device, MvLeFlashSyncPlan plan)
    {
        if (plan.TriggerCommandCandidates.Count == 0)
        {
            foreach (string cmd in DefaultTriggerCommandCandidates)
            {
                if (device.Parameters.GetNodeInterfaceType(cmd, out XmlInterfaceType t) == MvError.MV_OK
                    && t == XmlInterfaceType.ICommand)
                {
                    plan.TriggerCommandCandidates.Add(cmd);
                }
            }
        }

        plan.UseHoldTimerRise = plan.TriggerCommandCandidates.Count > 0;
        ProbeTimerDurationNode(device, plan);

        if (!plan.UseBroadcast || plan.BroadcastSelectorCandidates.Count == 0)
            CollectBroadcastSelectors(device, plan);
    }

    private static bool TryFireHoldTrigger(IDevice device, object syncRoot, MvLeFlashSyncPlan plan, out string usedCommand) =>
        TryFireTimerTrigger(device, syncRoot, plan, timerOnly: true, out usedCommand)
        || TryFireTimerTrigger(device, syncRoot, plan, timerOnly: false, out usedCommand);

    private static void ProbeTimerDurationNode(IDevice device, MvLeFlashSyncPlan plan)
    {
        foreach (string node in TimerDurationNodeCandidates)
        {
            if (device.Parameters.GetIntValue(node, out IIntValue? value) != MvError.MV_OK || value == null)
                continue;

            plan.TimerDurationNode = node;
            long max = value.Max;
            plan.TimerDurationHoldValue = max > 0 && max <= int.MaxValue
                ? (int)max
                : 60_000;
            return;
        }
    }

    private static bool TryApplyTimerHoldDuration(
        IDevice device,
        object syncRoot,
        MvLeFlashSyncPlan plan,
        int[] channels,
        out string via)
    {
        via = "";
        if (string.IsNullOrEmpty(plan.TimerDurationNode))
            return false;

        via = plan.TimerDurationNode;
        bool ok = true;
        WithSdkLock(syncRoot, plan.UseSdkLock, () =>
        {
            for (int i = 0; i < channels.Length; i++)
            {
                int ch = channels[i];
                if (!SelectChannel(device, ch))
                {
                    ok = false;
                    return;
                }

                if (device.Parameters.SetIntValue(plan.TimerDurationNode, plan.TimerDurationHoldValue) != MvError.MV_OK)
                {
                    ok = false;
                    return;
                }
            }
        });

        return ok;
    }

    private static void CollectBroadcastSelectors(IDevice device, MvLeFlashSyncPlan plan)
    {
        plan.BroadcastSelectorCandidates.Clear();

        if (device.Parameters.GetEnumValue(SelectorNode, out IEnumValue? enumValue) == MvError.MV_OK
            && enumValue?.SupportEnumEntries != null)
        {
            foreach (IEnumEntry entry in enumValue.SupportEnumEntries)
            {
                if (IsBroadcastSymbol(entry.Symbolic ?? ""))
                    plan.BroadcastSelectorCandidates.Add(entry.Value);
            }
        }

        if (plan.BroadcastSelectorCandidates.Count == 0)
            plan.BroadcastSelectorCandidates.Add(0);

        plan.UseBroadcast = true;
        plan.BroadcastSelectorValue = plan.BroadcastSelectorCandidates[0];
    }

    private static bool TryApplyBroadcastSource(
        IDevice device,
        object syncRoot,
        MvLeFlashSyncPlan plan,
        string source,
        out int failedChannel,
        out string via)
    {
        failedChannel = 0;
        via = "";
        bool found = false;
        string viaHit = "";

        WithSdkLock(syncRoot, plan.UseSdkLock, () =>
        {
            foreach (string name in BroadcastSelectorNames)
            {
                if (device.Parameters.SetEnumValueByString(SelectorNode, name) != MvError.MV_OK)
                    continue;

                if (!SetSource(device, source))
                    continue;

                viaHit = name;
                plan.UseBroadcast = true;
                found = true;
                return;
            }

            foreach (uint selector in plan.BroadcastSelectorCandidates)
            {
                if (device.Parameters.SetEnumValue(SelectorNode, selector) != MvError.MV_OK)
                    continue;

                if (!SetSource(device, source))
                    continue;

                viaHit = $"sel:{selector}";
                plan.UseBroadcast = true;
                plan.BroadcastSelectorValue = selector;
                found = true;
                return;
            }

            if (TryFindBroadcastSelector(device, out uint sel)
                && device.Parameters.SetEnumValue(SelectorNode, sel) == MvError.MV_OK
                && SetSource(device, source))
            {
                viaHit = $"probe:{sel}";
                plan.UseBroadcast = true;
                plan.BroadcastSelectorValue = sel;
                if (!plan.BroadcastSelectorCandidates.Contains(sel))
                    plan.BroadcastSelectorCandidates.Add(sel);
                found = true;
            }
        });

        if (found)
            via = viaHit;

        return found;
    }

    private static bool IsBroadcastSymbol(string sym) =>
        sym.Equals("All", StringComparison.OrdinalIgnoreCase)
        || sym.Equals("ChannelAll", StringComparison.OrdinalIgnoreCase)
        || sym.Contains("All", StringComparison.OrdinalIgnoreCase);

    private static bool WriteChannelsBrightnessOnly(
        IDevice device,
        object syncRoot,
        bool useSdkLock,
        int[] channels,
        int[] brightness,
        out int failedChannel)
    {
        failedChannel = 0;
        bool ok = true;
        int failCh = 0;
        WithSdkLock(syncRoot, useSdkLock, () =>
        {
            for (int i = 0; i < channels.Length; i++)
            {
                int ch = channels[i];
                if (!SelectChannel(device, ch))
                {
                    failCh = ch;
                    ok = false;
                    return;
                }

                if (device.Parameters.SetIntValue(BrightnessNode, brightness[i]) != MvError.MV_OK)
                {
                    failCh = ch;
                    ok = false;
                    return;
                }
            }
        });

        if (!ok)
            failedChannel = failCh;

        return ok;
    }

    private static bool PrepareChannels(
        IDevice device,
        object syncRoot,
        bool useSdkLock,
        int[] channels,
        int[]? brightness,
        string armSource,
        out int failedChannel)
    {
        failedChannel = 0;
        bool writeBrightness = brightness != null;
        bool ok = true;
        int failCh = 0;

        WithSdkLock(syncRoot, useSdkLock, () =>
        {
            for (int i = 0; i < channels.Length; i++)
            {
                int ch = channels[i];
                if (!SelectChannel(device, ch))
                {
                    failCh = ch;
                    ok = false;
                    return;
                }

                if (writeBrightness)
                {
                    if (device.Parameters.SetIntValue(BrightnessNode, brightness![i]) != MvError.MV_OK)
                    {
                        failCh = ch;
                        ok = false;
                        return;
                    }
                }

                if (!SetSource(device, armSource))
                {
                    failCh = ch;
                    ok = false;
                    return;
                }
            }
        });

        if (!ok)
            failedChannel = failCh;

        return ok;
    }

    private static bool ApplyImmediateSource(
        IDevice device,
        object syncRoot,
        MvLeFlashSyncPlan plan,
        int[] channels,
        int[] brightness,
        string source,
        bool writeBrightness,
        out int failedChannel,
        out string modeTag)
    {
        modeTag = "immediate";
        if (!PrepareChannels(device, syncRoot, plan.UseSdkLock, channels, writeBrightness ? brightness : null, source, out failedChannel))
            return false;

        return true;
    }

    private static bool TryFireTimerTrigger(
        IDevice device,
        object syncRoot,
        MvLeFlashSyncPlan plan,
        bool timerOnly,
        out string usedCommand)
    {
        usedCommand = "";

        if (plan.TimerTriggerCommand is { Length: > 0 } cached
            && (!timerOnly || IsTimerTriggerName(cached)))
        {
            bool fired = false;
            WithSdkLock(syncRoot, plan.UseSdkLock, () =>
            {
                if (device.Parameters.SetCommandValue(cached) == MvError.MV_OK)
                    fired = true;
            });

            if (fired)
            {
                usedCommand = cached;
                return true;
            }

            plan.TimerTriggerCommand = null;
        }

        foreach (string candidate in OrderTriggerCandidates(plan, timerOnly))
        {
            bool fired = false;
            WithSdkLock(syncRoot, plan.UseSdkLock, () =>
            {
                if (device.Parameters.SetCommandValue(candidate) == MvError.MV_OK)
                    fired = true;
            });

            if (!fired)
                continue;

            plan.TimerTriggerCommand = candidate;
            usedCommand = candidate;
            return true;
        }

        return false;
    }

    private static IEnumerable<string> OrderTriggerCandidates(MvLeFlashSyncPlan plan, bool timerOnly)
    {
        foreach (string candidate in plan.TriggerCommandCandidates)
        {
            if (timerOnly && !IsTimerTriggerName(candidate))
                continue;

            yield return candidate;
        }

        foreach (string candidate in DefaultTriggerCommandCandidates)
        {
            if (plan.TriggerCommandCandidates.Contains(candidate))
                continue;

            if (timerOnly && !IsTimerTriggerName(candidate))
                continue;

            yield return candidate;
        }
    }

    private static bool IsTimerTriggerName(string command) =>
        command.Contains("Timer", StringComparison.OrdinalIgnoreCase);

    private static bool TryFindBroadcastSelector(IDevice device, out uint selector)
    {
        selector = 0;
        if (device.Parameters.GetEnumValue(SelectorNode, out IEnumValue? enumValue) != MvError.MV_OK
            || enumValue?.SupportEnumEntries == null)
        {
            if (device.Parameters.SetEnumValue(SelectorNode, 0) == MvError.MV_OK)
            {
                selector = 0;
                return true;
            }

            return false;
        }

        foreach (IEnumEntry entry in enumValue.SupportEnumEntries)
        {
            string sym = entry.Symbolic ?? "";
            if (sym.Equals("All", StringComparison.OrdinalIgnoreCase)
                || sym.Equals("ChannelAll", StringComparison.OrdinalIgnoreCase)
                || sym.Contains("All", StringComparison.OrdinalIgnoreCase))
            {
                selector = entry.Value;
                return device.Parameters.SetEnumValue(SelectorNode, selector) == MvError.MV_OK;
            }
        }

        if (device.Parameters.SetEnumValue(SelectorNode, 0) == MvError.MV_OK)
        {
            selector = 0;
            return true;
        }

        return false;
    }

    private static bool SelectChannel(IDevice device, int channel)
    {
        if (device.Parameters.SetEnumValue(SelectorNode, (uint)channel) == MvError.MV_OK)
            return true;

        string selector = channel.ToString(CultureInfo.InvariantCulture);
        return device.Parameters.SetEnumValueByString(SelectorNode, selector) == MvError.MV_OK;
    }

    private static bool SetSource(IDevice device, string source)
    {
        if (TrySourceNumeric(source, out uint numeric)
            && device.Parameters.SetEnumValue(SourceNode, numeric) == MvError.MV_OK)
        {
            return true;
        }

        return device.Parameters.SetEnumValueByString(SourceNode, source) == MvError.MV_OK;
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
}

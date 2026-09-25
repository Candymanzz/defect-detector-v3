namespace IoInputMonitor;

/// <summary>Как приложение участвует в Line0/DO.</summary>
public enum IoCaptureStrategyKind
{
    /// <summary>Soft: Timer N (MVS Trigger Source=Software) → Out.</summary>
    Timer,

    /// <summary>Soft: application drives one DO level pulse through MV_IO_SetMainOutputLevel.</summary>
    Direct,

    /// <summary>Hardwire DI→DO: приложение только UDP + лог DI, без soft-DO.</summary>
    Hardware
}

/// <summary>Стратегия реакции на FireDo (после DI↑ / gate).</summary>
internal interface IIoCaptureStrategy
{
    IoCaptureStrategyKind Kind { get; }

    string DisplayName { get; }

    /// <summary>true — приложение шлёт soft DO/Timer; false — только listen/log/UDP.</summary>
    bool FiresSoftwareDo { get; }

    void OnFireDo(
        IoCaptureGate? captureGate,
        IoBoxSession session,
        IoDoExecutor doExecutor,
        IoCapturePulseScheduler capturePulseScheduler,
        IoCaptureOptions capture,
        IoCaptureChannel channel,
        object consoleLock);
}

internal static class IoCaptureStrategyFactory
{
    public static IIoCaptureStrategy Create(IoCaptureOptions capture) =>
        capture.Strategy switch
        {
            IoCaptureStrategyKind.Hardware => new HardwareListenCaptureStrategy(),
            IoCaptureStrategyKind.Direct => new DirectSoftwareCaptureStrategy(),
            _ => new TimerSoftwareCaptureStrategy()
        };

    public static IoCaptureStrategyKind Parse(string? raw)
    {
        if (string.IsNullOrWhiteSpace(raw))
            return IoCaptureStrategyKind.Timer;

        return raw.Trim().ToLowerInvariant() switch
        {
            "hardware" or "hw" or "listen" or "passthrough" or "passive" => IoCaptureStrategyKind.Hardware,
            "direct" or "do" or "level" => IoCaptureStrategyKind.Direct,
            "timer" or "software" or "soft" or "mvs_timer" => IoCaptureStrategyKind.Timer,
            _ => IoCaptureStrategyKind.Timer
        };
    }
}

internal sealed class DirectSoftwareCaptureStrategy : IIoCaptureStrategy
{
    public IoCaptureStrategyKind Kind => IoCaptureStrategyKind.Direct;
    public string DisplayName => "direct (software level pulse -> DO)";
    public bool FiresSoftwareDo => true;

    public void OnFireDo(
        IoCaptureGate? captureGate,
        IoBoxSession session,
        IoDoExecutor doExecutor,
        IoCapturePulseScheduler capturePulseScheduler,
        IoCaptureOptions capture,
        IoCaptureChannel channel,
        object consoleLock)
    {
        IoCaptureOptions bound = capture.ForChannel(channel);
        bound.OutputMode = IoCaptureOutputMode.Direct;
        IoDi3CaptureRunner.StartFireDoAfterUdp(
            captureGate, session, doExecutor, capturePulseScheduler,
            bound, channel.TriggerPort, consoleLock);
    }
}

/// <summary>DI↑ → software Timer Execute (по каналу TimerN→OutN), опционально ×N с gap от фронта.</summary>
internal sealed class TimerSoftwareCaptureStrategy : IIoCaptureStrategy
{
    public IoCaptureStrategyKind Kind => IoCaptureStrategyKind.Timer;

    public string DisplayName => "timer (MVS Timer Software → Out)";

    public bool FiresSoftwareDo => true;

    public void OnFireDo(
        IoCaptureGate? captureGate,
        IoBoxSession session,
        IoDoExecutor doExecutor,
        IoCapturePulseScheduler capturePulseScheduler,
        IoCaptureOptions capture,
        IoCaptureChannel channel,
        object consoleLock)
    {
        IoCaptureOptions bound = capture.ForChannel(channel);
        // Стратегия timer всегда бьёт через Timer Software, не Direct SetOutput.
        bound.OutputMode = IoCaptureOutputMode.Timer;
        if (bound.TimerIndex is < 1 or > 8)
            bound.TimerIndex = channel.OutputPort;

        IoDi3CaptureRunner.StartFireDoAfterUdp(
            captureGate,
            session,
            doExecutor,
            capturePulseScheduler,
            bound,
            channel.TriggerPort,
            consoleLock);
    }
}

/// <summary>Аппаратный DI→DO: FireDo не трогает DO, только уже залогированный DI/UDP.</summary>
internal sealed class HardwareListenCaptureStrategy : IIoCaptureStrategy
{
    public IoCaptureStrategyKind Kind => IoCaptureStrategyKind.Hardware;

    public string DisplayName => "hardware (listen+log DI only, DO from hardwire)";

    public bool FiresSoftwareDo => false;

    public void OnFireDo(
        IoCaptureGate? captureGate,
        IoBoxSession session,
        IoDoExecutor doExecutor,
        IoCapturePulseScheduler capturePulseScheduler,
        IoCaptureOptions capture,
        IoCaptureChannel channel,
        object consoleLock)
    {
        lock (consoleLock)
        {
            Console.WriteLine(
                $"[{DateTime.Now:HH:mm:ss.fff}] capture_strategy=hardware — soft DO skip "
                + $"(жду аппаратный {channel.Format()})");
        }
    }
}

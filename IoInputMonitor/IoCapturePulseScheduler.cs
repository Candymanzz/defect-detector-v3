namespace IoInputMonitor;

/// <summary>
/// Ограничение параллельных DO-импульсов (two-phase: два DI3 через ~80 ms при pulse_delay_ms&gt;0).
/// </summary>
internal sealed class IoCapturePulseScheduler
{
    private readonly int _maxInflight;
    private int _inflight;

    public IoCapturePulseScheduler(int maxInflight = 1) =>
        _maxInflight = Math.Clamp(maxInflight, 1, 8);

    public bool IsBusy => Volatile.Read(ref _inflight) >= _maxInflight;

    /// <summary>true — можно стартовать импульс; false — лимит in-flight.</summary>
    public bool TryBegin()
    {
        while (true)
        {
            int current = Volatile.Read(ref _inflight);
            if (current >= _maxInflight)
                return false;
            if (Interlocked.CompareExchange(ref _inflight, current + 1, current) == current)
                return true;
        }
    }

    public void End()
    {
        while (true)
        {
            int current = Volatile.Read(ref _inflight);
            if (current <= 0)
                return;
            if (Interlocked.CompareExchange(ref _inflight, current - 1, current) == current)
                return;
        }
    }
}

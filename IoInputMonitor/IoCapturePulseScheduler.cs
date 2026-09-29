namespace IoInputMonitor;

/// <summary>
/// Один in-flight capture DO: при bounce DI3 (debounce=0) не копим очередь Task.Run с импульсами.
/// </summary>
internal sealed class IoCapturePulseScheduler
{
    private int _inflight;

    public bool IsBusy => Volatile.Read(ref _inflight) != 0;

    /// <summary>true — можно стартовать импульс; false — уже идёт, пропуск.</summary>
    public bool TryBegin() => Interlocked.CompareExchange(ref _inflight, 1, 0) == 0;

    public void End() => Interlocked.Exchange(ref _inflight, 0);
}

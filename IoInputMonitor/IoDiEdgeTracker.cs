namespace IoInputMonitor;

/// <summary>
/// Потокобезопасное состояние DI для edge: both (pressed) + software refractory.
/// SDK callback может писать одновременно — без lock both глотает/дублирует фронты.
/// </summary>
internal sealed class IoDiEdgeTracker
{
    private readonly object _lock = new();
    private readonly Dictionary<int, bool> _pressed = new();
    private readonly Dictionary<(int Port, MvIoNative.IoEdgeType Edge), long> _lastLoggedTicks = new();
    private readonly int _softwareRefractoryMs;

    public IoDiEdgeTracker(int softwareRefractoryMs)
    {
        _softwareRefractoryMs = Math.Max(0, softwareRefractoryMs);
    }

    public void Seed(int port, bool pressed)
    {
        lock (_lock)
            _pressed[port] = pressed;
    }

    public void SyncLevels(IReadOnlyDictionary<int, bool> levels)
    {
        lock (_lock)
        {
            foreach (var kv in levels)
                _pressed[kv.Key] = kv.Value;
        }
    }

    public bool TryGetPressed(int port, out bool pressed)
    {
        lock (_lock)
            return _pressed.TryGetValue(port, out pressed);
    }

    /// <summary>
    /// Принимает фронт: обновляет pressed (для both), применяет refractory.
    /// Возвращает false если дубль/refractory — событие игнорировать.
    /// </summary>
    public bool TryAccept(
        int port,
        MvIoNative.IoEdgeType edge,
        IoInputEdgeMode edgeMode,
        out bool closed)
    {
        closed = false;
        lock (_lock)
        {
            bool shouldAccept = edgeMode switch
            {
                IoInputEdgeMode.Both => TryTransitionPressedUnlocked(port, edge),
                IoInputEdgeMode.Rising when edge == MvIoNative.IoEdgeType.Rising => true,
                IoInputEdgeMode.Falling when edge == MvIoNative.IoEdgeType.Falling => true,
                _ => false
            };

            if (!shouldAccept)
                return false;

            if (!PassRefractoryUnlocked(port, edge))
                return false;

            closed = edgeMode == IoInputEdgeMode.Both
                ? _pressed.TryGetValue(port, out bool p) && p
                : edge == MvIoNative.IoEdgeType.Rising;
            return true;
        }
    }

    /// <summary>
    /// SDK хранит один edge-фильтр на порт. Для both после события — противоположный фронт.
    /// </summary>
    public static MvIoNative.IoEdgeType NextEdgeToArm(IoInputEdgeMode edgeMode, bool pressed) =>
        edgeMode switch
        {
            IoInputEdgeMode.Falling => MvIoNative.IoEdgeType.Falling,
            IoInputEdgeMode.Both when pressed => MvIoNative.IoEdgeType.Falling,
            _ => MvIoNative.IoEdgeType.Rising
        };

    private bool TryTransitionPressedUnlocked(int port, MvIoNative.IoEdgeType edge)
    {
        bool target = edge == MvIoNative.IoEdgeType.Rising;
        if (_pressed.TryGetValue(port, out bool current) && current == target)
            return false;

        _pressed[port] = target;
        return true;
    }

    private bool PassRefractoryUnlocked(int port, MvIoNative.IoEdgeType edge)
    {
        if (_softwareRefractoryMs <= 0)
            return true;

        long now = Environment.TickCount64;
        var key = (port, edge);
        if (_lastLoggedTicks.TryGetValue(key, out long last) && now - last < _softwareRefractoryMs)
            return false;

        _lastLoggedTicks[key] = now;
        return true;
    }
}

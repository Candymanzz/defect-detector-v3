namespace IoInputMonitor;

internal enum IoCaptureDecision
{
    None,
    DirectionArmed,
    DirectionDisarmed,
    FireDo,
    SkipNoDirection,
    SkipAlreadyFired,
    SkipBusy,
    DirectionModeChanged
}

internal enum IoLineDirection
{
    Forward,
    Reverse
}

/// <summary>
/// Съёмка по DI3↑ после направления DI2.
/// При DirectionLatch: первый DI2=1 вооружает; снятие — DI work↓ (если disarm_on_work_low) или Disarm().
/// </summary>
internal sealed class IoCaptureGate
{
    private readonly int _directionPort;
    private readonly int _triggerPort;
    private readonly int _workPort;
    private readonly bool _disarmOnWorkLow;
    private readonly bool _directionInvert;
    private readonly bool _requireDirection;
    private readonly bool _directionLatch;
    private readonly object _lock = new();

    private IoLineDirection _selectedDirection = IoLineDirection.Forward;
    private bool _directionRawActive;
    private bool _directionKnown;
    private bool _directionArmed;
    private bool _directionLatched;
    private bool _triggerActive;
    private bool _captureFiredThisPulse;
    /// <summary>Один кадр на окно DI2=1: повторный DI3↑ при том же DI2=1 — холостой.</summary>
    private bool _captureFiredThisDi2Window;

    public IoCaptureGate(IoCaptureOptions options)
    {
        _directionPort = options.DirectionPort;
        _triggerPort = options.TriggerPort;
        _workPort = options.WorkPort;
        _disarmOnWorkLow = options.DisarmOnWorkLow;
        _directionInvert = options.DirectionInvert;
        _requireDirection = options.RequireDirection;
        _directionLatch = options.DirectionLatch;
        _selectedDirection = ParseDirection(options.InitialDirection) ?? IoLineDirection.Forward;
    }

    public int DirectionPort => _directionPort;

    public int TriggerPort => _triggerPort;

    public int WorkPort => _workPort;

    public bool DisarmOnWorkLow => _disarmOnWorkLow;

    public bool IsDirectionArmed
    {
        get { lock (_lock) return _directionArmed; }
    }

    public bool IsDirectionLatched
    {
        get { lock (_lock) return _directionLatched; }
    }

    public IoLineDirection SelectedDirection
    {
        get { lock (_lock) return _selectedDirection; }
    }

    public string SelectedWireValue => SelectedDirection == IoLineDirection.Forward ? "forward" : "reverse";

    public bool IsSelectedForward => SelectedDirection == IoLineDirection.Forward;

    /// <summary>UI / HTTP: forward|reverse (отображение). На DO5 не влияет — фильтр только DI2.</summary>
    public IoCaptureDecision SetSelectedDirection(string? wireValue)
    {
        IoLineDirection? parsed = ParseDirection(wireValue);
        if (parsed == null)
            throw new ArgumentException("direction required (forward|reverse)");

        lock (_lock)
        {
            if (_selectedDirection == parsed.Value)
                return IoCaptureDecision.None;

            _selectedDirection = parsed.Value;
            return IoCaptureDecision.DirectionModeChanged;
        }
    }

    public void SeedDirection(bool active)
    {
        lock (_lock)
        {
            _directionRawActive = active;
            _directionKnown = true;
            TryArmFromCurrentDirection();
        }
    }

    /// <summary>Снять armed/latch (HTTP или DI work↓). Следующий DI2=1 снова вооружит.</summary>
    public IoCaptureDecision Disarm()
    {
        lock (_lock)
            return DisarmUnlocked();
    }

    public IoCaptureDecision Evaluate(int port, bool active, bool risingEdge)
    {
        lock (_lock)
        {
            if (_disarmOnWorkLow && _workPort is >= 1 and <= 8 && port == _workPort)
            {
                if (!active && !risingEdge)
                    return DisarmUnlocked();
                return IoCaptureDecision.None;
            }

            if (port == _directionPort)
            {
                bool prevHigh = _directionKnown && MapDirection(_directionRawActive);
                _directionRawActive = active;
                _directionKnown = true;
                bool nowHigh = MapDirection(active);

                // Новое окно DI2=1 / конец окна — снова разрешаем один DI3.
                if (prevHigh != nowHigh)
                    _captureFiredThisDi2Window = false;

                // После latch все смены DI2 — холостые (направление уже зафиксировано).
                if (_directionLatch && _directionLatched)
                    return IoCaptureDecision.None;

                bool wasArmed = _directionArmed;
                TryArmFromCurrentDirection();
                return !wasArmed && _directionArmed
                    ? IoCaptureDecision.DirectionArmed
                    : IoCaptureDecision.None;
            }

            if (port != _triggerPort)
                return IoCaptureDecision.None;

            IoCaptureDecision decision = IoCaptureDecision.None;
            bool di2High = _directionKnown && MapDirection(_directionRawActive);
            if (risingEdge && active && !_triggerActive)
            {
                if (_requireDirection && !_directionArmed)
                {
                    decision = IoCaptureDecision.SkipNoDirection;
                }
                else if (_captureFiredThisPulse)
                {
                    decision = IoCaptureDecision.SkipAlreadyFired;
                }
                else if (di2High && _captureFiredThisDi2Window)
                {
                    // DI2 ещё 1, а DI3 пришёл повторно — холостой проход.
                    decision = IoCaptureDecision.SkipAlreadyFired;
                }
                else
                {
                    _captureFiredThisPulse = true;
                    if (di2High)
                        _captureFiredThisDi2Window = true;
                    decision = IoCaptureDecision.FireDo;
                }
            }

            if (!active && _triggerActive)
                _captureFiredThisPulse = false;

            _triggerActive = active;

            // DI3 Rising-only (короткий photoeye): Falling в Evaluate не приходит —
            // без сброса _triggerActive залипает HIGH и следующие DI3↑ = None (нет DO5).
            // Окно DI2 (_captureFiredThisDi2Window) НЕ сбрасываем — иначе повторный DI3 при DI2=1 снова стреляет.
            if (risingEdge && active)
            {
                _triggerActive = false;
                _captureFiredThisPulse = false;
            }

            return decision;
        }
    }

    private IoCaptureDecision DisarmUnlocked()
    {
        if (!_directionArmed && !_directionLatched)
            return IoCaptureDecision.None;

        _directionArmed = false;
        _directionLatched = false;
        _captureFiredThisPulse = false;
        _captureFiredThisDi2Window = false;
        return IoCaptureDecision.DirectionDisarmed;
    }

    public bool TryFireCapture(int port, bool active, bool risingEdge) =>
        Evaluate(port, active, risingEdge) == IoCaptureDecision.FireDo;

    /// <summary>
    /// Откат слота после FireDo, если импульс не стартовал (scheduler busy).
    /// Иначе DI3↑ «съеден» без DO до следующего полного цикла.
    /// </summary>
    public void ReleaseCaptureFireSlot()
    {
        lock (_lock)
        {
            _captureFiredThisPulse = false;
            _captureFiredThisDi2Window = false;
        }
    }

    public string DescribeExpectedArm()
    {
        lock (_lock)
        {
            if (!_requireDirection)
                return $"DI{_triggerPort}↑";
            if (_directionLatch)
            {
                string disarmHint = _disarmOnWorkLow && _workPort is >= 1 and <= 8
                    ? $"; DI{_workPort}↓ снимает"
                    : "";
                return _directionLatched
                    ? $"DI{_triggerPort}↑ (направление зафиксировано{disarmHint})"
                    : $"один раз DI{_directionPort}=1, далее DI{_triggerPort}↑{disarmHint}";
            }

            return $"DI{_directionPort}=1 затем DI{_triggerPort}↑";
        }
    }

    private void TryArmFromCurrentDirection()
    {
        if (!_requireDirection)
        {
            _directionArmed = true;
            return;
        }

        if (!_directionKnown)
            return;

        if (_directionLatch && _directionLatched)
        {
            _directionArmed = true;
            return;
        }

        bool forward = MapDirection(_directionRawActive);
        if (forward)
        {
            _directionArmed = true;
            if (_directionLatch)
                _directionLatched = true;
        }
        else if (!_directionLatch)
        {
            // Без latch: DI2=0 снимает armed.
            _directionArmed = false;
        }
    }

    private bool MapDirection(bool raw) =>
        _directionInvert ? !raw : raw;

    internal static IoLineDirection? ParseDirection(string? raw)
    {
        if (string.IsNullOrWhiteSpace(raw))
            return null;

        return raw.Trim().ToLowerInvariant() switch
        {
            "forward" or "1" or "true" => IoLineDirection.Forward,
            "reverse" or "0" or "false" => IoLineDirection.Reverse,
            _ => null
        };
    }
}

public sealed class IoCaptureOptions
{
    public bool Enabled { get; set; }

    public int DirectionPort { get; set; } = 2;

    public int TriggerPort { get; set; } = 3;

    /// <summary>Основной DO съёмки (1..8). Совпадает с первым в OutputPorts.</summary>
    public int OutputPort { get; set; } = 5;

    /// <summary>DO для импульса по DI3↑ (напр. [5]). Пусто → только OutputPort.</summary>
    public int[] OutputPorts { get; set; } = [5];

    /// <summary>Порты съёмки без дублей (всегда ≥1).</summary>
    public int[] ResolveOutputPorts()
    {
        if (OutputPorts is { Length: > 0 })
        {
            var ports = new List<int>(OutputPorts.Length);
            foreach (int p in OutputPorts)
            {
                if (p is >= 1 and <= 8 && !ports.Contains(p))
                    ports.Add(p);
            }

            if (ports.Count > 0)
                return ports.ToArray();
        }

        int primary = OutputPort is >= 1 and <= 8 ? OutputPort : 5;
        return [primary];
    }

    public string FormatOutputPorts() =>
        string.Join("+", ResolveOutputPorts().Select(static p => $"DO{p}"));

    /// <summary>direct = MV_IO_SetOutput; timer = software trigger Timer N (Out5←Timer в MVS).</summary>
    public IoCaptureOutputMode OutputMode { get; set; } = IoCaptureOutputMode.Auto;

    /// <summary>Номер таймера в MVS (Timer 1 → timer_index: 1).</summary>
    public int TimerIndex { get; set; } = 1;

    public int PulseDurationMs { get; set; } = 50;

    /// <summary>
    /// Пауза после UDP DI3 перед DO: дать Java/камерам войти в wait_frame (Line0 RisingEdge).
    /// 0 = DO сразу (часто промах: импульс уходит до arm).
    /// </summary>
    public int PulseDelayMs { get; set; } = 250;

    /// <summary>Сколько раз повторить DO после delay (edge мог попасть в flush).</summary>
    public int PulseRepeat { get; set; } = 1;

    /// <summary>Пауза между повторными DO-импульсами.</summary>
    public int PulseRepeatGapMs { get; set; } = 80;

    /// <summary>
    /// Уровень SDK при импульсе DO (Level / MainOutputLevel).
    /// Должен быть согласован с line0_trigger_activation камер:
    /// RisingEdge ↔ true (электрический ↑), FallingEdge ↔ true на NPN (энергия = линия ↓).
    /// </summary>
    public bool ActiveHigh { get; set; } = true;

    /// <summary>rising|falling — для логов; камеры читают line0_trigger_activation из config.json.</summary>
    public string Line0Edge { get; set; } = "rising";

    public bool DirectionInvert { get; set; }

    public bool RequireDirection { get; set; } = true;

    /// <summary>
    /// Первый DI2=1 фиксирует направление; снятие — Disarm() / work_port↓ при disarm_on_work_low.
    /// Без снятия каждый последующий DI3↑ продолжает FireDo — «сигналы не прекращаются».
    /// </summary>
    public bool DirectionLatch { get; set; } = true;

    /// <summary>DI «работа/конвейер» (обычно 1). При disarm_on_work_low: DI↓ снимает latch.</summary>
    public int WorkPort { get; set; } = 1;

    /// <summary>true — DI work↓ → Disarm (иначе latch живёт до рестарта процесса).</summary>
    public bool DisarmOnWorkLow { get; set; } = true;

    /// <summary>Начальный UI-ход (отображение); на DO не влияет.</summary>
    public string InitialDirection { get; set; } = "forward";

    public IoDirectionHttpOptions DirectionHttp { get; set; } = new();
}

public sealed class IoDirectionHttpOptions
{
    public bool Enabled { get; set; } = true;

    public string Host { get; set; } = "127.0.0.1";

    public int Port { get; set; } = 9101;
}


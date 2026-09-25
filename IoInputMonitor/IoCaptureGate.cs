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
/// Съёмка по DI↑ (каналы DI3→DO5, DI5→DO7, …) после направления DI2.
/// При DirectionLatch: первый DI2=1 вооружает; снятие — DI work↓ (если disarm_on_work_low) или Disarm().
/// </summary>
internal sealed class IoCaptureGate
{
    private readonly int _directionPort;
    private readonly int[] _triggerPorts;
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
    private readonly Dictionary<int, bool> _triggerActive = new();
    private readonly Dictionary<int, bool> _captureFiredThisPulse = new();
    /// <summary>Сколько FireDo уже дали по каждому trigger в текущем окне DI2=1.</summary>
    private readonly Dictionary<int, int> _capturesThisDi2Window = new();
    private readonly int _maxCapturesPerDi2Window;
    private int _lastFiredTriggerPort;

    public IoCaptureGate(IoCaptureOptions options)
    {
        _directionPort = options.DirectionPort;
        _triggerPorts = options.ResolveTriggerPorts();
        _workPort = options.WorkPort;
        _disarmOnWorkLow = options.DisarmOnWorkLow;
        _directionInvert = options.DirectionInvert;
        _requireDirection = options.RequireDirection;
        _directionLatch = options.DirectionLatch;
        _maxCapturesPerDi2Window = options.EffectiveMaxDi3CapturesPerDi2Window();
        _selectedDirection = ParseDirection(options.InitialDirection) ?? IoLineDirection.Forward;
        foreach (int di in _triggerPorts)
        {
            _triggerActive[di] = false;
            _captureFiredThisPulse[di] = false;
            _capturesThisDi2Window[di] = 0;
        }
    }

    public int DirectionPort => _directionPort;

    /// <summary>Первый (primary) trigger-порт — для логов/HTTP synthetic.</summary>
    public int TriggerPort => _triggerPorts.Length > 0 ? _triggerPorts[0] : 3;

    public IReadOnlyList<int> TriggerPorts => _triggerPorts;

    /// <summary>DI, по которому только что вернули FireDo.</summary>
    public int LastFiredTriggerPort
    {
        get { lock (_lock) return _lastFiredTriggerPort; }
    }

    public bool IsTriggerPort(int port)
    {
        foreach (int di in _triggerPorts)
        {
            if (di == port)
                return true;
        }

        return false;
    }

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

                // Новое окно DI2=1 / конец окна — снова принимаем trigger↑ (до max на канал).
                if (prevHigh != nowHigh)
                    ResetWindowCapturesUnlocked();

                // После latch все смены DI2 — холостые (направление уже зафиксировано).
                if (_directionLatch && _directionLatched)
                    return IoCaptureDecision.None;

                bool wasArmed = _directionArmed;
                TryArmFromCurrentDirection();
                return !wasArmed && _directionArmed
                    ? IoCaptureDecision.DirectionArmed
                    : IoCaptureDecision.None;
            }

            if (!IsTriggerPort(port))
                return IoCaptureDecision.None;

            bool triggerActive = _triggerActive.GetValueOrDefault(port);
            bool firedThisPulse = _captureFiredThisPulse.GetValueOrDefault(port);
            int windowCaptures = _capturesThisDi2Window.GetValueOrDefault(port);

            IoCaptureDecision decision = IoCaptureDecision.None;
            bool di2High = _directionKnown && MapDirection(_directionRawActive);
            if (risingEdge && active && !triggerActive)
            {
                if (_requireDirection && !_directionArmed)
                {
                    decision = IoCaptureDecision.SkipNoDirection;
                }
                else if (firedThisPulse)
                {
                    decision = IoCaptureDecision.SkipAlreadyFired;
                }
                else if (di2High && windowCaptures >= _maxCapturesPerDi2Window)
                {
                    decision = IoCaptureDecision.SkipAlreadyFired;
                }
                else
                {
                    _captureFiredThisPulse[port] = true;
                    if (di2High)
                        _capturesThisDi2Window[port] = windowCaptures + 1;
                    _lastFiredTriggerPort = port;
                    decision = IoCaptureDecision.FireDo;
                }
            }

            if (!active && triggerActive)
                _captureFiredThisPulse[port] = false;

            _triggerActive[port] = active;

            // Rising-only (короткий photoeye): Falling в Evaluate не приходит —
            // без сброса _triggerActive залипает HIGH и следующие ↑ = None.
            // Счётчик на окне DI2 НЕ сбрасываем здесь.
            if (risingEdge && active)
            {
                _triggerActive[port] = false;
                _captureFiredThisPulse[port] = false;
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
        foreach (int di in _triggerPorts)
        {
            _captureFiredThisPulse[di] = false;
            _capturesThisDi2Window[di] = 0;
        }

        return IoCaptureDecision.DirectionDisarmed;
    }

    private void ResetWindowCapturesUnlocked()
    {
        foreach (int di in _triggerPorts)
            _capturesThisDi2Window[di] = 0;
    }

    public bool TryFireCapture(int port, bool active, bool risingEdge) =>
        Evaluate(port, active, risingEdge) == IoCaptureDecision.FireDo;

    /// <summary>
    /// Откат слота после FireDo, если импульс не стартовал (scheduler busy).
    /// Иначе DI↑ «съеден» без DO до следующего полного цикла.
    /// </summary>
    public void ReleaseCaptureFireSlot(int? triggerPort = null)
    {
        lock (_lock)
        {
            int port = triggerPort is >= 1 and <= 8 ? triggerPort.Value : _lastFiredTriggerPort;
            if (port is < 1 or > 8)
                return;

            _captureFiredThisPulse[port] = false;
            if (_capturesThisDi2Window.TryGetValue(port, out int n) && n > 0)
                _capturesThisDi2Window[port] = n - 1;
        }
    }

    public string DescribeExpectedArm()
    {
        lock (_lock)
        {
            string triggers = string.Join("/", _triggerPorts.Select(static p => $"DI{p}↑"));
            if (!_requireDirection)
                return triggers;
            if (_directionLatch)
            {
                string disarmHint = _disarmOnWorkLow && _workPort is >= 1 and <= 8
                    ? $"; DI{_workPort}↓ снимает"
                    : "";
                return _directionLatched
                    ? $"{triggers} (направление зафиксировано{disarmHint})"
                    : $"один раз DI{_directionPort}=1, далее {triggers}{disarmHint}";
            }

            return $"DI{_directionPort}=1 затем {triggers}";
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

/// <summary>Один канал съёмки: DI trigger → DO (+ Timer для soft).</summary>
public sealed class IoCaptureChannel
{
    public int TriggerPort { get; set; } = 3;

    public int OutputPort { get; set; } = 5;

    /// <summary>MVS Timer N → OutN (обычно = OutputPort).</summary>
    public int TimerIndex { get; set; } = 5;

    public string Format() => $"DI{TriggerPort}→DO{OutputPort}/T{TimerIndex}";
}

public sealed class IoCaptureOptions
{
    public bool Enabled { get; set; }

    public int DirectionPort { get; set; } = 2;

    /// <summary>Primary trigger (первый канал). Совместимость / HTTP synthetic.</summary>
    public int TriggerPort { get; set; } = 3;

    /// <summary>Основной DO съёмки (первый канал). Совпадает с первым в OutputPorts.</summary>
    public int OutputPort { get; set; } = 5;

    /// <summary>DO для импульса primary-канала (напр. [5]). Пусто → только OutputPort.</summary>
    public int[] OutputPorts { get; set; } = [5];

    /// <summary>
    /// Независимые пары DI→DO. Пусто → один канал из TriggerPort/OutputPort/TimerIndex.
    /// </summary>
    public IoCaptureChannel[] Channels { get; set; } = [];

    public IoCaptureChannel[] ResolveChannels()
    {
        if (Channels is { Length: > 0 })
        {
            var list = new List<IoCaptureChannel>(Channels.Length);
            var seenDi = new HashSet<int>();
            foreach (IoCaptureChannel ch in Channels)
            {
                if (ch.TriggerPort is < 1 or > 8 || ch.OutputPort is < 1 or > 8)
                    continue;
                if (!seenDi.Add(ch.TriggerPort))
                    continue;
                int timer = ch.TimerIndex is >= 1 and <= 8 ? ch.TimerIndex : ch.OutputPort;
                list.Add(new IoCaptureChannel
                {
                    TriggerPort = ch.TriggerPort,
                    OutputPort = ch.OutputPort,
                    TimerIndex = timer
                });
            }

            if (list.Count > 0)
                return list.ToArray();
        }

        int di = TriggerPort is >= 1 and <= 8 ? TriggerPort : 3;
        int dout = OutputPort is >= 1 and <= 8 ? OutputPort : 5;
        int t = TimerIndex is >= 1 and <= 8 ? TimerIndex : dout;
        return
        [
            new IoCaptureChannel
            {
                TriggerPort = di,
                OutputPort = dout,
                TimerIndex = t
            }
        ];
    }

    public int[] ResolveTriggerPorts() =>
        ResolveChannels().Select(static c => c.TriggerPort).ToArray();

    public bool TryGetChannel(int triggerPort, out IoCaptureChannel channel)
    {
        foreach (IoCaptureChannel ch in ResolveChannels())
        {
            if (ch.TriggerPort == triggerPort)
            {
                channel = ch;
                return true;
            }
        }

        channel = new IoCaptureChannel();
        return false;
    }

    public bool IsTriggerPort(int port)
    {
        foreach (int di in ResolveTriggerPorts())
        {
            if (di == port)
                return true;
        }

        return false;
    }

    /// <summary>Порты съёмки без дублей (все каналы).</summary>
    public int[] ResolveOutputPorts()
    {
        var ports = new List<int>();
        foreach (IoCaptureChannel ch in ResolveChannels())
        {
            if (!ports.Contains(ch.OutputPort))
                ports.Add(ch.OutputPort);
        }

        if (ports.Count > 0)
            return ports.ToArray();

        int primary = OutputPort is >= 1 and <= 8 ? OutputPort : 5;
        return [primary];
    }

    public string FormatOutputPorts() =>
        string.Join("+", ResolveOutputPorts().Select(static p => $"DO{p}"));

    public string FormatChannels() =>
        string.Join(", ", ResolveChannels().Select(static c => c.Format()));

    /// <summary>Копия timing/режима с портами выбранного канала (для параллельных FireDo).</summary>
    public IoCaptureOptions ForChannel(IoCaptureChannel channel) =>
        new()
        {
            Enabled = Enabled,
            DirectionPort = DirectionPort,
            TriggerPort = channel.TriggerPort,
            OutputPort = channel.OutputPort,
            OutputPorts = [channel.OutputPort],
            Channels =
            [
                new IoCaptureChannel
                {
                    TriggerPort = channel.TriggerPort,
                    OutputPort = channel.OutputPort,
                    TimerIndex = channel.TimerIndex
                }
            ],
            OutputMode = OutputMode,
            Strategy = Strategy,
            TimerIndex = channel.TimerIndex,
            PulseDurationMs = PulseDurationMs,
            PulseDelayMs = PulseDelayMs,
            PulseRepeat = PulseRepeat,
            PulseRepeatGapMs = PulseRepeatGapMs,
            ActiveHigh = ActiveHigh,
            Line0Edge = Line0Edge,
            DirectionInvert = DirectionInvert,
            RequireDirection = RequireDirection,
            DirectionLatch = DirectionLatch,
            WorkPort = WorkPort,
            DisarmOnWorkLow = DisarmOnWorkLow,
            InitialDirection = InitialDirection,
            RepeatDi3Capture = RepeatDi3Capture,
            MaxDi3CapturesPerDi2Window = MaxDi3CapturesPerDi2Window,
            DirectionHttp = DirectionHttp
        };

    /// <summary>direct = MV_IO_SetOutput; timer = software trigger Timer N (Out←Timer в MVS).</summary>
    public IoCaptureOutputMode OutputMode { get; set; } = IoCaptureOutputMode.Auto;

    /// <summary>
    /// timer — soft Timer Software → Out; hardware — только слушать/логировать DI (DO с платы).
    /// </summary>
    public IoCaptureStrategyKind Strategy { get; set; } = IoCaptureStrategyKind.Timer;

    /// <summary>Номер таймера primary-канала в MVS (Timer 5 → timer_index: 5).</summary>
    public int TimerIndex { get; set; } = 5;

    public int PulseDurationMs { get; set; } = 50;

    /// <summary>
    /// Пауза после UDP DI перед DO: дать Java/камерам войти в wait_frame (Line0 RisingEdge).
    /// 0 = DO сразу (часто промах: импульс уходит до arm).
    /// </summary>
    public int PulseDelayMs { get; set; } = 0;

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
    /// Без снятия каждый последующий DI↑ продолжает FireDo — «сигналы не прекращаются».
    /// </summary>
    public bool DirectionLatch { get; set; } = true;

    /// <summary>DI «работа/конвейер» (обычно 1). При disarm_on_work_low: DI↓ снимает latch.</summary>
    public int WorkPort { get; set; } = 1;

    /// <summary>true — DI work↓ → Disarm (иначе latch живёт до рестарта процесса).</summary>
    public bool DisarmOnWorkLow { get; set; } = true;

    /// <summary>Начальный UI-ход (отображение); на DO не влияет.</summary>
    public string InitialDirection { get; set; } = "forward";

    /// <summary>
    /// Два DI↑ при одном DI2=1 на канал (two-phase). Эквивалент max_di3_captures_per_di2_window=2.
    /// </summary>
    public bool RepeatDi3Capture { get; set; }

    /// <summary>
    /// Сколько DI↑ на канал дают FireDo+UDP, пока DI2=1. 0 — из RepeatDi3Capture (2) или 1.
    /// </summary>
    public int MaxDi3CapturesPerDi2Window { get; set; }

    public int EffectiveMaxDi3CapturesPerDi2Window()
    {
        if (MaxDi3CapturesPerDi2Window > 0)
            return Math.Clamp(MaxDi3CapturesPerDi2Window, 1, 8);

        return RepeatDi3Capture ? 2 : 1;
    }

    public IoDirectionHttpOptions DirectionHttp { get; set; } = new();
}

public sealed class IoDirectionHttpOptions
{
    public bool Enabled { get; set; } = true;

    public string Host { get; set; } = "127.0.0.1";

    public int Port { get; set; } = 9101;
}


namespace IoInputMonitor;

internal enum IoCaptureDecision
{
    None,
    DirectionArmed,
    FireDo,
    SkipNoDirection,
    SkipAlreadyFired,
    DirectionModeChanged
}

internal enum IoLineDirection
{
    Forward,
    Reverse
}

/// <summary>
/// Съёмка по DI3↑ после направления DI2.
/// При DirectionLatch: первый DI2=1 вооружает навсегда, дальше DI2 холостой.
/// </summary>
internal sealed class IoCaptureGate
{
    private readonly int _directionPort;
    private readonly int _triggerPort;
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

    public IoCaptureGate(IoCaptureOptions options)
    {
        _directionPort = options.DirectionPort;
        _triggerPort = options.TriggerPort;
        _directionInvert = options.DirectionInvert;
        _requireDirection = options.RequireDirection;
        _directionLatch = options.DirectionLatch;
        _selectedDirection = ParseDirection(options.InitialDirection) ?? IoLineDirection.Forward;
    }

    public int DirectionPort => _directionPort;

    public int TriggerPort => _triggerPort;

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

    public IoCaptureDecision Evaluate(int port, bool active, bool risingEdge)
    {
        lock (_lock)
        {
            if (port == _directionPort)
            {
                // После latch все смены DI2 — холостые (направление уже зафиксировано).
                if (_directionLatch && _directionLatched)
                {
                    _directionRawActive = active;
                    _directionKnown = true;
                    return IoCaptureDecision.None;
                }

                bool wasArmed = _directionArmed;
                _directionRawActive = active;
                _directionKnown = true;
                TryArmFromCurrentDirection();
                return !wasArmed && _directionArmed
                    ? IoCaptureDecision.DirectionArmed
                    : IoCaptureDecision.None;
            }

            if (port != _triggerPort)
                return IoCaptureDecision.None;

            IoCaptureDecision decision = IoCaptureDecision.None;
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
                else
                {
                    _captureFiredThisPulse = true;
                    decision = IoCaptureDecision.FireDo;
                }
            }

            if (!active && _triggerActive)
                _captureFiredThisPulse = false;

            _triggerActive = active;
            return decision;
        }
    }

    public bool TryFireCapture(int port, bool active, bool risingEdge) =>
        Evaluate(port, active, risingEdge) == IoCaptureDecision.FireDo;

    public string DescribeExpectedArm()
    {
        lock (_lock)
        {
            if (!_requireDirection)
                return $"DI{_triggerPort}↑";
            if (_directionLatch)
            {
                return _directionLatched
                    ? $"DI{_triggerPort}↑ (направление зафиксировано)"
                    : $"один раз DI{_directionPort}=1, далее DI{_triggerPort}↑";
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

    /// <summary>DO-порт IO box (1..8; по умолчанию DO5 → Line0 камер).</summary>
    public int OutputPort { get; set; } = 5;

    /// <summary>direct = MV_IO_SetOutput; timer = software trigger Timer N (Out5←Timer в MVS).</summary>
    public IoCaptureOutputMode OutputMode { get; set; } = IoCaptureOutputMode.Auto;

    /// <summary>Номер таймера в MVS (Timer 1 → timer_index: 1).</summary>
    public int TimerIndex { get; set; } = 1;

    public int PulseDurationMs { get; set; } = 20;

    /// <summary>
    /// Пауза после UDP DI3 перед DO: дать Java/камерам войти в wait_frame (Line0 RisingEdge).
    /// 0 = DO сразу (часто промах: импульс уходит до arm).
    /// </summary>
    public int PulseDelayMs { get; set; } = 80;

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
    public string Line0Edge { get; set; } = "falling";

    public bool DirectionInvert { get; set; }

    public bool RequireDirection { get; set; } = true;

    /// <summary>
    /// Первый DI2=1 фиксирует направление навсегда; дальнейшие DI2 не снимают armed.
    /// </summary>
    public bool DirectionLatch { get; set; } = true;

    /// <summary>Начальный UI-ход (отображение); на DO5 не влияет.</summary>
    public string InitialDirection { get; set; } = "forward";

    public IoDirectionHttpOptions DirectionHttp { get; set; } = new();
}

public sealed class IoDirectionHttpOptions
{
    public bool Enabled { get; set; } = true;

    public string Host { get; set; } = "127.0.0.1";

    public int Port { get; set; } = 9101;
}

/// <summary>
/// DO → физические входы ПЛК (техзрение). FINS только для таймаутов D4400–D4404.
/// X4 готовность, X5 ошибка, X6/X7 брак линий. CIO 240.15 (сброс DI) не используется.
/// </summary>
public sealed class IoRejectOptions
{
    public bool Enabled { get; set; }

    /// <summary>MV IO DO → PLC X4 (Техзрение готовность), уровень. Default DO1.</summary>
    public int ReadyOutputPort { get; set; } = 1;

    /// <summary>MV IO DO → PLC X5 (Техзрение ошибка), уровень. Default DO2.</summary>
    public int FaultOutputPort { get; set; } = 2;

    /// <summary>MV IO DO → PLC X6 (брак линия 1), импульс. Default DO3.</summary>
    public int Line1OutputPort { get; set; } = 3;

    /// <summary>MV IO DO → PLC X7 (брак линия 2), импульс. Default DO4.</summary>
    public int Line2OutputPort { get; set; } = 4;

    public int PulseDurationMs { get; set; } = 50;

    public bool ActiveHigh { get; set; } = true;
}

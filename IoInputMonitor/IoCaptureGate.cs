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
/// Съёмка: DI2 должен совпасть с UI-ходом, затем DI3↑ → DO.
/// Прямой: DI2=1 → DI3↑; обратный: DI2=0 → DI3↑ (наоборот).
/// </summary>
internal sealed class IoCaptureGate
{
    private readonly int _directionPort;
    private readonly int _triggerPort;
    private readonly bool _directionInvert;
    private readonly bool _requireDirection;
    private readonly object _lock = new();

    private IoLineDirection _selectedDirection = IoLineDirection.Reverse;
    private bool _directionRawActive;
    private bool _directionKnown;
    private bool _directionArmed;
    private bool _triggerActive;
    private bool _captureFiredThisPulse;

    public IoCaptureGate(IoCaptureOptions options)
    {
        _directionPort = options.DirectionPort;
        _triggerPort = options.TriggerPort;
        _directionInvert = options.DirectionInvert;
        _requireDirection = options.RequireDirection;
        _selectedDirection = ParseDirection(options.InitialDirection) ?? IoLineDirection.Reverse;
    }

    public int DirectionPort => _directionPort;

    public int TriggerPort => _triggerPort;

    public bool IsDirectionArmed
    {
        get { lock (_lock) return _directionArmed; }
    }

    public IoLineDirection SelectedDirection
    {
        get { lock (_lock) return _selectedDirection; }
    }

    public string SelectedWireValue => SelectedDirection == IoLineDirection.Forward ? "forward" : "reverse";

    public bool IsSelectedForward => SelectedDirection == IoLineDirection.Forward;

    /// <summary>UI / HTTP: forward|reverse. Сбрасывает arm и переоценивает текущий DI2.</summary>
    public IoCaptureDecision SetSelectedDirection(string? wireValue)
    {
        IoLineDirection? parsed = ParseDirection(wireValue);
        if (parsed == null)
            throw new ArgumentException("direction required (forward|reverse)");

        lock (_lock)
        {
            if (_selectedDirection == parsed.Value && _directionArmed == TravelMatchesSelected())
                return IoCaptureDecision.None;

            _selectedDirection = parsed.Value;
            _directionArmed = false;
            _captureFiredThisPulse = false;
            TryArmFromCurrentDirection();
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
            return _selectedDirection == IoLineDirection.Forward
                ? $"DI{_directionPort}=1 затем DI{_triggerPort}↑"
                : $"DI{_directionPort}=0 затем DI{_triggerPort}↑";
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

        // Armed только пока текущий DI2 совпадает с выбранным UI-ходом.
        _directionArmed = TravelMatchesSelected();
    }

    private bool TravelMatchesSelected()
    {
        bool travelForward = MapDirection(_directionRawActive);
        bool selectedForward = _selectedDirection == IoLineDirection.Forward;
        return travelForward == selectedForward;
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
    public int PulseRepeat { get; set; } = 3;

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

    /// <summary>Начальный ход (пока UI не переключил): forward|reverse.</summary>
    public string InitialDirection { get; set; } = "reverse";

    public IoDirectionHttpOptions DirectionHttp { get; set; } = new();
}

public sealed class IoDirectionHttpOptions
{
    public bool Enabled { get; set; } = true;

    public string Host { get; set; } = "127.0.0.1";

    public int Port { get; set; } = 9101;
}

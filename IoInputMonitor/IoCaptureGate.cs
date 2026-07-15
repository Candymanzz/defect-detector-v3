namespace IoInputMonitor;

/// <summary>
/// Логика съёмки как в оркестраторе (IoInputDirectionAutoCapture):
/// фаза 1 — DI2=1 вооружает направление; фаза 2 — DI3↑ даёт импульс на DO (Line0).
/// </summary>
internal sealed class IoCaptureGate
{
    private readonly int _directionPort;
    private readonly int _triggerPort;
    private readonly bool _directionInvert;
    private readonly bool _requireDirection;

    private bool _directionArmed;
    private bool _triggerActive;
    private bool _captureFiredThisPulse;

    public IoCaptureGate(IoCaptureOptions options)
    {
        _directionPort = options.DirectionPort;
        _triggerPort = options.TriggerPort;
        _directionInvert = options.DirectionInvert;
        _requireDirection = options.RequireDirection;
    }

    public int DirectionPort => _directionPort;

    public int TriggerPort => _triggerPort;

    /// <summary>Обработка смены DI; возвращает true, если нужен импульс DO (квалифицированный DI3↑).</summary>
    public bool TryFireCapture(int port, bool active, bool risingEdge)
    {
        if (port == _directionPort)
        {
            OnDirectionChange(active);
            return false;
        }

        if (port != _triggerPort)
            return false;

        bool fireDo = false;
        if (risingEdge && active && !_triggerActive)
        {
            if (!_requireDirection || _directionArmed)
                fireDo = TryCommitCapture();
        }

        if (!active && _triggerActive)
            _captureFiredThisPulse = false;

        _triggerActive = active;
        return fireDo;
    }

    public void SeedDirection(bool active) => OnDirectionChange(active);

    public bool IsDirectionArmed => _directionArmed;

    private void OnDirectionChange(bool active)
    {
        if (_requireDirection && !_directionArmed && IsForwardRaw(active))
            _directionArmed = true;
    }

    private bool TryCommitCapture()
    {
        if (_captureFiredThisPulse)
            return false;

        if (_requireDirection && !_directionArmed)
            return false;

        _captureFiredThisPulse = true;
        _triggerActive = true;
        return true;
    }

    private bool IsForwardRaw(bool raw) =>
        _directionInvert ? !raw : raw;
}

internal sealed class IoCaptureOptions
{
    public bool Enabled { get; set; }

    public int DirectionPort { get; set; } = 2;

    public int TriggerPort { get; set; } = 3;

    /// <summary>DO-порт IO box (1=DO0/первый выход → Line0 камер).</summary>
    public int OutputPort { get; set; } = 1;

    public int PulseDurationMs { get; set; } = 20;

    public bool DirectionInvert { get; set; }

    public bool RequireDirection { get; set; } = true;
}

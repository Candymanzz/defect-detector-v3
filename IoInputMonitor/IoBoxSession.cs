namespace IoInputMonitor;

/// <summary>Сессия с IO box: CreateHandle → Open(COM) → DI / edge callback → Close.</summary>
internal sealed class IoBoxSession : IDisposable
{
    private IntPtr _handle;
    private bool _disposed;
    private MvIoNative.EdgeDetectionCallback? _edgeCallback;
    private readonly Dictionary<int, (uint Edge, uint DebounceMs, uint DelayMs)> _configuredInputs = new();
    /// <summary>Кэш board-exact для DO reject — без GetPortOutputParam на каждый импульс.</summary>
    private readonly Dictionary<int, CachedRejectEncoding> _rejectEncodingCache = new();
    /// <summary>Sticky vision_ready: после DO5/DO3 импульсов SDK сбивает hold — восстанавливаем.</summary>
    private int _stickyReadyPort;
    private bool _stickyReadyElectricalHigh;
    private bool _stickyReadyActive;
    /// <summary>
    /// Disabled ready/fault: держим inactive с Enable Start.
    /// StopDo без hold → float → ПЛК снова видит HIGH (DO1→X4 «летает»).
    /// </summary>
    private readonly List<(int Port, bool ActiveHigh)> _forcedInactiveHolds = new();

    private readonly record struct CachedRejectEncoding(uint Pattern, uint Width, uint Period, uint Duration);

    public string ComPort { get; }
    public string OpenedComName { get; private set; } = "";

    /// <summary>DO съёмки (Line0), из capture.output_port. Default 6.</summary>
    public int Line0OutputPort { get; set; } = 5;

    /// <summary>Все DO съёмки, из capture.output_ports. Default [6].</summary>
    public int[] Line0OutputPorts { get; set; } = [6];

    public IoBoxSession(string comPort) =>
        ComPort = NormalizeComPort(comPort);

    public void Open()
    {
        ObjectDisposedException.ThrowIf(_disposed, this);
        if (_handle != IntPtr.Zero)
            return;

        IntPtr handle = IntPtr.Zero;
        int ret = MvIoNative.CreateHandle(ref handle);
        if (ret != MvIoNative.MvOk || handle == IntPtr.Zero)
            throw new InvalidOperationException($"MV_IO_CreateHandle failed: 0x{ret:x8}");

        string? opened = null;
        var tried = new List<string>();
        // SDK иногда принимает "COM3", иногда "Com3" — пробуем оба варианта.
        foreach (string candidate in BuildComCandidates(ComPort))
        {
            var serial = new MvIoNative.MvIoSerial
            {
                ComName = candidate,
                Reserved = new uint[8]
            };
            ret = MvIoNative.Open(handle, ref serial);
            tried.Add($"{candidate}=0x{ret:x8}");
            if (ret == MvIoNative.MvOk)
            {
                opened = candidate;
                break;
            }
        }

        if (opened == null)
        {
            MvIoNative.DestroyHandle(handle);
            throw new InvalidOperationException(
                $"MV_IO_Open failed for {ComPort}. Tried: {string.Join(", ", tried)}. " +
                DescribeOpenError(ret) +
                " Закройте другой IoInputMonitor (Ctrl+C), LightServer или MVS Client.");
        }

        _handle = handle;
        OpenedComName = opened;
    }

    public bool TryReadFirmwareVersion(out MvIoNative.MvIoVersion version)
    {
        EnsureOpen();
        version = new MvIoNative.MvIoVersion { Reserved = new uint[8] };
        return MvIoNative.GetFirmwareVersion(_handle, ref version) == MvIoNative.MvOk;
    }

    public void ConfigureInputEdge(int portNumber, uint edge, uint debounceMs, uint delayMs = 0)
    {
        EnsureOpen();
        if (portNumber is < 1 or > 8)
            throw new ArgumentOutOfRangeException(nameof(portNumber), "DI должен быть 1..8.");

        var input = new MvIoNative.MvIoSetInput
        {
            Port = MvIoNative.PortMaskForUint(portNumber),
            Enable = 1,
            Edge = edge,
            DelayTime = delayMs,
            Glitch = debounceMs,
            Reserved = new uint[8]
        };

        int ret = MvIoNative.SetInput(_handle, ref input);
        if (ret != MvIoNative.MvOk)
        {
            throw new InvalidOperationException(
                $"MV_IO_SetInput failed for DI{portNumber}: 0x{ret:x8}");
        }

        _configuredInputs[portNumber] = (edge, debounceMs, delayMs);
    }

    public bool TryConfigureInputEdge(int portNumber, uint edge, uint debounceMs, uint delayMs = 0)
    {
        try
        {
            ConfigureInputEdge(portNumber, edge, debounceMs, delayMs);
            return true;
        }
        catch
        {
            return false;
        }
    }

    public bool TryReadPortInputParam(int portNumber, out MvIoNative.MvIoSetInput input)
    {
        EnsureOpen();
        input = new MvIoNative.MvIoSetInput
        {
            Port = MvIoNative.PortMaskForUint(portNumber),
            Reserved = new uint[8]
        };

        return MvIoNative.GetPortInputParam(_handle, ref input) == MvIoNative.MvOk;
    }

    public bool TryGetOutPortTriggerSource(int outPort, out uint inPort, out uint reportedOut)
    {
        EnsureOpen();
        inPort = 0;
        reportedOut = 0;
        foreach (uint outEnc in new uint[] { (uint)outPort, MvIoNative.OutputPortIndex(outPort) }.Distinct())
        {
            var assoc = new MvIoNative.MvIoPortAssociation
            {
                InPortNum = 0,
                OutPortNum = outEnc,
                Reserved = new uint[8]
            };
            if (MvIoNative.GetOutPortTriggerSource(_handle, ref assoc) == MvIoNative.MvOk)
            {
                inPort = assoc.InPortNum;
                reportedOut = assoc.OutPortNum;
                return true;
            }
        }

        return false;
    }

    public bool TryGetPortOutputParam(int outPort, out MvIoNative.MvIoSetOutput output)
    {
        EnsureOpen();
        foreach (uint portEnc in new uint[]
                 {
                     (uint)outPort,
                     MvIoNative.PortMaskForUint(outPort),
                     MvIoNative.OutputPortIndex(outPort)
                 }.Distinct())
        {
            output = new MvIoNative.MvIoSetOutput
            {
                Port = portEnc,
                Reserved = new uint[8]
            };
            if (MvIoNative.GetPortOutputParam(_handle, ref output) == MvIoNative.MvOk)
                return true;
        }

        output = default;
        return false;
    }

    public static string DescribeEdge(uint edge) =>
        edge switch
        {
            (uint)MvIoNative.IoEdgeType.Rising => "rising",
            (uint)MvIoNative.IoEdgeType.Falling => "falling",
            _ => $"unknown({edge})"
        };

    public void ResetParam()
    {
        EnsureOpen();
        int ret = MvIoNative.ResetParam(_handle);
        if (ret != MvIoNative.MvOk)
            throw new InvalidOperationException($"MV_IO_ResetParam failed: 0x{ret:x8}");
    }

    public byte ReadInputLevel(int portNumber)
    {
        EnsureOpen();
        var levels = new MvIoNative.MvIoInputLevel
        {
            PortMask = MvIoNative.PortMaskFor(portNumber),
            Reserved = new uint[8]
        };

        int ret = MvIoNative.GetInputLevel(_handle, ref levels);
        if (ret != MvIoNative.MvOk)
        {
            throw new InvalidOperationException(
                $"MV_IO_GetInputLevel failed: 0x{ret:x8}. " +
                "Если на порту включён edge detection, отключите его в MVS IO Controller.");
        }

        return MvIoNative.ReadLevel(levels, portNumber);
    }

    public bool TryReadInputLevel(int portNumber, out byte level)
    {
        level = 0;
        try
        {
            level = ReadInputLevel(portNumber);
            return true;
        }
        catch
        {
            return false;
        }
    }

    public MvIoNative.MvIoInputLevel ReadAllInputLevels(byte portMask = 0xFF)
    {
        EnsureOpen();
        var levels = new MvIoNative.MvIoInputLevel
        {
            PortMask = portMask,
            Reserved = new uint[8]
        };

        int ret = MvIoNative.GetInputLevel(_handle, ref levels);
        if (ret != MvIoNative.MvOk)
            throw new InvalidOperationException($"MV_IO_GetInputLevel failed: 0x{ret:x8}");

        return levels;
    }

    public void RegisterEdgeCallback(Action<int, MvIoNative.IoEdgeType> onEdge)
    {
        EnsureOpen();
        // Делегат должен жить, пока открыт handle — храним в поле _edgeCallback.
        _edgeCallback = (IntPtr _, ref MvIoNative.MvIoInputEdge edge, IntPtr __) =>
        {
            // edge.PortNumber — маска (0x04 = DI3), конвертируем в 1..8.
            int port = MvIoNative.PortFromMask(edge.PortNumber);
            onEdge(port, edge.EdgeType);
        };

        int ret = MvIoNative.RegisterEdgeDetectionCallback(_handle, _edgeCallback, IntPtr.Zero);
        if (ret != MvIoNative.MvOk)
            throw new InvalidOperationException($"MV_IO_RegisterEdgeDetectionCallBack failed: 0x{ret:x8}");
    }

    /// <summary>
    /// Импульс съёмки по DI3↑: просто ACTIVE на output_ports, длительность = pulse_duration_ms
    /// (держит Program снаружи → ReleaseLine0ForPlc). Без связки с профилем DO5/board-exact.
    /// </summary>
    public string FireCapturePulse(IoCaptureOptions capture)
    {
        EnsureOpen();
        if (capture.OutputMode == IoCaptureOutputMode.Timer)
            return FireTimerOnly(capture);

        int[] ports = capture.ResolveOutputPorts();
        int duration = Math.Clamp(capture.PulseDurationMs, 1, 65535);
        var parts = new List<string>(ports.Length);

        foreach (int port in ports)
        {
            BeginSimpleCaptureLevelPulse(port, capture.ActiveHigh);
            parts.Add($"DO{port} ACTIVE hold {duration}ms");
        }

        return string.Join("; ", parts);
    }

    /// <summary>Поднять DO в ACTIVE (гашение — EndSimpleCaptureLevelPulse после settle).</summary>
    public void BeginSimpleCaptureLevelPulse(int outputPort, bool activeHigh = true)
    {
        EnsureOpen();
        if (outputPort is < 1 or > 8)
            throw new ArgumentOutOfRangeException(nameof(outputPort), "DO port must be 1..8.");

        bool active = activeHigh;
        bool idle = !activeHigh;

        TrySetOutTriggerSource(inPort: 0, outPort: outputPort);
        TryPnpEnable(outputPort, enabled: true);
        StopDoOutput(outputPort);
        _ = TrySetMainOutputLevel(outputPort, idle);

        if (!TryOutputEnableAny(outputPort, MvIoNative.IoOutputEnableType.Start, out string enableErrors))
            throw new InvalidOperationException($"DO{outputPort} enable failed: {enableErrors}");

        if (!TrySetMainOutputLevel(outputPort, active))
            throw new InvalidOperationException($"DO{outputPort} ACTIVE level failed");
    }

    /// <summary>
    /// Гасить capture DO: StopDo + idle level. Без hold/Enable.
    /// </summary>
    public string EndSimpleCaptureLevelPulse(int outputPort, bool activeHigh = true)
    {
        EnsureOpen();
        if (outputPort is < 1 or > 8)
            throw new ArgumentOutOfRangeException(nameof(outputPort), "DO port must be 1..8.");

        bool idle = !activeHigh;
        StopDoOutput(outputPort);
        _ = TrySetMainOutputLevel(outputPort, idle);
        StopDoOutput(outputPort);
        return $"DO{outputPort} idle";
    }

    private string FireTimerOnly(IoCaptureOptions capture)
    {
        FireTimerSoftwareTrigger(capture.TimerIndex);
        return $"timer{capture.TimerIndex} → Out{capture.OutputPort}";
    }

    private static string ShortErr(Exception ex)
    {
        string msg = ex.Message;
        int cut = msg.IndexOf(". ", StringComparison.Ordinal);
        return cut > 0 && cut < 100 ? msg[..cut] : (msg.Length > 100 ? msg[..100] : msg);
    }

    /// <summary>Software trigger Timer N — как Execute в MVS.</summary>
    public void FireTimerSoftwareTrigger(int timerIndex)
    {
        EnsureOpen();
        int ret = MvIoTimerTrigger.TriggerSoftware(_handle, timerIndex, out string detail);
        if (ret != MvIoNative.MvOk)
        {
            throw new InvalidOperationException(
                $"Timer{timerIndex} software trigger failed: 0x{ret:x8} via {detail}");
        }
    }

    /// <summary>
    /// DI3→DO: Soft-источник → параметры (как в MVS) → Enable Start.
    /// На этой прошивке часто срабатывает Enable по уже записанным PWM-параметрам платы.
    /// </summary>
    /// <param name="pulseMainLevel">
    /// false — только SetOutput+Enable (для DO5+DO6 сразу друг за другом без Sleep между портами).
    /// </param>
    public string FireDoSoftwarePulse(
        int outputPort,
        int durationMs,
        bool activeHigh = true,
        bool pulseMainLevel = true)
    {
        EnsureOpen();
        if (outputPort is < 1 or > 8)
            throw new ArgumentOutOfRangeException(nameof(outputPort), "DO port must be 1..8.");

        int pulseDuration = Math.Clamp(durationMs, 1, 65535);
        uint level = activeHigh ? 1u : 0u;
        uint duration = (uint)pulseDuration;

        // Soft / User: InPort=0 → программный импульс, не Out←In.
        // SaveParam в горячем пути ломает DI edge — параметры пишем без Save (или один раз при старте).
        TrySetOutTriggerSource(inPort: 0, outPort: outputPort);
        TryPnpEnable(outputPort, enabled: true);

        // Idle перед фронтом — иначе повторный импульс может не дать edge на Line0.
        TrySetMainOutputLevel(outputPort, !activeHigh);

        TryGetPortOutputParam(outputPort, out var board);
        uint boardPattern = board.Pattern;
        uint boardPeriod = board.PulsePeriod == 0 ? Math.Max(duration, 1u) : board.PulsePeriod;
        uint boardWidth = board.PulseWidth == 0 ? Math.Max(1u, boardPeriod / 2) : board.PulseWidth;
        if (boardWidth >= boardPeriod)
            boardWidth = Math.Max(1u, boardPeriod / 2);

        // board-exact с длинным/0 PulseDuration на DO6 оставлял линию «залипшей».
        // Для съёмки сначала профили с нашей длительностью.
        bool boardDurationSane = board.PulseDuration > 0
            && board.PulseDuration != 65535
            && board.PulseDuration <= duration * 2;

        var attempts = new List<(string Tag, uint Pattern, uint Width, uint Period, uint Duration)>
        {
            ("single", 0, duration, duration, duration),
            ("board+dur", boardPattern, boardWidth, boardPeriod, duration),
            ("single-p1", 0, duration, 1, duration),
            ("pwm", 1, Math.Max(1u, duration / 2), duration, duration),
        };
        if (boardDurationSane)
        {
            attempts.Insert(2, ("board-exact", boardPattern,
                board.PulseWidth == 0 ? boardWidth : board.PulseWidth, boardPeriod, board.PulseDuration));
        }

        var errors = new List<string>();
        foreach (var a in attempts)
        {
            uint period = a.Period == 0 ? 1u : a.Period;
            foreach (uint port in OutputPortEncodings(outputPort).Distinct())
            {
                var output = new MvIoNative.MvIoSetOutput
                {
                    Port = port,
                    Pattern = a.Pattern,
                    PulseWidth = a.Width,
                    PulsePeriod = period,
                    PulseDuration = a.Duration,
                    Level = level,
                    Reserved = new uint[8]
                };

                int setRet;
                try
                {
                    setRet = MvIoNative.SetOutput(_handle, ref output);
                }
                catch (Exception ex)
                {
                    errors.Add($"{a.Tag}/port={port}:ex={ex.GetType().Name}");
                    continue;
                }

                if (setRet != MvIoNative.MvOk)
                {
                    errors.Add($"{a.Tag}/port={port}:0x{setRet:x8}");
                    continue;
                }

                int enRet = TryOutputEnable(port, MvIoNative.IoOutputEnableType.Start);
                if (enRet == MvIoNative.MvOk)
                {
                    // Доп. фронт уровнем — камеры на RisingEdge часто ждут именно edge на Line0.
                    if (pulseMainLevel)
                        _ = TryPulseMainOutputLevel(outputPort, Math.Min(pulseDuration, 50), activeHigh);
                    return $"setoutput DO{outputPort} ({a.Tag}, port={port})";
                }

                errors.Add($"{a.Tag}/port={port}:set=ok enable=0x{enRet:x8}");
            }
        }

        // Как «Execute» в MVS: параметры уже на плате, только Start.
        _ = TryOutputEnableAny(outputPort, MvIoNative.IoOutputEnableType.End, out _);
        if (TryOutputEnableAny(outputPort, MvIoNative.IoOutputEnableType.Start, out string enableErrors))
        {
            if (pulseMainLevel)
                _ = TryPulseMainOutputLevel(outputPort, Math.Min(pulseDuration, 50), activeHigh);
            return $"enable DO{outputPort} (MVS-params pat={boardPattern} w={board.PulseWidth} p={boardPeriod})";
        }

        if (pulseMainLevel && TryPulseMainOutputLevel(outputPort, pulseDuration, activeHigh))
            return $"mainlevel DO{outputPort}";

        throw new InvalidOperationException(
            $"DO{outputPort} pulse failed enable={enableErrors}; " +
            string.Join("; ", errors.Take(6)));
    }

    /// <summary>
    /// Удержать уровень DO (vision_ready / vision_fault → X4/X5).
    /// Для active=true предпочитаем SetOutput+Enable hold (MainOutputLevel на DO1 часто мёртв).
    /// </summary>
    public string SetDoLevel(int outputPort, bool active, bool activeHigh = true)
    {
        EnsureOpen();
        if (outputPort is < 1 or > 8)
            throw new ArgumentOutOfRangeException(nameof(outputPort), "DO port must be 1..8.");

        bool electricalHigh = active == activeHigh;
        var errors = new List<string>();

        // Готовность/ошибка: активный hold без StopDo (иначе X4 скачет при каждом re-assert).
        if (TryDriveDoLevel(outputPort, electricalHigh, holdEnable: active, stopFirst: !active))
        {
            if (!active)
                StopDoOutput(outputPort);
            return $"drive-hold={(electricalHigh ? "HIGH" : "LOW")} active={active}";
        }

        // Общий SDK handle: leftover capture Enable часто блокирует MainOutputLevel на DO1–4.
        try { ReleaseLine0ForPlc(); } catch { /* best-effort */ }
        StopDoOutput(outputPort);
        TrySetOutTriggerSource(inPort: 0, outPort: outputPort);
        TryPnpEnable(outputPort, enabled: true);
        _ = TryOutputEnableAny(outputPort, MvIoNative.IoOutputEnableType.End, out _);
        if (!TryOutputEnableAny(outputPort, MvIoNative.IoOutputEnableType.Start, out string enableErrors))
            errors.Add($"enable:{enableErrors}");

        if (TrySetMainOutputLevel(outputPort, electricalHigh))
        {
            if (!active)
            {
                _ = TryOutputEnableAny(outputPort, MvIoNative.IoOutputEnableType.End, out _);
                _ = TrySetMainOutputLevel(outputPort, electricalHigh);
            }

            return $"level={(electricalHigh ? "HIGH" : "LOW")}";
        }

        errors.Add("mainlevel-fail");
        Thread.Sleep(20);
        _ = TryOutputEnableAny(outputPort, MvIoNative.IoOutputEnableType.End, out _);
        _ = TryOutputEnableAny(outputPort, MvIoNative.IoOutputEnableType.Start, out _);
        if (TrySetMainOutputLevel(outputPort, electricalHigh))
            return $"level-retry={(electricalHigh ? "HIGH" : "LOW")}";

        if (TryHoldDoViaSetOutput(outputPort, electricalHigh))
            return $"setoutput-hold={(electricalHigh ? "HIGH" : "LOW")}";

        // Тот же SDK-путь, что BeginRejectLevelPulse (рабочий на DO3/4), без End — уровень держим.
        try
        {
            BeginRejectLevelPulse(outputPort, activeHigh: electricalHigh);
            if (!active)
            {
                _ = TrySetMainOutputLevel(outputPort, !electricalHigh);
                StopDoOutput(outputPort);
            }

            return $"level-pulse-path={(electricalHigh ? "HIGH" : "LOW")} active={active}";
        }
        catch (Exception ex)
        {
            errors.Add($"level-pulse-path:{ex.Message}");
        }

        throw new InvalidOperationException(
            $"DO{outputPort} SetDoLevel failed (active={active} active_high={activeHigh}): {string.Join("; ", errors)}");
    }

    /// <summary>Публичный SetOutput-hold для smoke/ready, когда MainOutputLevel недоступен.</summary>
    public string ForceDoHoldViaSetOutput(int outputPort, bool electricalHigh)
    {
        EnsureOpen();
        try { ReleaseLine0ForPlc(); } catch { /* ignore */ }
        StopDoOutput(outputPort);
        TrySetOutTriggerSource(inPort: 0, outPort: outputPort);
        TryPnpEnable(outputPort, enabled: true);
        _ = TryOutputEnableAny(outputPort, MvIoNative.IoOutputEnableType.End, out _);
        _ = TryOutputEnableAny(outputPort, MvIoNative.IoOutputEnableType.Start, out _);
        if (!TryHoldDoViaSetOutput(outputPort, electricalHigh))
            throw new InvalidOperationException($"DO{outputPort} SetOutput hold failed");
        return $"setoutput-hold={(electricalHigh ? "HIGH" : "LOW")}";
    }

    /// <summary>Fallback удержания уровня через SetOutput по всем encoding порта.</summary>
    private bool TryHoldDoViaSetOutput(int outputPort, bool electricalHigh)
    {
        uint level = electricalHigh ? 1u : 0u;
        foreach (uint port in OutputPortEncodings(outputPort))
        {
            var output = new MvIoNative.MvIoSetOutput
            {
                Port = port,
                Pattern = 0,
                PulseWidth = 65535,
                PulsePeriod = 65535,
                PulseDuration = 65535,
                Level = level,
                Reserved = new uint[8]
            };

            int setRet;
            try
            {
                setRet = MvIoNative.SetOutput(_handle, ref output);
            }
            catch
            {
                continue;
            }

            if (setRet != MvIoNative.MvOk)
                continue;

            if (TryOutputEnable(port, MvIoNative.IoOutputEnableType.Start) == MvIoNative.MvOk)
                return true;
        }

        return false;
    }

    /// <summary>
    /// Импульс брака только на указанный DO (линия1→DO3 / линия2→DO4).
    /// Не трогаем capture DO6 и соседнюю reject-линию — только этот порт.
    /// Full ReviveDi — только после SaveParam или полного провала (без StopDo чужих DO).
    /// </summary>
    public string FireRejectPulse(int outputPort, int durationMs, bool activeHigh = true)
    {
        EnsureOpen();
        if (outputPort is < 1 or > 8)
            throw new ArgumentOutOfRangeException(nameof(outputPort), "DO port must be 1..8.");

        int pulseDuration = Math.Clamp(durationMs, 1, 5000);
        bool needFullRevive = false;
        Exception? last = null;

        try
        {
            // Только целевой reject-DO. Capture DO6 / соседнюю линию не гасим.
            StopDoOutput(outputPort);

            for (int attempt = 1; attempt <= 3; attempt++)
            {
                try
                {
                    return FireRejectFast(outputPort, pulseDuration, activeHigh, saveParam: false);
                }
                catch (Exception ex)
                {
                    last = ex;
                    bool busy = ex.Message.Contains("80000004", StringComparison.OrdinalIgnoreCase)
                        || ex.Message.Contains("80000204", StringComparison.OrdinalIgnoreCase);
                    SoftCleanupAfterReject(outputPort, activeHigh);
                    if (busy && attempt < 3)
                    {
                        Thread.Sleep(40 * attempt);
                        continue;
                    }

                    // Последний шанс — SaveParam; DI после этого нужно поднять.
                    needFullRevive = true;
                    _rejectEncodingCache.Remove(outputPort);
                    try
                    {
                        return FireRejectFast(outputPort, pulseDuration, activeHigh, saveParam: true);
                    }
                    catch (Exception saveEx)
                    {
                        last = saveEx;
                        break;
                    }
                }
            }

            needFullRevive = true;
            throw new InvalidOperationException(
                $"DO{outputPort} reject pulse failed: {last?.Message ?? "unknown"}");
        }
        finally
        {
            SoftCleanupAfterReject(outputPort, activeHigh);
            if (needFullRevive)
                ReviveDiAfterReject();
        }
    }

    /// <summary>Гасим reject-DO: Enable End + idle. Без hold ready/fault.</summary>
    public void SoftCleanupAfterReject(int outputPort, bool activeHigh = true)
    {
        try
        {
            StopDoOutput(outputPort);
            _ = TrySetMainOutputLevel(outputPort, !activeHigh);
            StopDoOutput(outputPort);
        }
        catch
        {
            // best-effort
        }
    }

    public void ClearStickyReady()
    {
        _stickyReadyActive = false;
        _stickyReadyPort = 0;
    }

    public void MarkStickyReady(int outputPort, bool electricalHigh, bool active = true)
    {
        if (outputPort is < 1 or > 8)
            return;
        if (active)
        {
            _stickyReadyPort = outputPort;
            _stickyReadyElectricalHigh = electricalHigh;
            _stickyReadyActive = true;
        }
        else if (_stickyReadyPort == outputPort)
        {
            _stickyReadyActive = false;
        }
    }

    private void RememberStickyReady(int outputPort, bool electricalHigh, bool active) =>
        MarkStickyReady(outputPort, electricalHigh, active);

    /// <summary>
    /// Восстановить vision_ready после DO5 capture / DO3 reject — без StopDo (без лишнего фронта вниз).
    /// </summary>
    public string ReassertStickyReady()
    {
        if (!_stickyReadyActive || _stickyReadyPort is < 1 or > 8)
            return "";

        try
        {
            if (TryDriveDoLevel(
                    _stickyReadyPort,
                    _stickyReadyElectricalHigh,
                    holdEnable: true,
                    stopFirst: false))
            {
                return $"sticky-ready DO{_stickyReadyPort} reheld";
            }

            if (TryHoldDoViaSetOutput(_stickyReadyPort, _stickyReadyElectricalHigh))
                return $"sticky-ready DO{_stickyReadyPort} setoutput-hold";
        }
        catch
        {
            // best-effort
        }

        return "sticky-ready-fail";
    }

    public void ClearForcedInactiveHolds() => _forcedInactiveHolds.Clear();

    public void RegisterForcedInactiveHold(int outputPort, bool activeHigh)
    {
        if (outputPort is < 1 or > 8)
            return;
        _forcedInactiveHolds.RemoveAll(x => x.Port == outputPort);
        _forcedInactiveHolds.Add((outputPort, activeHigh));
    }

    /// <summary>
    /// Удержать DO на inactive (Enable Start). Без StopDo — иначе float → HIGH на ПЛК.
    /// </summary>
    public string ForceHoldInactive(int outputPort, bool activeHigh)
    {
        EnsureOpen();
        if (outputPort is < 1 or > 8)
            throw new ArgumentOutOfRangeException(nameof(outputPort), "DO port must be 1..8.");

        bool electricalHigh = !activeHigh;
        if (TryDriveDoLevel(outputPort, electricalHigh, holdEnable: true, stopFirst: true))
            return $"inactive-hold DO{outputPort} level={(electricalHigh ? "HIGH" : "LOW")}";

        if (TryHoldDoViaSetOutput(outputPort, electricalHigh))
            return $"inactive-setoutput-hold DO{outputPort} level={(electricalHigh ? "HIGH" : "LOW")}";

        throw new InvalidOperationException(
            $"DO{outputPort} ForceHoldInactive failed (active_high={activeHigh})");
    }

    /// <summary>После DO5/DO3/DO4 — снова закрепить disabled ready/fault в inactive.</summary>
    public string ReassertForcedInactiveHolds()
    {
        if (_forcedInactiveHolds.Count == 0)
            return "";

        var parts = new List<string>(_forcedInactiveHolds.Count);
        foreach (var (port, activeHigh) in _forcedInactiveHolds.ToArray())
        {
            bool electricalHigh = !activeHigh;
            try
            {
                if (TryDriveDoLevel(port, electricalHigh, holdEnable: true, stopFirst: false))
                    parts.Add($"DO{port}=inactive");
                else if (TryHoldDoViaSetOutput(port, electricalHigh))
                    parts.Add($"DO{port}=inactive-setoutput");
                else
                    parts.Add($"DO{port}=fail");
            }
            catch
            {
                parts.Add($"DO{port}=fail");
            }
        }

        return string.Join(",", parts);
    }

    /// <summary>Принудительно погасить DO1..maxPort активным inactive-уровнем (Enable Start удерживаем).</summary>
    public IReadOnlyList<string> ForceAllDoOff(int maxPort = 7, bool activeHigh = true)
    {
        EnsureOpen();
        var report = new List<string>();
        for (int p = 1; p <= maxPort; p++)
        {
            bool off = TryDriveDoLevel(p, electricalHigh: !activeHigh, holdEnable: true);
            if (off)
            {
                report.Add($"DO{p} OFF held level={(activeHigh ? 0 : 1)}");
                continue;
            }

            bool alt = TryDriveDoLevel(p, electricalHigh: activeHigh, holdEnable: true);
            report.Add(alt
                ? $"DO{p} OFF via inverted level={(activeHigh ? 1 : 0)}"
                : $"DO{p} OFF FAILED");
        }

        return report;
    }

    /// <summary>
    /// Board-exact SetOutput уровня + Enable Start (тот же путь, что FireRejectFast).
    /// holdEnable=true — не делаем End (иначе float → ПЛК снова видит HIGH).
    /// </summary>
    public bool TryDriveDoLevel(int outputPort, bool electricalHigh, bool holdEnable)
        => TryDriveDoLevel(outputPort, electricalHigh, holdEnable, stopFirst: true);

    /// <param name="stopFirst">false — не гасим порт перед SetOutput (для re-hold ready без скачка).</param>
    public bool TryDriveDoLevel(int outputPort, bool electricalHigh, bool holdEnable, bool stopFirst)
    {
        EnsureOpen();
        // Не гасим DO5 при re-hold ready — иначе снова сбиваем Line0 mid-capture.
        if (stopFirst)
        {
            try { ReleaseLine0ForPlc(); } catch { /* ignore */ }
            StopDoOutput(outputPort);
        }

        TrySetOutTriggerSource(inPort: 0, outPort: outputPort);
        TryPnpEnable(outputPort, enabled: true);

        uint level = electricalHigh ? 1u : 0u;
        const uint duration = 100u;
        uint port = (uint)outputPort;
        CachedRejectEncoding enc = ResolveRejectEncoding(outputPort, duration);
        uint period = enc.Period == 0 ? Math.Max(duration, 1u) : enc.Period;
        uint width = enc.Width == 0 ? Math.Max(1u, period / 2) : enc.Width;
        if (width >= period)
            width = Math.Max(1u, period / 2);

        // Как FireRejectFast: сначала board port number, потом остальные encoding.
        foreach (uint tryPort in new[] { port }.Concat(OutputPortEncodings(outputPort)).Distinct())
        {
            foreach (uint pulseDur in new uint[] { duration, 65535u, period })
            {
                var output = new MvIoNative.MvIoSetOutput
                {
                    Port = tryPort,
                    Pattern = enc.Pattern,
                    PulseWidth = width,
                    PulsePeriod = period,
                    PulseDuration = pulseDur,
                    Level = level,
                    Reserved = new uint[8]
                };

                int setRet;
                try
                {
                    setRet = MvIoNative.SetOutput(_handle, ref output);
                }
                catch
                {
                    continue;
                }

                if (setRet != MvIoNative.MvOk)
                    continue;

                int enRet = TryOutputEnable(tryPort, MvIoNative.IoOutputEnableType.Start);
                if (enRet != MvIoNative.MvOk)
                    continue;

                if (!holdEnable)
                    StopDoOutput(outputPort);

                return true;
            }
        }

        // Fallback: старый hold-путь.
        return TryHoldDoViaSetOutput(outputPort, electricalHigh);
    }

    /// <summary>Диагностика: код возврата SetOutput для Level 0/1 на DO.</summary>
    public string ProbeDoSetOutput(int outputPort)
    {
        EnsureOpen();
        StopDoOutput(outputPort);
        TrySetOutTriggerSource(inPort: 0, outPort: outputPort);
        TryPnpEnable(outputPort, enabled: true);
        uint port = (uint)outputPort;
        var parts = new List<string>();
        foreach (uint level in new uint[] { 0u, 1u })
        {
            var output = new MvIoNative.MvIoSetOutput
            {
                Port = port,
                Pattern = 0,
                PulseWidth = 50,
                PulsePeriod = 100,
                PulseDuration = 100,
                Level = level,
                Reserved = new uint[8]
            };
            int setRet = MvIoNative.SetOutput(_handle, ref output);
            int enRet = setRet == MvIoNative.MvOk
                ? TryOutputEnable(port, MvIoNative.IoOutputEnableType.Start)
                : -1;
            StopDoOutput(outputPort);
            parts.Add($"L{level}:set=0x{setRet:x8},en=0x{enRet:x8}");
        }

        return $"DO{outputPort} {string.Join(" | ", parts)}";
    }

    /// <summary>
    /// Один SetOutput (board-exact из кэша) + Enable + settle pulse_ms. Без PulseLevel.
    /// </summary>
    private string FireRejectFast(int outputPort, int durationMs, bool activeHigh, bool saveParam)
    {
        int pulseDuration = Math.Clamp(durationMs, 1, 65535);
        uint level = activeHigh ? 1u : 0u;
        uint duration = (uint)pulseDuration;
        uint port = (uint)outputPort;

        TrySetOutTriggerSource(inPort: 0, outPort: outputPort);
        TryPnpEnable(outputPort, enabled: true);
        if (saveParam)
            MvIoNative.SaveParam(_handle);

        TrySetMainOutputLevel(outputPort, !activeHigh);

        CachedRejectEncoding primary = ResolveRejectEncoding(outputPort, duration);
        var attempts = new List<(string Tag, CachedRejectEncoding Enc)>
        {
            ("board-exact", primary),
            ("single", new CachedRejectEncoding(0, duration, duration, duration)),
        };

        var errors = new List<string>();
        foreach (var a in attempts)
        {
            uint period = a.Enc.Period == 0 ? 1u : a.Enc.Period;
            var output = new MvIoNative.MvIoSetOutput
            {
                Port = port,
                Pattern = a.Enc.Pattern,
                PulseWidth = a.Enc.Width,
                PulsePeriod = period,
                PulseDuration = a.Enc.Duration == 0 ? duration : a.Enc.Duration,
                Level = level,
                Reserved = new uint[8]
            };

            int setRet;
            try
            {
                setRet = MvIoNative.SetOutput(_handle, ref output);
            }
            catch (Exception ex)
            {
                errors.Add($"{a.Tag}/port={port}:ex={ex.GetType().Name}");
                continue;
            }

            if (setRet != MvIoNative.MvOk)
            {
                errors.Add($"{a.Tag}/port={port}:0x{setRet:x8}");
                if ((uint)setRet is 0x80000004u or 0x80000204u)
                    throw new InvalidOperationException(
                        $"DO{outputPort} SetOutput busy 0x{setRet:x8} — wait Capture quiet");
                if (a.Tag == "board-exact")
                    _rejectEncodingCache.Remove(outputPort);
                continue;
            }

            int enRet = TryOutputEnable(port, MvIoNative.IoOutputEnableType.Start);
            if (enRet != MvIoNative.MvOk)
            {
                errors.Add($"{a.Tag}/port={port}:enable=0x{enRet:x8}");
                continue;
            }

            // Ждём аппаратный импульс (конфиг pulse_duration_ms), без лишнего PulseLevel.
            Thread.Sleep(pulseDuration);
            StopDoOutput(outputPort);

            if (a.Tag == "board-exact")
                _rejectEncodingCache[outputPort] = a.Enc;

            string saveTag = saveParam ? "+save" : "";
            return $"setoutput DO{outputPort} ({a.Tag}{saveTag}, port={port})";
        }

        throw new InvalidOperationException(string.Join("; ", errors.Take(4)));
    }

    private CachedRejectEncoding ResolveRejectEncoding(int outputPort, uint duration)
    {
        if (_rejectEncodingCache.TryGetValue(outputPort, out CachedRejectEncoding cached))
            return cached;

        if (!TryGetPortOutputParam(outputPort, out var board))
            return new CachedRejectEncoding(0, duration, duration, duration);

        uint boardPeriod = board.PulsePeriod == 0 ? Math.Max(duration, 1u) : board.PulsePeriod;
        uint boardWidth = board.PulseWidth == 0 ? Math.Max(1u, boardPeriod / 2) : board.PulseWidth;
        if (boardWidth >= boardPeriod)
            boardWidth = Math.Max(1u, boardPeriod / 2);
        uint boardDuration = board.PulseDuration == 0 ? duration : board.PulseDuration;
        uint width = board.PulseWidth == 0 ? boardWidth : board.PulseWidth;

        var enc = new CachedRejectEncoding(board.Pattern, width, boardPeriod, boardDuration);
        _rejectEncodingCache[outputPort] = enc;
        return enc;
    }

    /// <summary>
    /// Вызывается после ReviveDiAfterReject: ключ=DI port, value=сейчас HIGH.
    /// Нужен, чтобы Program синхронизировал portPressed — иначе both глотает DI3↑.
    /// </summary>
    public Action<IReadOnlyDictionary<int, bool>>? AfterDiRevive { get; set; }

    /// <summary>
    /// Вернуть DI после reject: только SetInput по уровню.
    /// Не трогаем DO (ни capture DO6, ни DO3/DO4) и не RegisterEdgeCallback (повтор → 0x80000003).
    /// </summary>
    public void ReviveDiAfterReject()
    {
        EnsureOpen();

        var levels = new Dictionary<int, bool>();
        foreach (var item in _configuredInputs.ToArray())
        {
            int di = item.Key;
            uint debounce = item.Value.DebounceMs;
            uint delay = item.Value.DelayMs;
            bool high = false;
            try
            {
                high = ReadInputLevel(di) == (byte)MvIoNative.IoLevel.High;
            }
            catch
            {
                // keep false
            }

            levels[di] = high;
            // both: следующий ожидаемый фронт
            uint edge = high
                ? (uint)MvIoNative.IoEdgeType.Falling
                : (uint)MvIoNative.IoEdgeType.Rising;

            if (!TryConfigureInputEdge(di, edge, debounce, delay))
            {
                Console.Error.WriteLine(
                    $"[{DateTime.Now:HH:mm:ss.fff}] DI{di}: revive SetInput failed (edge={(high ? "falling" : "rising")})");
            }
        }

        try
        {
            AfterDiRevive?.Invoke(levels);
        }
        catch (Exception ex)
        {
            Console.Error.WriteLine(
                $"[{DateTime.Now:HH:mm:ss.fff}] DI revive sync failed: {ex.Message}");
        }
    }

    /// <summary>
    /// SetOutput для reject: duration из аргумента, без SaveParam.
    /// </summary>
    private string FireDoRejectSetOutputPulse(int outputPort, int durationMs, bool activeHigh)
    {
        string how = BeginRejectSetOutputPulse(outputPort, durationMs, activeHigh);
        Thread.Sleep(Math.Clamp(durationMs, 1, 5000) + 10);
        return EndRejectSetOutputPulse(outputPort, durationMs, how, activeHigh);
    }

    /// <summary>
    /// Старт SetOutput-импульса reject (без Sleep). Паузу держать вне arbiter.
    /// При 0x80000004 сразу abort — не долбить все encoding'и (убивает DI monitor).
    /// </summary>
    public string BeginRejectSetOutputPulse(int outputPort, int durationMs, bool activeHigh = true)
    {
        EnsureOpen();
        if (outputPort is < 1 or > 8)
            throw new ArgumentOutOfRangeException(nameof(outputPort), "DO port must be 1..8.");

        int pulseDuration = Math.Clamp(durationMs, 1, 5000);
        uint level = activeHigh ? 1u : 0u;
        uint duration = (uint)pulseDuration;

        // Только целевой reject-DO (не гасим capture DO6 / соседнюю линию).
        StopDoOutput(outputPort);

        TrySetOutTriggerSource(inPort: 0, outPort: outputPort);
        TryPnpEnable(outputPort, enabled: true);
        _ = TryOutputEnableAny(outputPort, MvIoNative.IoOutputEnableType.End, out _);
        _ = TrySetMainOutputLevel(outputPort, !activeHigh);

        TryGetPortOutputParam(outputPort, out var board);
        uint boardPattern = board.Pattern;
        uint boardPeriod = board.PulsePeriod == 0 ? Math.Max(duration, 1u) : board.PulsePeriod;
        uint boardWidth = board.PulseWidth == 0 ? Math.Max(1u, boardPeriod / 2) : board.PulseWidth;
        if (boardWidth >= boardPeriod)
            boardWidth = Math.Max(1u, boardPeriod / 2);

        // Минимум попыток: один encoding порта, два профиля. Busy → наружу, arbiter подождёт Capture.
        var attempts = new List<(string Tag, uint Pattern, uint Width, uint Period, uint Duration)>
        {
            ("single", 0, duration, duration, duration),
            ("board+dur", boardPattern, boardWidth, boardPeriod, duration),
        };

        uint port = (uint)outputPort;
        var errors = new List<string>();
        foreach (var a in attempts)
        {
            uint period = a.Period == 0 ? 1u : a.Period;
            var output = new MvIoNative.MvIoSetOutput
            {
                Port = port,
                Pattern = a.Pattern,
                PulseWidth = a.Width,
                PulsePeriod = period,
                PulseDuration = a.Duration,
                Level = level,
                Reserved = new uint[8]
            };

            int setRet;
            try
            {
                setRet = MvIoNative.SetOutput(_handle, ref output);
            }
            catch (Exception ex)
            {
                errors.Add($"{a.Tag}/port={port}:ex={ex.GetType().Name}");
                continue;
            }

            if (setRet != MvIoNative.MvOk)
            {
                string code = $"0x{setRet:x8}";
                errors.Add($"{a.Tag}/port={port}:{code}");
                if ((uint)setRet is 0x80000004u or 0x80000204u)
                {
                    throw new InvalidOperationException(
                        $"DO{outputPort} SetOutput busy {code} — wait Capture quiet");
                }

                continue;
            }

            int enRet = TryOutputEnable(port, MvIoNative.IoOutputEnableType.Start);
            if (enRet != MvIoNative.MvOk)
            {
                errors.Add($"{a.Tag}/port={port}:enable=0x{enRet:x8}");
                continue;
            }

            return $"setoutput DO{outputPort} ({a.Tag}, {pulseDuration}ms, port={port})";
        }

        throw new InvalidOperationException(string.Join("; ", errors.Take(4)));
    }

    /// <summary>Завершить SetOutput-импульс reject и восстановить DI.</summary>
    public string EndRejectSetOutputPulse(int outputPort, int durationMs, string how, bool activeHigh = true)
    {
        EnsureOpen();
        try
        {
            _ = TryOutputEnableAny(outputPort, MvIoNative.IoOutputEnableType.End, out _);
            _ = TrySetMainOutputLevel(outputPort, !activeHigh);
            return how;
        }
        finally
        {
            StopDoOutput(outputPort);
            RestoreConfiguredInputs();
        }
    }

    /// <summary>
    /// Старт reject-импульса уровнем (короткий SDK-вызов). Паузу держать ВНЕ executor.
    /// </summary>
    public void BeginRejectLevelPulse(int outputPort, bool activeHigh = true)
    {
        EnsureOpen();
        if (outputPort is < 1 or > 8)
            throw new ArgumentOutOfRangeException(nameof(outputPort), "DO port must be 1..8.");

        // Только целевой reject-DO (не гасим capture DO6 / соседнюю линию).
        StopDoOutput(outputPort);

        bool activeLevel = activeHigh;
        bool idleLevel = !activeHigh;

        TrySetOutTriggerSource(inPort: 0, outPort: outputPort);
        TryPnpEnable(outputPort, enabled: true);
        _ = TryOutputEnableAny(outputPort, MvIoNative.IoOutputEnableType.End, out _);
        if (!TryOutputEnableAny(outputPort, MvIoNative.IoOutputEnableType.Start, out string enableErrors))
            throw new InvalidOperationException($"DO{outputPort} enable failed: {enableErrors}");

        // Idle на DO3/DO4 часто не принимает SetMainOutputLevel — не валим из‑за этого.
        _ = TrySetMainOutputLevel(outputPort, idleLevel);
        if (!TrySetMainOutputLevel(outputPort, activeLevel))
            throw new InvalidOperationException($"DO{outputPort} active level failed");
    }

    /// <summary>Снять reject-импульс в idle и восстановить DI edge-конфиг.</summary>
    public string EndRejectLevelPulse(int outputPort, int durationMs, bool activeHigh = true)
    {
        EnsureOpen();
        bool idleLevel = !activeHigh;
        try
        {
            _ = TrySetMainOutputLevel(outputPort, idleLevel);
            _ = TryOutputEnableAny(outputPort, MvIoNative.IoOutputEnableType.End, out _);
            _ = TrySetMainOutputLevel(outputPort, idleLevel);
            return $"level-pulse DO{outputPort} {Math.Clamp(durationMs, 1, 5000)}ms → idle";
        }
        finally
        {
            StopDoOutput(outputPort);
            RestoreConfiguredInputs();
        }
    }

    /// <summary>
    /// Короткий импульс уровнем для ПЛК (reject X6/X7): без board PWM/SetOutput.
    /// Гарантированно гасит DO в idle после импульса (иначе ПЛК залипает в отбраковке).
    /// </summary>
    public string FireDoLevelPulse(int outputPort, int durationMs, bool activeHigh = true)
    {
        int pulseDuration = Math.Clamp(durationMs, 1, 5000);
        BeginRejectLevelPulse(outputPort, activeHigh);
        try
        {
            Thread.Sleep(pulseDuration);
            return EndRejectLevelPulse(outputPort, pulseDuration, activeHigh);
        }
        catch
        {
            try { EndRejectLevelPulse(outputPort, pulseDuration, activeHigh); } catch { /* best-effort */ }
            throw;
        }
    }

    /// <summary>Прямой импульс (совместимость с тестами/CLI).</summary>
    public void FireOutputPulse(int outputPort, int durationMs, bool activeHigh = true) =>
        _ = FireDoSoftwarePulse(outputPort, durationMs, activeHigh);

    /// <summary>Fallback: тумблер уровня через MV_IO_SetMainOutputLevel (не PWM).</summary>
    public bool TryPulseMainOutputLevel(int outputPort, int durationMs, bool activeHigh = true)
    {
        EnsureOpen();
        TrySetMainOutputLevel(outputPort, !activeHigh);
        Thread.Sleep(2);
        if (!TrySetMainOutputLevel(outputPort, activeHigh))
            return false;

        Thread.Sleep(Math.Clamp(durationMs, 1, 5000));
        TrySetMainOutputLevel(outputPort, !activeHigh);
        return true;
    }

    private bool TrySetMainOutputLevel(int outputPort, bool high)
    {
        uint status = high ? 1u : 0u;
        uint[] ports = [(uint)outputPort, MvIoNative.OutputPortIndex(outputPort), MvIoNative.PortMaskForUint(outputPort)];
        foreach (uint p in ports.Distinct())
        {
            var lvl = new MvIoNative.MvIoMainOutputLevel { Port = p, Status = status, Reserved = new uint[8] };
            if (MvIoNative.SetMainOutputLevel(_handle, ref lvl) == MvIoNative.MvOk)
                return true;
        }

        return false;
    }

    private void TryPnpEnable(int outputPort, bool enabled)
    {
        foreach (uint port in OutputPortEncodings(outputPort))
        {
            var pnp = new MvIoNative.MvIoPnpEnable
            {
                Port = port,
                Enable = enabled ? 1u : 0u,
                Reserved = new uint[8]
            };
            if (MvIoNative.ExecutePnpEnable(_handle, ref pnp) == MvIoNative.MvOk)
                return;
        }
    }

    private void TrySetOutTriggerSource(uint inPort, int outPort)
    {
        uint[] outCandidates = [(uint)outPort, MvIoNative.OutputPortIndex(outPort)];
        uint[] inCandidates = [inPort, 0u, 9u, 255u];
        foreach (uint outEnc in outCandidates.Distinct())
        {
            foreach (uint inEnc in inCandidates.Distinct())
            {
                var assoc = new MvIoNative.MvIoPortAssociation
                {
                    InPortNum = inEnc,
                    OutPortNum = outEnc,
                    Reserved = new uint[8]
                };
                if (MvIoNative.SetOutPortTriggerSource(_handle, ref assoc) == MvIoNative.MvOk)
                    return;
            }
        }
    }

    private int TryOutputEnable(uint portEnc, MvIoNative.IoOutputEnableType enableType)
    {
        var enable = new MvIoNative.MvIoOutputEnable
        {
            Port = portEnc,
            Enable = (uint)enableType,
            Reserved = new uint[8]
        };
        return MvIoNative.SetOutputEnable(_handle, ref enable);
    }

    /// <summary>Восстанавливает DI после изменения профиля любого DO на общей IO-плате.</summary>
    public void RestoreConfiguredInputs()
    {
        foreach (var item in _configuredInputs.ToArray())
            ConfigureInputEdge(item.Key, item.Value.Edge, item.Value.DebounceMs, item.Value.DelayMs);
    }

    /// <summary>
    /// Гасит capture DO в электрический idle и удерживает Enable (не float).
    /// </summary>
    public void ReleaseLine0ForPlc(bool activeHigh = true)
    {
        EnsureOpen();
        foreach (int line0 in ResolveLine0Ports())
            _ = EndSimpleCaptureLevelPulse(line0, activeHigh);
    }

    private int[] ResolveLine0Ports()
    {
        if (Line0OutputPorts is { Length: > 0 })
        {
            var ports = new List<int>(Line0OutputPorts.Length);
            foreach (int p in Line0OutputPorts)
            {
                if (p is >= 1 and <= 8 && !ports.Contains(p))
                    ports.Add(p);
            }

            if (ports.Count > 0)
                return ports.ToArray();
        }

        int fallback = Line0OutputPort is >= 1 and <= 8 ? Line0OutputPort : 6;
        return [fallback];
    }

    public void StopDoOutput(int outputPort)
    {
        EnsureOpen();
        _ = TryOutputEnableAny(outputPort, MvIoNative.IoOutputEnableType.End, out _);
    }

    private bool TryOutputEnableAny(
        int outputPort,
        MvIoNative.IoOutputEnableType enableType,
        out string errors)
    {
        var failed = new List<string>();
        foreach (uint port in OutputPortEncodings(outputPort))
        {
            int ret = TryOutputEnable(port, enableType);
            if (ret == MvIoNative.MvOk)
            {
                errors = "";
                return true;
            }
            failed.Add($"port={port}:0x{ret:x8}");
        }

        errors = string.Join(", ", failed);
        return false;
    }

    private static uint[] OutputPortEncodings(int outputPort) =>
        [(uint)outputPort, MvIoNative.OutputPortIndex(outputPort), MvIoNative.PortMaskForUint(outputPort)];

    public void Dispose()
    {
        if (_disposed)
            return;

        _disposed = true;
        if (_handle == IntPtr.Zero)
            return;

        try
        {
            MvIoNative.Close(_handle);
            MvIoNative.DestroyHandle(_handle);
        }
        catch
        {
            // ignore on shutdown
        }

        _handle = IntPtr.Zero;
    }

    private void EnsureOpen()
    {
        ObjectDisposedException.ThrowIf(_disposed, this);
        if (_handle == IntPtr.Zero)
            throw new InvalidOperationException("IO box is not open.");
    }

    private static string NormalizeComPort(string raw)
    {
        string upper = raw.Trim().ToUpperInvariant();
        return upper.StartsWith("COM", StringComparison.Ordinal) ? upper : $"COM{upper}";
    }

    private static IEnumerable<string> BuildComCandidates(string canonicalCom)
    {
        yield return canonicalCom;
        string alt = canonicalCom.Length >= 4
            ? $"Com{canonicalCom[3..]}"
            : canonicalCom;
        if (!string.Equals(alt, canonicalCom, StringComparison.OrdinalIgnoreCase))
            yield return alt;
    }

    private static string DescribeOpenError(int ret) => ret switch
    {
        unchecked((int)0x80000004) =>
            "Порт занят или уже открыт (часто — второй dotnet run IoInputMonitor).",
        unchecked((int)0x80000204) => "Устройство занято (MV_E_BUSY).",
        _ => ""
    };
}

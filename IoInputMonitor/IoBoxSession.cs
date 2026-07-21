namespace IoInputMonitor;

/// <summary>Сессия с IO box: CreateHandle → Open(COM) → DI / edge callback → Close.</summary>
internal sealed class IoBoxSession : IDisposable
{
    private IntPtr _handle;
    private bool _disposed;
    private MvIoNative.EdgeDetectionCallback? _edgeCallback;
    private readonly Dictionary<int, (uint Edge, uint DebounceMs, uint DelayMs)> _configuredInputs = new();

    public string ComPort { get; }
    public string OpenedComName { get; private set; } = "";

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
    /// Импульс на DO. Auto: SetOutput → Enable → MainLevel (без тихого «hardware» при Soft).
    /// </summary>
    public string FireCapturePulse(IoCaptureOptions capture)
    {
        EnsureOpen();
        return capture.OutputMode switch
        {
            IoCaptureOutputMode.Timer => FireTimerOnly(capture),
            _ => FireDoSoftwarePulse(capture.OutputPort, capture.PulseDurationMs, capture.ActiveHigh)
        };
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
    public string FireDoSoftwarePulse(int outputPort, int durationMs, bool activeHigh = true)
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

        var attempts = new List<(string Tag, uint Pattern, uint Width, uint Period, uint Duration)>
        {
            // Всегда сначала соблюдаем длительность вызывающего кода. Сохранённый профиль
            // DO3/DO4 может приниматься SDK, но содержать статический/нулевой импульс.
            ("board+dur", boardPattern, boardWidth, boardPeriod, duration),
            // Как в MVS на плите — это уже принималось SDK (pat=5, …).
            ("board-exact", boardPattern, board.PulseWidth == 0 ? boardWidth : board.PulseWidth, boardPeriod,
                board.PulseDuration == 0 ? duration : board.PulseDuration),
            ("single", 0, duration, duration, duration),
            ("single-p1", 0, duration, 1, duration),
            ("pwm", 1, Math.Max(1u, duration / 2), duration, duration),
        };

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
            _ = TryPulseMainOutputLevel(outputPort, Math.Min(pulseDuration, 50), activeHigh);
            return $"enable DO{outputPort} (MVS-params pat={boardPattern} w={board.PulseWidth} p={boardPeriod})";
        }

        if (TryPulseMainOutputLevel(outputPort, pulseDuration, activeHigh))
            return $"mainlevel DO{outputPort}";

        throw new InvalidOperationException(
            $"DO{outputPort} pulse failed enable={enableErrors}; " +
            string.Join("; ", errors.Take(6)));
    }

    /// <summary>Удержать уровень DO (для vision_ready / vision_fault → X4/X5).</summary>
    public string SetDoLevel(int outputPort, bool active, bool activeHigh = true)
    {
        EnsureOpen();
        if (outputPort is < 1 or > 8)
            throw new ArgumentOutOfRangeException(nameof(outputPort), "DO port must be 1..8.");

        bool electricalHigh = active == activeHigh;
        TrySetOutTriggerSource(inPort: 0, outPort: outputPort);
        TryPnpEnable(outputPort, enabled: true);
        _ = TryOutputEnableAny(outputPort, MvIoNative.IoOutputEnableType.Start, out _);
        if (!TrySetMainOutputLevel(outputPort, electricalHigh))
        {
            throw new InvalidOperationException(
                $"DO{outputPort} SetMainOutputLevel failed (active={active} active_high={activeHigh})");
        }

        return $"level={(electricalHigh ? "HIGH" : "LOW")}";
    }

    /// <summary>
    /// Импульс брака DO3/DO4 → ПЛК X6/X7.
    /// Предпочтительно board-exact БЕЗ SaveParam (SaveParam глушит DI2/DI3).
    /// После импульса — ReviveDiAfterReject (SetInput + повторный RegisterEdgeCallback).
    /// </summary>
    public string FireRejectPulse(int outputPort, int durationMs, bool activeHigh = true)
    {
        EnsureOpen();
        if (outputPort is < 1 or > 8)
            throw new ArgumentOutOfRangeException(nameof(outputPort), "DO port must be 1..8.");

        int pulseDuration = Math.Clamp(durationMs, 1, 5000);

        ReleaseLine0ForPlc();
        StopDoOutput(outputPort);

        Exception? last = null;
        try
        {
            for (int attempt = 1; attempt <= 3; attempt++)
            {
                try
                {
                    // 1) Как в успешных логах, но без SaveParam — иначе DI умирают.
                    return FireDoSoftwarePulseForReject(outputPort, pulseDuration, activeHigh, saveParam: false);
                }
                catch (Exception ex)
                {
                    last = ex;
                    bool busy = ex.Message.Contains("80000004", StringComparison.OrdinalIgnoreCase)
                        || ex.Message.Contains("80000204", StringComparison.OrdinalIgnoreCase);
                    ReleaseLine0ForPlc();
                    StopDoOutput(outputPort);
                    if (busy && attempt < 3)
                    {
                        Thread.Sleep(100 * attempt);
                        continue;
                    }

                    // 2) Последний шанс — SaveParam (как 18:43), потом обязательно Revive DI.
                    if (attempt == 3 || !busy)
                    {
                        try
                        {
                            return FireDoSoftwarePulseForReject(
                                outputPort, pulseDuration, activeHigh, saveParam: true);
                        }
                        catch (Exception saveEx)
                        {
                            last = saveEx;
                            break;
                        }
                    }
                }
            }

            throw new InvalidOperationException(
                $"DO{outputPort} reject pulse failed: {last?.Message ?? "unknown"}");
        }
        finally
        {
            StopDoOutput(outputPort);
            _ = TrySetMainOutputLevel(outputPort, !activeHigh);
            StopDoOutput(outputPort);
            ReviveDiAfterReject();
        }
    }

    /// <summary>
    /// board-exact первым (рабочий how на X6). SaveParam только если явно разрешён.
    /// </summary>
    private string FireDoSoftwarePulseForReject(
        int outputPort,
        int durationMs,
        bool activeHigh,
        bool saveParam)
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
        Thread.Sleep(5);

        TryGetPortOutputParam(outputPort, out var board);
        uint boardPattern = board.Pattern;
        uint boardPeriod = board.PulsePeriod == 0 ? Math.Max(duration, 1u) : board.PulsePeriod;
        uint boardWidth = board.PulseWidth == 0 ? Math.Max(1u, boardPeriod / 2) : board.PulseWidth;
        if (boardWidth >= boardPeriod)
            boardWidth = Math.Max(1u, boardPeriod / 2);

        var attempts = new List<(string Tag, uint Pattern, uint Width, uint Period, uint Duration)>
        {
            ("board-exact", boardPattern, board.PulseWidth == 0 ? boardWidth : board.PulseWidth, boardPeriod,
                board.PulseDuration == 0 ? duration : board.PulseDuration),
            ("board+dur", boardPattern, boardWidth, boardPeriod, duration),
            ("single", 0, duration, duration, duration),
        };

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
                errors.Add($"{a.Tag}/port={port}:0x{setRet:x8}");
                if ((uint)setRet is 0x80000004u or 0x80000204u)
                    throw new InvalidOperationException(
                        $"DO{outputPort} SetOutput busy 0x{setRet:x8} — wait Capture quiet");
                continue;
            }

            int enRet = TryOutputEnable(port, MvIoNative.IoOutputEnableType.Start);
            if (enRet != MvIoNative.MvOk)
            {
                errors.Add($"{a.Tag}/port={port}:enable=0x{enRet:x8}");
                continue;
            }

            _ = TryPulseMainOutputLevel(outputPort, Math.Min(pulseDuration, 50), activeHigh);
            // Сразу гасим Enable — не оставляем PWM висеть на плате.
            StopDoOutput(outputPort);
            string saveTag = saveParam ? "+save" : "";
            return $"setoutput DO{outputPort} ({a.Tag}{saveTag}, port={port})";
        }

        throw new InvalidOperationException(string.Join("; ", errors.Take(4)));
    }

    /// <summary>
    /// Вызывается после ReviveDiAfterReject: ключ=DI port, value=сейчас HIGH.
    /// Нужен, чтобы Program синхронизировал portPressed — иначе both глотает DI3↑.
    /// </summary>
    public Action<IReadOnlyDictionary<int, bool>>? AfterDiRevive { get; set; }

    /// <summary>
    /// Вернуть DI после reject: гасим DO3/4/5, SetInput по текущему уровню.
    /// Не трогаем RegisterEdgeCallback (повтор → 0x80000003 и ломает монитор).
    /// </summary>
    public void ReviveDiAfterReject()
    {
        EnsureOpen();
        try
        {
            ReleaseLine0ForPlc();
            StopDoOutput(3);
            StopDoOutput(4);
        }
        catch
        {
            // best-effort
        }

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

        StopDoOutput(5);
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

        // DO5 (Line0) и reject делят один handle — снять leftover Enable от съёмки.
        StopDoOutput(5);
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
    /// Гасит leftover Enable на DO5 (Line0) перед PLC DO1–4.
    /// Не трогает DI-конфиг и не вызывает SaveParam — съёмка/edge остаются живыми.
    /// </summary>
    public void ReleaseLine0ForPlc()
    {
        EnsureOpen();
        const int line0 = 5;
        StopDoOutput(line0);
        _ = TrySetMainOutputLevel(line0, high: false);
        StopDoOutput(line0);
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

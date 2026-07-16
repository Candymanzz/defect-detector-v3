namespace IoInputMonitor;

/// <summary>Сессия с IO box: CreateHandle → Open(COM) → DI / edge callback → Close.</summary>
internal sealed class IoBoxSession : IDisposable
{
    private IntPtr _handle;
    private bool _disposed;
    private MvIoNative.EdgeDetectionCallback? _edgeCallback;

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
        uint port = (uint)outputPort;

        // Soft / User: InPort=0 → программный импульс, не Out←In.
        TrySetOutTriggerSource(inPort: 0, outPort: outputPort);
        TryPnpEnable(port, enabled: true);
        MvIoNative.SaveParam(_handle);

        // Idle перед фронтом — иначе повторный импульс может не дать edge на Line0.
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
            // Как в MVS на плите — это уже принималось SDK (pat=5, …).
            ("board-exact", boardPattern, board.PulseWidth == 0 ? boardWidth : board.PulseWidth, boardPeriod,
                board.PulseDuration == 0 ? duration : board.PulseDuration),
            // Длина из конфига при том же pattern.
            ("board+dur", boardPattern, boardWidth, boardPeriod, duration),
            ("single", 0, duration, duration, duration),
            ("single-p1", 0, duration, 1, duration),
            ("pwm", 1, Math.Max(1u, duration / 2), duration, duration),
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
                errors.Add($"{a.Tag}:ex={ex.GetType().Name}");
                continue;
            }

            if (setRet != MvIoNative.MvOk)
            {
                errors.Add($"{a.Tag}:0x{setRet:x8}");
                continue;
            }

            int enRet = TryOutputEnable(port, MvIoNative.IoOutputEnableType.Start);
            if (enRet == MvIoNative.MvOk)
            {
                // Доп. фронт уровнем — камеры на RisingEdge часто ждут именно edge на Line0.
                _ = TryPulseMainOutputLevel(outputPort, Math.Min(pulseDuration, 50), activeHigh);
                return $"setoutput DO{outputPort} ({a.Tag})";
            }

            errors.Add($"{a.Tag}:set=ok enable=0x{enRet:x8}");
        }

        // Как «Execute» в MVS: параметры уже на плате, только Start.
        TryOutputEnable(port, MvIoNative.IoOutputEnableType.End);
        int enableOnly = TryOutputEnable(port, MvIoNative.IoOutputEnableType.Start);
        if (enableOnly == MvIoNative.MvOk)
        {
            _ = TryPulseMainOutputLevel(outputPort, Math.Min(pulseDuration, 50), activeHigh);
            return $"enable DO{outputPort} (MVS-params pat={boardPattern} w={board.PulseWidth} p={boardPeriod})";
        }

        if (TryPulseMainOutputLevel(outputPort, pulseDuration, activeHigh))
            return $"mainlevel DO{outputPort}";

        throw new InvalidOperationException(
            $"DO{outputPort} pulse failed enable=0x{enableOnly:x8}; " +
            string.Join("; ", errors.Take(6)));
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

    private void TryPnpEnable(uint port, bool enabled)
    {
        var pnp = new MvIoNative.MvIoPnpEnable
        {
            Port = port,
            Enable = enabled ? 1u : 0u,
            Reserved = new uint[8]
        };
        _ = MvIoNative.ExecutePnpEnable(_handle, ref pnp);
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

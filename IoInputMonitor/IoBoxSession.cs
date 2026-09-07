namespace IoInputMonitor;

/// <summary>Сессия с IO box: CreateHandle → Open(COM) → DI / edge callback → DO5 capture → Close.</summary>
internal sealed class IoBoxSession : IDisposable
{
    private IntPtr _handle;
    private bool _disposed;
    private MvIoNative.EdgeDetectionCallback? _edgeCallback;
    private readonly Dictionary<int, (uint Edge, uint DebounceMs, uint DelayMs)> _configuredInputs = new();

    public string ComPort { get; }
    public string OpenedComName { get; private set; } = "";

    /// <summary>DO съёмки Line0 (capture.output_port). Только DO5.</summary>
    public int Line0OutputPort { get; set; } = 5;

    /// <summary>DO съёмки (capture.output_ports). Только [5].</summary>
    public int[] Line0OutputPorts { get; set; } = [5];

    public IoBoxSession(string comPort) =>
        ComPort = NormalizeComPort(comPort);

    public void Open()
    {
        ObjectDisposedException.ThrowIf(_disposed, this);
        if (_handle != IntPtr.Zero)
            return;

        const int maxAttempts = 8;
        InvalidOperationException? last = null;
        for (int attempt = 1; attempt <= maxAttempts; attempt++)
        {
            try
            {
                OpenOnce();
                return;
            }
            catch (InvalidOperationException ex) when (IsTransientComOpenFailure(ex) && attempt < maxAttempts)
            {
                last = ex;
                int delayMs = Math.Min(2000, 250 * attempt);
                Thread.Sleep(delayMs);
            }
        }

        throw last ?? new InvalidOperationException($"MV_IO_Open failed for {ComPort}.");
    }

    private void OpenOnce()
    {
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

    private static bool IsTransientComOpenFailure(InvalidOperationException ex)
    {
        string message = ex.Message;
        return message.Contains("0x80000004", StringComparison.OrdinalIgnoreCase)
            || message.Contains("0x80000204", StringComparison.OrdinalIgnoreCase)
            || message.Contains("занят", StringComparison.OrdinalIgnoreCase)
            || message.Contains("занято", StringComparison.OrdinalIgnoreCase);
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
    /// Длительность держит Program → EndSimpleCaptureLevelPulse.
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

    /// <summary>
    /// Один фронт IDLE→ACTIVE на Line0. Без StopDo/Enable-пляски — иначе камеры RisingEdge
    /// ловят 2–3 ложных импульса на один FireDo.
    /// </summary>
    public void BeginSimpleCaptureLevelPulse(int outputPort, bool activeHigh = true)
    {
        EnsureOpen();
        if (outputPort is < 1 or > 8)
            throw new ArgumentOutOfRangeException(nameof(outputPort), "DO port must be 1..8.");

        bool active = activeHigh;
        bool idle = !activeHigh;

        TrySetOutTriggerSource(inPort: 0, outPort: outputPort);
        TryPnpEnable(outputPort, enabled: true);
        // База: idle, Enable Start (без StopDo — Stop даёт лишний фронт).
        if (!TryOutputEnableAny(outputPort, MvIoNative.IoOutputEnableType.Start, out string enableErrors))
            throw new InvalidOperationException($"DO{outputPort} enable failed: {enableErrors}");
        _ = TrySetMainOutputLevel(outputPort, idle);

        if (!TrySetMainOutputLevel(outputPort, active))
            throw new InvalidOperationException($"DO{outputPort} ACTIVE level failed");
    }

    /// <summary>Гасить capture DO: один уход в idle (без двойного StopDo).</summary>
    public string EndSimpleCaptureLevelPulse(int outputPort, bool activeHigh = true)
    {
        EnsureOpen();
        if (outputPort is < 1 or > 8)
            throw new ArgumentOutOfRangeException(nameof(outputPort), "DO port must be 1..8.");

        bool idle = !activeHigh;
        _ = TrySetMainOutputLevel(outputPort, idle);
        return $"DO{outputPort} idle";
    }

    private string FireTimerOnly(IoCaptureOptions capture)
    {
        FireTimerSoftwareTrigger(capture.TimerIndex);
        return $"timer{capture.TimerIndex} → Out{capture.OutputPort}";
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

    /// <summary>Гасит DO5 в idle после импульса (если нужно снаружи).</summary>
    public void ReleaseLine0(bool activeHigh = true)
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

        int fallback = Line0OutputPort is >= 1 and <= 8 ? Line0OutputPort : 5;
        return [fallback];
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

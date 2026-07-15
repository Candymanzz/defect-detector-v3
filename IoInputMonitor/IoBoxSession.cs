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

    /// <summary>Импульс на Line0: direct DO или software trigger таймера (Out5←Timer1 в MVS).</summary>
    public void FireCapturePulse(IoCaptureOptions capture)
    {
        EnsureOpen();
        if (capture.OutputMode == IoCaptureOutputMode.Timer)
            FireTimerSoftwareTrigger(capture.TimerIndex);
        else
            FireOutputPulse(capture.OutputPort, capture.PulseDurationMs);
    }

    /// <summary>Software trigger Timer N — как Execute в MVS (Out5 должен быть Line Source = Timer N).</summary>
    public void FireTimerSoftwareTrigger(int timerIndex)
    {
        EnsureOpen();
        int ret = MvIoTimerTrigger.TriggerSoftware(_handle, timerIndex, out string detail);
        if (ret != MvIoNative.MvOk)
        {
            throw new InvalidOperationException(
                $"Timer{timerIndex} software trigger failed: 0x{ret:x8} via {detail}. " +
                "Проверьте MVS: Out5 Line Source = Timer 1, Trigger Source = Software, Duration/Delay в us.");
        }
    }

    /// <summary>Прямой импульс на DO через MV_IO_SetOutput (OutN Line Source = Software/User).</summary>
    public void FireOutputPulse(int outputPort, int durationMs, bool activeHigh = true)
    {
        EnsureOpen();
        if (outputPort is < 1 or > 8)
            throw new ArgumentOutOfRangeException(nameof(outputPort), "DO port must be 1..8.");

        int pulseDuration = Math.Clamp(durationMs, 1, 65535);
        // SDK/доки расходятся: индекс 0..7, маска как у DI (0x10), либо номер 1..8 — пробуем все.
        uint[] portCandidates =
        [
            MvIoNative.OutputPortIndex(outputPort),
            MvIoNative.PortMaskForUint(outputPort),
            (uint)outputPort
        ];

        int lastRet = unchecked((int)0x80000004);
        uint usedPort = portCandidates[0];
        foreach (uint portEnc in portCandidates.Distinct())
        {
            var output = new MvIoNative.MvIoSetOutput
            {
                Port = portEnc,
                Pattern = (uint)MvIoNative.IoOutputPattern.Single,
                PulseWidth = (uint)pulseDuration,
                PulsePeriod = 1,
                PulseDuration = (uint)pulseDuration,
                Level = activeHigh ? 1u : 0u,
                Reserved = new uint[8]
            };

            lastRet = MvIoNative.SetOutput(_handle, ref output);
            if (lastRet == MvIoNative.MvOk)
            {
                usedPort = portEnc;
                break;
            }
        }

        if (lastRet != MvIoNative.MvOk)
        {
            throw new InvalidOperationException(
                $"MV_IO_SetOutput failed for DO{outputPort}: 0x{lastRet:x8}. " +
                $"SetOutput работает только если Out{outputPort} Line Source = Software/User. " +
                $"Для дубля DI→DO без Software: Out{outputPort}←InN на IO box и capture.enabled=false.");
        }

        var enable = new MvIoNative.MvIoOutputEnable
        {
            Port = usedPort,
            Enable = (uint)MvIoNative.IoOutputEnableType.Start,
            Reserved = new uint[8]
        };

        int ret = MvIoNative.SetOutputEnable(_handle, ref enable);
        if (ret != MvIoNative.MvOk)
        {
            throw new InvalidOperationException(
                $"MV_IO_SetOutputEnable failed for DO{outputPort}: 0x{ret:x8}");
        }
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

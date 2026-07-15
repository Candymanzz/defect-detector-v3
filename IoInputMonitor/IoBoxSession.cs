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

    /// <summary>
    /// Импульс на DO. Auto: SetOutput → Timer → hardware (без throw, если Out5←In3).
    /// </summary>
    public string FireCapturePulse(IoCaptureOptions capture)
    {
        EnsureOpen();
        return capture.OutputMode switch
        {
            IoCaptureOutputMode.Timer => FireTimerOnly(capture),
            IoCaptureOutputMode.Direct => FireDirectOnly(capture),
            _ => FireAuto(capture)
        };
    }

    private string FireAuto(IoCaptureOptions capture)
    {
        try
        {
            FireOutputPulse(capture.OutputPort, capture.PulseDurationMs);
            return $"setoutput DO{capture.OutputPort}";
        }
        catch (Exception directEx)
        {
            try
            {
                FireTimerSoftwareTrigger(capture.TimerIndex);
                return $"timer{capture.TimerIndex} → Out{capture.OutputPort}";
            }
            catch (Exception timerEx)
            {
                return
                    $"hardware-passthrough Out{capture.OutputPort}←In{capture.TriggerPort} " +
                    $"(SetOutput={ShortErr(directEx)}; Timer={ShortErr(timerEx)})";
            }
        }
    }

    private string FireDirectOnly(IoCaptureOptions capture)
    {
        FireOutputPulse(capture.OutputPort, capture.PulseDurationMs);
        return $"setoutput DO{capture.OutputPort}";
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

    /// <summary>Прямой импульс на DO через MV_IO_SetOutput.</summary>
    public void FireOutputPulse(int outputPort, int durationMs, bool activeHigh = true)
    {
        EnsureOpen();
        if (outputPort is < 1 or > 8)
            throw new ArgumentOutOfRangeException(nameof(outputPort), "DO port must be 1..8.");

        int pulseDuration = Math.Clamp(durationMs, 1, 65535);
        uint[] portCandidates =
        [
            MvIoNative.OutputPortIndex(outputPort),
            MvIoNative.PortMaskForUint(outputPort),
            (uint)outputPort
        ];

        foreach (uint portEnc in portCandidates.Distinct())
            TryOutputEnable(portEnc, MvIoNative.IoOutputEnableType.Start);

        int lastRet = unchecked((int)0x80000004);
        uint usedPort = portCandidates[0];
        foreach (uint portEnc in portCandidates.Distinct())
        {
            foreach (uint pattern in new uint[] { 0, 1 })
            {
                var output = new MvIoNative.MvIoSetOutput
                {
                    Port = portEnc,
                    Pattern = pattern,
                    PulseWidth = (uint)pulseDuration,
                    PulsePeriod = Math.Max(1u, (uint)pulseDuration),
                    PulseDuration = (uint)pulseDuration,
                    Level = activeHigh ? 1u : 0u,
                    Reserved = new uint[8]
                };

                lastRet = MvIoNative.SetOutput(_handle, ref output);
                if (lastRet == MvIoNative.MvOk)
                {
                    usedPort = portEnc;
                    goto enableOk;
                }
            }
        }

        throw new InvalidOperationException(
            $"MV_IO_SetOutput failed for DO{outputPort}: 0x{lastRet:x8}");

        enableOk:
        int ret = TryOutputEnable(usedPort, MvIoNative.IoOutputEnableType.Start);
        if (ret != MvIoNative.MvOk)
        {
            throw new InvalidOperationException(
                $"MV_IO_SetOutputEnable failed for DO{outputPort}: 0x{ret:x8}");
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

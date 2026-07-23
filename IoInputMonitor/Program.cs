using System.IO.Ports;

namespace IoInputMonitor;

/// <summary>CLI и основной цикл: edge callback SDK → лог + опциональный UDP.</summary>
internal static class Program
{
    private static int Main(string[] args)
    {
        var options = MonitorOptions.Parse(args);
        if (options.ShowHelp)
        {
            PrintHelp();
            return 0;
        }

        if (options.ListPorts)
        {
            PrintHostComPorts();
            return 0;
        }

        if (options.ProbePorts)
        {
            RunProbe();
            return 0;
        }

        try
        {
            if (options.SimulateDi3)
                return RunSimulateDi3(options);
            if (options.HwDi3Do5)
                return RunHwDi3Do5(options);
            if (options.PulsePort is > 0)
                return RunPulse(options);
            return RunMonitor(options);
        }
        catch (DllNotFoundException)
        {
            Console.Error.WriteLine("MvIOInterfaceBox.dll не найдена рядом с exe.");
            return 2;
        }
        catch (Exception ex)
        {
            Console.Error.WriteLine(ex.Message);
            return 1;
        }
    }

    /// <summary>
    /// Полный hardware-цикл без физического DI3: UDP DI3↑ (arm wait_frame) → delay → DO5 pulse → DI3↓.
    /// Кадры только если Line0 реально получает фронт от DO5.
    /// </summary>
    private static int RunHwDi3Do5(MonitorOptions options)
    {
        var udp = options.UdpPublish;
        string host = string.IsNullOrWhiteSpace(udp.Host) ? "127.0.0.1" : udp.Host;
        int port = udp.Port > 0 ? udp.Port : 9100;
        int di = options.Capture.TriggerPort is >= 1 and <= 8 ? options.Capture.TriggerPort : 3;
        int[] doPorts = options.Capture.ResolveOutputPorts();
        int doPort = doPorts[0];
        string doLabel = options.Capture.FormatOutputPorts();
        int delayMs = Math.Clamp(options.Capture.PulseDelayMs, 0, 5000);
        int durationMs = options.PulseDurationMs > 0
            ? options.PulseDurationMs
            : Math.Max(1, options.Capture.PulseDurationMs);
        int repeats = Math.Clamp(options.Capture.PulseRepeat, 1, 20);
        int gapMs = Math.Clamp(options.Capture.PulseRepeatGapMs, 0, 2000);

        using var client = new System.Net.Sockets.UdpClient();
        var endpoint = new System.Net.IPEndPoint(System.Net.IPAddress.Parse(host), port);

        void SendDi(int value)
        {
            long ts = DateTimeOffset.UtcNow.ToUnixTimeMilliseconds();
            string json = $"{{\"di\":{di},\"value\":{value},\"ts_ms\":{ts},\"hw\":true}}";
            byte[] bytes = System.Text.Encoding.UTF8.GetBytes(json);
            client.Send(bytes, bytes.Length, endpoint);
            Console.WriteLine($"[{Timestamp()}] HW DI{di} value={value} → {host}:{port}");
        }

        SendDi(1);
        Console.WriteLine($"[{Timestamp()}] arm wait_frame, {doLabel} after {delayMs} ms ×{repeats}");
        if (delayMs > 0)
            Thread.Sleep(delayMs);

        var capture = new IoCaptureOptions
        {
            Enabled = true,
            OutputPort = doPort,
            OutputPorts = doPorts,
            OutputMode = options.PulseMode == IoCaptureOutputMode.Auto
                ? IoCaptureOutputMode.Direct
                : options.PulseMode,
            TimerIndex = options.Capture.TimerIndex,
            PulseDurationMs = durationMs,
            TriggerPort = di,
            ActiveHigh = options.Capture.ActiveHigh,
            Line0Edge = options.Capture.Line0Edge
        };

        using var session = new IoBoxSession(options.ComPort);
        session.Line0OutputPort = doPort;
        session.Line0OutputPorts = doPorts;
        Console.WriteLine($"Открываю {options.ComPort} для {doLabel} (edge={capture.Line0Edge} active_high={capture.ActiveHigh})...");
        session.Open();
        try { MvIoNative.SetDebugView(1); } catch { /* optional */ }

        string lastHow = "";
        for (int r = 1; r <= repeats; r++)
        {
            try
            {
                lastHow = session.FireCapturePulse(capture);
                Console.WriteLine($"[{Timestamp()}] {doLabel} pulse {r}/{repeats}: {lastHow}");
            }
            catch (Exception ex)
            {
                Console.WriteLine($"[{Timestamp()}] {doLabel} pulse {r}/{repeats}: FAIL {ex.Message}");
                SendDi(0);
                return 1;
            }

            if (r < repeats && gapMs > 0)
                Thread.Sleep(gapMs);
        }

        Thread.Sleep(50);
        SendDi(0);
        Console.WriteLine($"HW DI{di}+{doLabel}: done via {lastHow} — ждите capture_ok (Line0), не software");
        return 0;
    }

    /// <summary>
    /// Симуляция DI3↑ без железа: UDP на оркестратор (software trigger / trigger_only).
    /// Пока DO5→Line0 не прокинут — основной способ проверить кадры.
    /// </summary>
    private static int RunSimulateDi3(MonitorOptions options)
    {
        var udp = options.UdpPublish;
        string host = string.IsNullOrWhiteSpace(udp.Host) ? "127.0.0.1" : udp.Host;
        int port = udp.Port > 0 ? udp.Port : 9100;
        int di = options.Capture.TriggerPort is >= 1 and <= 8 ? options.Capture.TriggerPort : 3;
        int holdMs = options.SimulateDi3HoldMs > 0 ? options.SimulateDi3HoldMs : 100;

        using var client = new System.Net.Sockets.UdpClient();
        var endpoint = new System.Net.IPEndPoint(System.Net.IPAddress.Parse(host), port);

        void Send(int value)
        {
            long ts = DateTimeOffset.UtcNow.ToUnixTimeMilliseconds();
            string json = $"{{\"di\":{di},\"value\":{value},\"ts_ms\":{ts},\"sim\":true}}";
            byte[] bytes = System.Text.Encoding.UTF8.GetBytes(json);
            client.Send(bytes, bytes.Length, endpoint);
            Console.WriteLine($"[{Timestamp()}] SIM DI{di} value={value} → {host}:{port} {json}");
        }

        Send(1);
        Thread.Sleep(holdMs);
        Send(0);
        Console.WriteLine($"SIM DI{di}: OK (ожидайте trigger_only / кадры в оркестраторе)");
        return 0;
    }

    private static int RunPulse(MonitorOptions options)
    {
        int port = options.PulsePort!.Value;
        if (port != 5)
            throw new ArgumentOutOfRangeException(nameof(options.PulsePort), "Только DO5 (Line0).");
        int durationMs = options.PulseDurationMs > 0
            ? options.PulseDurationMs
            : Math.Max(1, options.Capture.PulseDurationMs);
        var capture = new IoCaptureOptions
        {
            Enabled = true,
            OutputPort = 5,
            OutputPorts = [5],
            OutputMode = options.PulseMode,
            TimerIndex = options.Capture.TimerIndex,
            PulseDurationMs = durationMs,
            TriggerPort = options.Capture.TriggerPort,
            ActiveHigh = options.Capture.ActiveHigh,
            Line0Edge = options.Capture.Line0Edge
        };

        using var session = new IoBoxSession(options.ComPort);
        Console.WriteLine($"Открываю {options.ComPort} для импульса DO{port}...");
        session.Open();
        try { MvIoNative.SetDebugView(1); } catch { /* optional */ }
        Console.WriteLine(
            $"OK {session.OpenedComName}: DO{port} mode={capture.OutputMode} " +
            $"{durationMs} ms, retry every {options.PulseRetryMs} ms");
        LogOutTriggerSource(session, port);

        for (int attempt = 1; ; attempt++)
        {
            string how;
            try
            {
                how = session.FireCapturePulse(capture);
            }
            catch (Exception ex)
            {
                Console.WriteLine($"[{Timestamp()}] attempt {attempt}: FAIL {ex.Message}");
                if (options.PulseMaxAttempts > 0 && attempt >= options.PulseMaxAttempts)
                    return 1;
                Thread.Sleep(options.PulseRetryMs);
                continue;
            }

            bool softwareOk =
                how.StartsWith("setoutput", StringComparison.OrdinalIgnoreCase) ||
                how.StartsWith("enable", StringComparison.OrdinalIgnoreCase) ||
                how.StartsWith("timer", StringComparison.OrdinalIgnoreCase) ||
                how.StartsWith("mainlevel", StringComparison.OrdinalIgnoreCase);
            Console.WriteLine($"[{Timestamp()}] attempt {attempt}: {how}");
            if (softwareOk)
            {
                Console.WriteLine($"DO{port} pulse OK via {how}");
                return 0;
            }

            if (options.PulseMaxAttempts > 0 && attempt >= options.PulseMaxAttempts)
                return 1;
            Thread.Sleep(options.PulseRetryMs);
        }
    }

    private static void LogOutTriggerSource(IoBoxSession session, int port)
    {
        if (session.TryGetOutPortTriggerSource(port, out uint inPort, out uint outPort))
            Console.WriteLine($"Out{port} trigger source: InPort={inPort} (reported Out={outPort})");
        else
            Console.WriteLine($"Out{port} trigger source: read failed");

        if (session.TryGetPortOutputParam(port, out var output))
        {
            Console.WriteLine(
                $"Out{port} param: port={output.Port} pattern={output.Pattern} " +
                $"width={output.PulseWidth} period={output.PulsePeriod} " +
                $"duration={output.PulseDuration} level={output.Level}");
        }
        else
            Console.WriteLine($"Out{port} GetPortOutputParam: failed");
    }

    private static int RunMonitor(MonitorOptions options)
    {
        var sdkVersion = new MvIoNative.MvIoVersion { Reserved = new uint[8] };
        int sdkRet = MvIoNative.GetSdkVersion(ref sdkVersion);
        if (sdkRet == MvIoNative.MvOk)
        {
            Console.WriteLine(
                $"SDK {sdkVersion.Main}.{sdkVersion.Sub}.{sdkVersion.Modify} " +
                $"({sdkVersion.Year:D4}-{sdkVersion.Month:D2}-{sdkVersion.Day:D2})");
        }

        if (options.ConfigPath != null)
            Console.WriteLine($"Конфиг: {options.ConfigPath}");

        using var session = new IoBoxSession(options.ComPort);
        Console.WriteLine($"Открываю {options.ComPort}...");
        session.Open();
        Console.WriteLine($"Подключено: {session.OpenedComName}");

        string inputsLabel = string.Join(", ", options.InputPorts.Select(static p => $"DI{p}"));

        if (!session.TryReadFirmwareVersion(out MvIoNative.MvIoVersion firmware))
        {
            string? ioCom = IoBoxProbe.FindIoBoardComPort();
            throw new InvalidOperationException(
                $"{options.ComPort} открылся, но на нём нет IO box (MV-LE подсветка без DI). " +
                (ioCom != null
                    ? $"Используйте com_port: {ioCom} в конфиге или --com {ioCom}"
                    : "Запустите: dotnet run -- --probe"));
        }

        Console.WriteLine(
            $"Прошивка IO box {firmware.Main}.{firmware.Sub}.{firmware.Modify} " +
            $"({firmware.Year:D4}-{firmware.Month:D2}-{firmware.Day:D2})");

        if (options.ScanAll)
        {
            var all = session.ReadAllInputLevels();
            for (int port = 1; port <= 8; port++)
            {
                byte level = MvIoNative.ReadLevel(all, port);
                Console.WriteLine($"DI{port}: {DescribeLevel(level)} (raw={level})");
            }
            return 0;
        }

        Console.WriteLine(
            $"Мониторинг {inputsLabel} (edge={options.EdgeMode}, debounce={options.DebounceMs} мс). Ctrl+C для выхода.");
        if (options.DebounceMs <= 0)
        {
            Console.WriteLine(
                "WARNING: debounce_ms=0 — bounce DI заливает UDP/DO; для контактов ставь 20–50.");
        }

        var inputSet = new HashSet<int>(options.InputPorts);
        // SDK Glitch = debounce_ms (0 → ловит DI3 ~10 мс). Soft refractory отдельно:
        // при 0 debounce both иначе глотает bounce DI3 → несколько UDP/FireDo на один продукт.
        int softwareRefractoryMs = options.DebounceMs > 0
            ? options.DebounceMs
            : (options.EdgeMode == IoInputEdgeMode.Both ? 80 : 0);
        var edgeTracker = new IoDiEdgeTracker(softwareRefractoryMs);
        var capturePulseScheduler = new IoCapturePulseScheduler();
        object consoleLock = new();
        using var doExecutor = new IoDoExecutor();
        Console.WriteLine(
            $"IO arbiter: Input+Capture(DI{options.Capture.DirectionPort}/{options.Capture.TriggerPort}+{options.Capture.FormatOutputPorts()}); Sleep вне COM");
        session.Line0OutputPort = options.Capture.OutputPort;
        session.Line0OutputPorts = options.Capture.ResolveOutputPorts();

        foreach (int inputPort in options.InputPorts)
        {
            byte level = session.ReadInputLevel(inputPort);
            bool pressed = level == (byte)MvIoNative.IoLevel.High;
            edgeTracker.Seed(inputPort, pressed);
            Console.WriteLine(
                $"[{Timestamp()}] DI{inputPort}: начальный уровень {DescribeLevel(level)} (raw={level})");
            PrintPortInputParam(session, inputPort);
        }

        uint debounceMs = (uint)options.DebounceMs;
        int triggerPort = options.Capture.Enabled
            ? options.Capture.TriggerPort
            : (options.UdpPublish.TriggerPort is >= 1 and <= 8 ? options.UdpPublish.TriggerPort : 3);
        if (ShouldConfigureSdk(options))
        {
            foreach (int inputPort in options.InputPorts)
            {
                bool pressed = edgeTracker.TryGetPressed(inputPort, out bool p) && p;
                // DI3 <10 мс: edge=both не успевает перевооружить Falling → stuck HIGH →
                // следующие Rising глотаются. Триггер — только Rising.
                // IoCaptureGate после Rising сам сбрасывает _triggerActive (Falling не придёт).
                IoInputEdgeMode portMode = inputPort == triggerPort
                    ? IoInputEdgeMode.Rising
                    : options.EdgeMode;
                MvIoNative.IoEdgeType initialEdge = IoDiEdgeTracker.NextEdgeToArm(portMode, pressed);
                session.ConfigureInputEdge(inputPort, (uint)initialEdge, debounceMs);
                if (inputPort == triggerPort && options.EdgeMode == IoInputEdgeMode.Both)
                {
                    Console.WriteLine(
                        $"DI{triggerPort}: edge=Rising (override both) — короткий импульс <10 мс иначе stuck");
                }
            }

            if (options.EdgeMode == IoInputEdgeMode.Both)
            {
                Console.WriteLine(
                    "both: DI1/DI2 — динамическое перевооружение; " +
                    $"DI{triggerPort} — только Rising (короткий photoeye).");
            }
        }
        else
        {
            Console.WriteLine(
                "configure_sdk=false — параметры порта не меняем, используем настройку устройства/MVS.");
        }


        using var udpPublisher = IoInputUdpPublisher.TryCreate(options.UdpPublish, options.InputPorts);
        IoCaptureGate? captureGate = options.Capture.Enabled
            ? new IoCaptureGate(options.Capture)
            : null;

        if (captureGate != null)
        {
            if (edgeTracker.TryGetPressed(options.Capture.DirectionPort, out bool dirInitial))
                captureGate.SeedDirection(dirInitial);

            // Конвейер уже OFF при старте — не держим latch от залипшего DI2.
            if (captureGate.DisarmOnWorkLow
                && edgeTracker.TryGetPressed(captureGate.WorkPort, out bool workHigh)
                && !workHigh)
            {
                captureGate.Disarm();
            }

            Console.WriteLine(
                $"Capture gate: UI={captureGate.SelectedWireValue} → {captureGate.DescribeExpectedArm()} → " +
                DescribeCaptureOutput(options.Capture) +
                $" (require_direction={options.Capture.RequireDirection})");

            if (options.Capture.OutputMode == IoCaptureOutputMode.Timer)
            {
                if (MvIoTimerTrigger.IsAvailable)
                {
                    Console.WriteLine(
                        $"Timer SDK: {MvIoTimerTrigger.ResolvedExport} (Timer{options.Capture.TimerIndex} → Out{options.Capture.OutputPort})");
                }
                else
                {
                    Console.WriteLine(
                        "WARNING: MV_IO timer trigger export not found — для Out5 поставь Line Source = In 3.");
                }
            }

            MvIoDllExports.LogInteresting(Console.Out);
            Console.WriteLine(
                $"{options.Capture.FormatOutputPorts()}: mode={options.Capture.OutputMode} — " +
                $"при DI{options.Capture.TriggerPort}↑ шлём импульс (или hardware Out←In если SDK откажется).");
        }


        using var directionHttp = IoLineDirectionHttpServer.TryStart(
            captureGate,
            options.Capture.DirectionHttp,
            consoleLock);

        if (udpPublisher != null && options.UdpPublish.SendInitialState)
        {
            int udpTriggerPort = options.UdpPublish.TriggerPort;
            foreach (int inputPort in options.InputPorts)
            {
                if (!options.UdpPublish.SendInitialTriggerState && inputPort == udpTriggerPort)
                    continue;

                if (edgeTracker.TryGetPressed(inputPort, out bool closed))
                    udpPublisher.Publish(inputPort, closed);
            }
        }

        session.RegisterEdgeCallback((port, edge) =>
        {
            if (!inputSet.Contains(port))
                return;

            IoInputEdgeMode portEdgeMode = port == triggerPort
                ? IoInputEdgeMode.Rising
                : options.EdgeMode;
            if (!edgeTracker.TryAccept(port, edge, portEdgeMode, out bool closed))
                return;

            string edgeName = edge switch
            {
                MvIoNative.IoEdgeType.Rising => "RISING",
                MvIoNative.IoEdgeType.Falling => "FALLING",
                _ => edge.ToString()
            };
            string action = edge switch
            {
                MvIoNative.IoEdgeType.Rising => "  <- замыкание (LOW -> HIGH)",
                MvIoNative.IoEdgeType.Falling => "  <- размыкание (HIGH -> LOW)",
                _ => ""
            };

            bool risingEdge = edge == MvIoNative.IoEdgeType.Rising;
            IoCaptureDecision captureDecision = captureGate?.Evaluate(port, closed, risingEdge)
                ?? IoCaptureDecision.None;

            lock (consoleLock)
            {
                string udpSuffix = udpPublisher != null ? $"  [udp {port}:{(closed ? 1 : 0)}]" : "";
                Console.WriteLine($"[{Timestamp()}] DI{port} edge {edgeName}{action}{udpSuffix}");
                LogCaptureDecision(captureDecision, options.Capture, captureGate);
            }

            // Сначала UDP → Java/камеры в wait_frame, потом DO на Line0 (иначе RisingEdge уже прошёл).
            udpPublisher?.Publish(port, closed);

            if (captureDecision == IoCaptureDecision.FireDo)
            {
                if (!capturePulseScheduler.TryBegin())
                {
                    // Импульс уже в полёте — слот FireDo не отпускаем (bounce DI3 иначе даст 2-й FireDo).
                    lock (consoleLock)
                    {
                        Console.WriteLine(
                            $"[{Timestamp()}] {options.Capture.FormatOutputPorts()}: НЕ отправляется — импульс уже в полёте (SkipBusy)");
                    }
                }
                else
                {
                    IoCaptureOptions capture = options.Capture;
                    int delayMs = Math.Clamp(capture.PulseDelayMs, 0, 5000);
                    int repeats = Math.Clamp(capture.PulseRepeat, 1, 20);
                    int gapMs = Math.Clamp(capture.PulseRepeatGapMs, 0, 2000);
                    lock (consoleLock)
                    {
                        Console.WriteLine(
                            $"[{Timestamp()}] {capture.FormatOutputPorts()}: after {delayMs} ms ×{repeats} pulse {capture.PulseDurationMs} ms");
                    }

                    _ = Task.Run(async () =>
                    {
                        try
                        {
                            using (doExecutor.Arbiter.CaptureWindow())
                            {
                                try
                                {
                                    if (delayMs > 0)
                                        await Task.Delay(delayMs).ConfigureAwait(false);
                                    for (int i = 0; i < repeats; i++)
                                    {
                                        await FireCapturePulseLoggedAsync(session, doExecutor, consoleLock, capture)
                                            .ConfigureAwait(false);
                                        if (i + 1 < repeats && gapMs > 0)
                                            await Task.Delay(gapMs).ConfigureAwait(false);
                                    }
                                    // EndSimpleCaptureLevelPulse уже в FireCapturePulseLoggedAsync —
                                    // повторный ReleaseLine0ForPlc давал лишние фронты на Line0.
                                }
                                catch (Exception ex)
                                {
                                    lock (consoleLock)
                                    {
                                        Console.Error.WriteLine(
                                            $"[{Timestamp()}] {capture.FormatOutputPorts()}: delayed FAIL — {ex.Message}");
                                    }
                                }
                            }
                        }
                        finally
                        {
                            capturePulseScheduler.End();
                        }
                    });
                }
            }

            if (ShouldConfigureSdk(options))
            {
                IoInputEdgeMode rearmMode = port == triggerPort
                    ? IoInputEdgeMode.Rising
                    : options.EdgeMode;
                // Rising-only (DI3): после каждого ↑ снова Rising.
                // Both (DI1/DI2): противоположный фронт.
                if (rearmMode is IoInputEdgeMode.Both or IoInputEdgeMode.Rising or IoInputEdgeMode.Falling)
                {
                    bool pressed = edgeTracker.TryGetPressed(port, out bool p) && p;
                    MvIoNative.IoEdgeType nextEdge = IoDiEdgeTracker.NextEdgeToArm(rearmMode, pressed);
                    int rearmPort = port;
                    _ = doExecutor.RunAsync(
                        IoDoExecutor.Priority.Input,
                        () => ReArmEdge(session, rearmPort, nextEdge, debounceMs));
                }
            }
        });
        Console.WriteLine("Ожидаю фронты...");

        using var cts = new CancellationTokenSource();
        Console.CancelKeyPress += (_, e) =>
        {
            e.Cancel = true;
            cts.Cancel();
        };

        cts.Token.WaitHandle.WaitOne();

        Console.WriteLine("Остановлено.");
        return 0;
    }

    private static void LogCaptureDecision(IoCaptureDecision decision, IoCaptureOptions capture, IoCaptureGate? gate)
    {
        string expect = gate?.DescribeExpectedArm()
            ?? $"DI{capture.DirectionPort} затем DI{capture.TriggerPort}↑";
        string mode = gate?.SelectedWireValue ?? capture.InitialDirection;

        switch (decision)
        {
            case IoCaptureDecision.DirectionArmed:
                Console.WriteLine(
                    $"[{Timestamp()}] capture: DI{capture.DirectionPort}=1 — направление зафиксировано ({expect})");
                break;
            case IoCaptureDecision.DirectionDisarmed:
                Console.WriteLine(
                    $"[{Timestamp()}] capture: disarm — направление снято" +
                    (capture.DisarmOnWorkLow ? $" (DI{capture.WorkPort}↓ / HTTP)" : " (HTTP)"));
                break;
            case IoCaptureDecision.SkipNoDirection:
                Console.WriteLine(
                    $"[{Timestamp()}] {capture.FormatOutputPorts()}: НЕ отправляется — DI{capture.TriggerPort}↑ SKIP " +
                    $"(UI={mode}, жду {expect})");
                break;
            case IoCaptureDecision.SkipAlreadyFired:
                Console.WriteLine(
                    $"[{Timestamp()}] {capture.FormatOutputPorts()}: НЕ отправляется — импульс уже был в этом пульсе");
                break;
            case IoCaptureDecision.SkipBusy:
                Console.WriteLine(
                    $"[{Timestamp()}] {capture.FormatOutputPorts()}: НЕ отправляется — импульс уже в полёте");
                break;
            case IoCaptureDecision.FireDo:
                Console.WriteLine(
                    $"[{Timestamp()}] {capture.FormatOutputPorts()}: SEND — DI{capture.TriggerPort}↑ UI={mode} → {DescribeCaptureSend(capture)}");
                break;
            case IoCaptureDecision.DirectionModeChanged:
                Console.WriteLine(
                    $"[{Timestamp()}] capture: UI ход → {mode} ({expect})");
                break;
        }
    }

    private static string DescribeCaptureOutput(IoCaptureOptions capture) =>
        capture.OutputMode switch
        {
            IoCaptureOutputMode.Timer => $"Timer{capture.TimerIndex}→{capture.FormatOutputPorts()}",
            IoCaptureOutputMode.Direct => $"{capture.FormatOutputPorts()} pulse {capture.PulseDurationMs} ms",
            _ => $"{capture.FormatOutputPorts()} auto (SetOutput→Timer→Out←In{capture.TriggerPort})"
        };

    private static string DescribeCaptureSend(IoCaptureOptions capture) =>
        capture.OutputMode switch
        {
            IoCaptureOutputMode.Timer => $"TRIGGER Timer{capture.TimerIndex} → {capture.FormatOutputPorts()}",
            IoCaptureOutputMode.Direct =>
                $"SEND {capture.FormatOutputPorts()} pulse {capture.PulseDurationMs} ms edge={capture.Line0Edge} active_high={capture.ActiveHigh}",
            _ => $"AUTO {capture.FormatOutputPorts()} edge={capture.Line0Edge} active_high={capture.ActiveHigh}"
        };

    private static async Task FireCapturePulseLoggedAsync(
        IoBoxSession session,
        IoDoExecutor doExecutor,
        object consoleLock,
        IoCaptureOptions capture)
    {
        string doLabel = capture.FormatOutputPorts();
        try
        {
            lock (consoleLock)
            {
                Console.WriteLine($"[{Timestamp()}] {doLabel}: очередь Capture…");
            }

            // Старт DO ACTIVE; pulse_duration_ms снаружи; потом StopDo (без hold).
            string how = await doExecutor.RunAsync(IoDoExecutor.Priority.Capture, () =>
                session.FireCapturePulse(capture)).ConfigureAwait(false);

            int settleMs = Math.Clamp(capture.PulseDurationMs, 1, 2000);
            await Task.Delay(settleMs).ConfigureAwait(false);

            string released = await doExecutor.RunAsync(IoDoExecutor.Priority.Capture, () =>
            {
                var parts = new List<string>();
                foreach (int port in capture.ResolveOutputPorts())
                    parts.Add(session.EndSimpleCaptureLevelPulse(port, capture.ActiveHigh));
                return string.Join("; ", parts);
            }).ConfigureAwait(false);

            lock (consoleLock)
            {
                Console.WriteLine(
                    $"[{Timestamp()}] {doLabel}: OK — {how} → {released} after {settleMs} ms");
            }
        }
        catch (Exception ex)
        {
            lock (consoleLock)
            {
                Console.Error.WriteLine(
                    $"[{Timestamp()}] {doLabel}: FAIL — {ex.Message}");
            }
        }
    }

    // both всегда вызывает SetInput (перевооружение); иначе — только если configure_sdk=true.
    private static bool ShouldConfigureSdk(MonitorOptions options) =>
        options.EdgeMode == IoInputEdgeMode.Both || options.ConfigureSdk;

    private static void ReArmEdge(
        IoBoxSession session,
        int port,
        MvIoNative.IoEdgeType edge,
        uint debounceMs)
    {
        if (!session.TryConfigureInputEdge(port, (uint)edge, debounceMs))
        {
            Console.Error.WriteLine(
                $"[{Timestamp()}] DI{port}: не удалось перевооружить фронт {IoBoxSession.DescribeEdge((uint)edge)}");
        }
    }

    private static void PrintPortInputParam(IoBoxSession session, int inputPort)
    {
        if (!session.TryReadPortInputParam(inputPort, out MvIoNative.MvIoSetInput param))
            return;

        string edge = IoBoxSession.DescribeEdge(param.Edge);
        string notice = param.Enable == 1 ? "вкл" : "выкл";
        Console.WriteLine(
            $"DI{inputPort}: устройство edge={edge}, уведомления={notice}, debounce={param.Glitch} мс");
    }

    private static void RunProbe()
    {
        Console.WriteLine("Поиск IO box на COM-портах...");
        foreach (IoBoxProbe.ProbeResult result in IoBoxProbe.ScanAllPorts())
        {
            if (result.HasIoBoard)
                Console.WriteLine($"{result.ComPort}: IO box OK, прошивка {result.Firmware}");
            else if (result.Opened)
                Console.WriteLine($"{result.ComPort}: открыт, но не IO box — {result.Error}");
            else
                Console.WriteLine($"{result.ComPort}: не открылся — {result.Error}");
        }

        string? ioCom = IoBoxProbe.FindIoBoardComPort();
        if (ioCom != null)
            Console.WriteLine($"Рекомендуемый порт для DI: {ioCom}");
        else
            Console.WriteLine("IO box не найден ни на одном COM.");
    }

    private static string DescribeLevel(byte level) =>
        level switch
        {
            0 => "LOW",
            1 => "HIGH",
            _ => $"UNKNOWN({level})"
        };

    private static string Timestamp() =>
        DateTime.Now.ToString("HH:mm:ss.fff");

    private static void PrintHostComPorts()
    {
        string[] ports = SerialPort.GetPortNames()
            .OrderBy(static p => p, StringComparer.OrdinalIgnoreCase)
            .ToArray();

        if (ports.Length == 0)
        {
            Console.WriteLine("На ПК не видно COM-портов.");
            return;
        }

        Console.WriteLine("COM-порты Windows:");
        foreach (string port in ports)
            Console.WriteLine($"  {port}");
    }

    private static void PrintHelp()
    {
        Console.WriteLine(
            """
            IoInputMonitor — события Digital Input с MV IO Box (MvIOInterfaceBox.dll)
            Мониторинг через edge callback SDK, без периодического опроса.

            Использование:
              IoInputMonitor
              IoInputMonitor --com COM3 --input 3
              IoInputMonitor --probe
              IoInputMonitor --com COM3 --scan
              IoInputMonitor --pulse 5
              IoInputMonitor --hw-di3
              IoInputMonitor --simulate-di3
              IoInputMonitor --list

            Конфиг (по умолчанию config/blocks/52-io-input.yaml):
              com_port, inputs, edge (rising|falling|both), debounce_ms, configure_sdk
              rising — только замыкание (LOW→HIGH)
              falling — только размыкание (HIGH→LOW)
              both — оба фронта через динамическое перевооружение SDK (событийно, без polling)
              configure_sdk: false — не вызывать SetInput, использовать настройку MVS/устройства
              publish.udp — UDP 1/0 при смене состояния (1=замкнуто/HIGH, 0=разомкнуто/LOW)

            Параметры:
              --com COMx       COM-порт IO box (переопределяет конфиг)
              --input N        DI 1..8 (переопределяет inputs из конфига)
              --scan           Однократно показать DI1..DI8 и выйти
              --pulse 5        Импульс только на DO5 (Line0)
              --pulse-ms M     Длительность импульса (по умолчанию из capture.pulse_duration_ms)
              --pulse-mode M   auto|direct|timer (по умолчанию auto)
              --hw-di3         UDP DI3↑ + DO5 pulse (hardware Line0; без software trigger)
              --simulate-di3   только UDP DI3 (software path; для отладки)
              --simulate-hold  Пауза HIGH перед ↓ (мс, по умолчанию 100)
              --probe          Найти, на каком COM висит IO box с DI
              --list           Показать COM-порты Windows
              --help           Эта справка

            Примечания:
              - COM1/COM2 часто заняты MV-LE (подсветка), DI там нет.
              - IO box с DI обычно на отдельном COM (у вас COM3).
              - Закройте MVS Client / LightServer, если COM занят.
              - Кадры от Line0: --hw-di3 при hardware_line_trigger=true.
            """);
    }

    private sealed class MonitorOptions
    {
        public string ComPort { get; set; } = "COM3";
        public int[] InputPorts { get; set; } = [3];
        public IoInputEdgeMode EdgeMode { get; set; } = IoInputEdgeMode.Rising;
        public bool ConfigureSdk { get; set; }
        public int DebounceMs { get; set; } = 50;
        public IoInputUdpPublishOptions UdpPublish { get; set; } = new();
        public IoCaptureOptions Capture { get; set; } = new();
        public bool ScanAll { get; set; }
        public int? PulsePort { get; set; }
        public int PulseDurationMs { get; set; }
        public IoCaptureOutputMode PulseMode { get; set; } = IoCaptureOutputMode.Auto;
        public int PulseRetryMs { get; set; } = 250;
        /// <summary>0 = бесконечно; иначе стоп после N неудач.</summary>
        public int PulseMaxAttempts { get; set; }
        public bool SimulateDi3 { get; set; }
        public int SimulateDi3HoldMs { get; set; } = 100;
        /// <summary>UDP DI3↑ + DO5 pulse (hardware Line0 path).</summary>
        public bool HwDi3Do5 { get; set; }
        public bool ProbePorts { get; set; }
        public bool ListPorts { get; set; }
        public bool ShowHelp { get; set; }
        public string? ConfigPath { get; set; }

        public static MonitorOptions Parse(string[] args)
        {
            if (args.Length == 0)
                return FromConfig(args);

            var options = FromConfig(args);

            for (int i = 0; i < args.Length; i++)
            {
                string arg = args[i];
                switch (arg)
                {
                    case "--help":
                    case "-h":
                    case "/?":
                        return new MonitorOptions { ShowHelp = true };
                    case "--list":
                        return new MonitorOptions { ListPorts = true };
                    case "--scan":
                        options.ScanAll = true;
                        break;
                    case "--pulse":
                        options.PulsePort = int.Parse(RequireValue(args, ref i, "--pulse"));
                        break;
                    case "--pulse-ms":
                        options.PulseDurationMs = int.Parse(RequireValue(args, ref i, "--pulse-ms"));
                        break;
                    case "--pulse-mode":
                        options.PulseMode = ParsePulseMode(RequireValue(args, ref i, "--pulse-mode"));
                        break;
                    case "--pulse-attempts":
                        options.PulseMaxAttempts = int.Parse(RequireValue(args, ref i, "--pulse-attempts"));
                        break;
                    case "--simulate-di3":
                    case "--sim-di3":
                    case "--fire-di3":
                        options.SimulateDi3 = true;
                        break;
                    case "--simulate-hold":
                        options.SimulateDi3HoldMs = int.Parse(RequireValue(args, ref i, "--simulate-hold"));
                        break;
                    case "--hw-di3":
                    case "--arm-do5":
                    case "--do5-capture":
                        options.HwDi3Do5 = true;
                        break;
                    case "--probe":
                        options.ProbePorts = true;
                        break;
                    case "--com":
                        options.ComPort = RequireValue(args, ref i, "--com");
                        break;
                    case "--input":
                        options.InputPorts = [int.Parse(RequireValue(args, ref i, "--input"))];
                        break;
                    default:
                        if (arg.StartsWith(IoInputConfigLoader.ConfigCliPrefix, StringComparison.OrdinalIgnoreCase))
                            break;
                        throw new ArgumentException($"Неизвестный аргумент: {arg}");
                }
            }

            Validate(options);
            return options;
        }

        private static IoCaptureOutputMode ParsePulseMode(string value) =>
            value.Trim().ToLowerInvariant() switch
            {
                "auto" => IoCaptureOutputMode.Auto,
                "direct" => IoCaptureOutputMode.Direct,
                "timer" => IoCaptureOutputMode.Timer,
                _ => throw new ArgumentException(
                    $"Неизвестный --pulse-mode: {value} (ожидается auto|direct|timer)")
            };

        private static MonitorOptions FromConfig(string[] args)
        {
            IoInputConfigLoadResult loaded = IoInputConfigLoader.Load(args);
            return new MonitorOptions
            {
                ComPort = loaded.Options.ComPort,
                InputPorts = loaded.Options.InputPorts,
                EdgeMode = loaded.Options.EdgeMode,
                ConfigureSdk = loaded.Options.ConfigureSdk,
                DebounceMs = loaded.Options.DebounceMs,
                UdpPublish = loaded.Options.UdpPublish,
                Capture = loaded.Options.Capture,
                ConfigPath = loaded.ConfigPath
            };
        }

        private static void Validate(MonitorOptions options)
        {
            if (options.PulsePort is int pulsePort && pulsePort != 5)
                throw new ArgumentOutOfRangeException(nameof(options.PulsePort), "Только DO5 (Line0).");

            if (options.InputPorts.Length == 0
                && options.PulsePort is null
                && !options.SimulateDi3
                && !options.HwDi3Do5)
                throw new ArgumentException("Список inputs пуст — укажите DI 1..8 в конфиге или --input N.");

            foreach (int port in options.InputPorts)
            {
                if (port is < 1 or > 8)
                    throw new ArgumentOutOfRangeException(nameof(options.InputPorts), "DI должен быть 1..8.");
            }

            if (options.DebounceMs is < 0 or > 1000)
                throw new ArgumentOutOfRangeException(nameof(options.DebounceMs), "debounce_ms должен быть 0..1000.");
        }

        private static string RequireValue(string[] args, ref int index, string name)
        {
            if (index + 1 >= args.Length)
                throw new ArgumentException($"После {name} нужно значение.");

            index++;
            return args[index];
        }
    }
}

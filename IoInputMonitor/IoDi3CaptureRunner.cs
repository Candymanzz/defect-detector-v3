namespace IoInputMonitor;

/// <summary>UDP DI↑ + DO импульс (физический trigger или HTTP synthetic).</summary>
internal static class IoDi3CaptureRunner
{
    internal static async Task<(bool Ok, string Detail)> FireSyntheticDi3Async(
        IoCaptureGate? captureGate,
        IoInputUdpPublisher? udpPublisher,
        IoBoxSession session,
        IoDoExecutor doExecutor,
        IoCapturePulseScheduler capturePulseScheduler,
        IoCaptureOptions capture,
        int triggerPort,
        object consoleLock)
    {
        if (captureGate == null || !capture.Enabled)
            return (false, "capture disabled");

        if (!capture.TryGetChannel(triggerPort, out IoCaptureChannel channel))
            channel = capture.ResolveChannels()[0];

        IoCaptureDecision decision = captureGate.Evaluate(triggerPort, true, true);
        lock (consoleLock)
        {
            Console.WriteLine(
                $"[{Timestamp()}] synthetic DI{triggerPort}↑ decision={decision}");
        }

        if (decision != IoCaptureDecision.FireDo)
            return (false, decision.ToString());

        udpPublisher?.Publish(triggerPort, true);

        if (capture.Strategy == IoCaptureStrategyKind.Hardware)
            return (true, "hardware strategy — soft DO skipped");

        if (!capturePulseScheduler.TryBegin())
        {
            captureGate.ReleaseCaptureFireSlot(triggerPort);
            return (false, "pulse scheduler busy");
        }

        try
        {
            IoCaptureOptions bound = capture.ForChannel(channel);
            bound.OutputMode = IoCaptureOutputMode.Timer;
            if (bound.TimerIndex is < 1 or > 8)
                bound.TimerIndex = channel.OutputPort;
            await FireDoPulsesFromFrontAsync(session, doExecutor, consoleLock, bound).ConfigureAwait(false);
            return (true, "ok");
        }
        catch (Exception ex)
        {
            return (false, ex.Message);
        }
        finally
        {
            capturePulseScheduler.End();
        }
    }

    /// <summary>Только primary DO (2-й кадр burst), без UDP и без слота gate.</summary>
    internal static void StartLine0PulseOnly(
        IoBoxSession session,
        IoDoExecutor doExecutor,
        IoCapturePulseScheduler capturePulseScheduler,
        IoCaptureOptions capture,
        object consoleLock)
    {
        if (!capturePulseScheduler.TryBegin())
        {
            lock (consoleLock)
            {
                Console.WriteLine(
                    $"[{Timestamp()}] {capture.FormatOutputPorts()}: line0-pulse SKIP — лимит параллельных импульсов");
            }
            return;
        }

        IoCaptureOptions bound = capture.ForChannel(capture.ResolveChannels()[0]);
        _ = Task.Run(async () =>
        {
            try
            {
                try
                {
                    await FireDoPulsesFromFrontAsync(session, doExecutor, consoleLock, bound).ConfigureAwait(false);
                }
                catch (Exception ex)
                {
                    lock (consoleLock)
                    {
                        Console.Error.WriteLine(
                            $"[{Timestamp()}] {bound.FormatOutputPorts()}: line0-pulse FAIL — {ex.Message}");
                    }
                }
            }
            finally
            {
                capturePulseScheduler.End();
            }
        });
    }

    internal static void StartFireDoAfterUdp(
        IoCaptureGate? captureGate,
        IoBoxSession session,
        IoDoExecutor doExecutor,
        IoCapturePulseScheduler capturePulseScheduler,
        IoCaptureOptions capture,
        int triggerPort,
        object consoleLock)
    {
        if (captureGate == null)
            return;

        if (!capturePulseScheduler.TryBegin())
        {
            captureGate.ReleaseCaptureFireSlot(triggerPort);
            lock (consoleLock)
            {
                Console.WriteLine(
                    $"[{Timestamp()}] {capture.FormatOutputPorts()}: НЕ отправляется — лимит параллельных импульсов (SkipBusy)");
            }
            return;
        }

        int delayMs = Math.Clamp(capture.PulseDelayMs, 0, 5000);
        int repeats = Math.Clamp(capture.PulseRepeat, 1, 20);
        int gapMs = Math.Clamp(capture.PulseRepeatGapMs, 0, 2000);
        lock (consoleLock)
        {
            if (repeats <= 1)
            {
                Console.WriteLine(
                    $"[{Timestamp()}] {capture.FormatOutputPorts()}: after {delayMs} ms ×1 pulse {capture.PulseDurationMs} ms");
            }
            else
            {
                Console.WriteLine(
                    $"[{Timestamp()}] {capture.FormatOutputPorts()}: front → DO×{repeats} at +{delayMs}ms then +{gapMs}ms "
                    + $"(duration {capture.PulseDurationMs} ms)");
            }
        }

        long frontTick = Environment.TickCount64;
        _ = Task.Run(async () =>
        {
            try
            {
                await FireDoPulsesFromFrontAsync(session, doExecutor, consoleLock, capture, frontTick)
                    .ConfigureAwait(false);
            }
            catch (Exception ex)
            {
                lock (consoleLock)
                {
                    Console.Error.WriteLine(
                        $"[{Timestamp()}] {capture.FormatOutputPorts()}: delayed FAIL — {ex.Message}");
                }
            }
            finally
            {
                capturePulseScheduler.End();
            }
        });
    }

    /// <summary>
    /// Импульсы DO от момента фронта: 1-й в T+delay, 2-й в T+delay+gap (не «после конца 1-го»).
    /// </summary>
    private static async Task FireDoPulsesFromFrontAsync(
        IoBoxSession session,
        IoDoExecutor doExecutor,
        object consoleLock,
        IoCaptureOptions capture,
        long? frontTickMs = null)
    {
        int delayMs = Math.Clamp(capture.PulseDelayMs, 0, 5000);
        int repeats = Math.Clamp(capture.PulseRepeat, 1, 20);
        int gapMs = Math.Clamp(capture.PulseRepeatGapMs, 0, 2000);
        long t0 = frontTickMs ?? Environment.TickCount64;

        using (doExecutor.Arbiter.CaptureWindow())
        {
            for (int i = 0; i < repeats; i++)
            {
                int targetOffsetMs = delayMs + i * gapMs;
                await WaitUntilFrontOffsetAsync(t0, targetOffsetMs).ConfigureAwait(false);
                lock (consoleLock)
                {
                    Console.WriteLine(
                        $"[{Timestamp()}] {capture.FormatOutputPorts()}: pulse {i + 1}/{repeats} "
                        + $"(+{ElapsedSinceFrontMs(t0)} ms from front)");
                }
                await FireCapturePulseLoggedAsync(session, doExecutor, consoleLock, capture)
                    .ConfigureAwait(false);
            }
        }
    }

    private static async Task WaitUntilFrontOffsetAsync(long frontTickMs, int targetOffsetMs)
    {
        while (true)
        {
            long elapsed = Environment.TickCount64 - frontTickMs;
            int remaining = targetOffsetMs - (int)elapsed;
            if (remaining <= 0)
                return;
            await Task.Delay(Math.Min(remaining, 25)).ConfigureAwait(false);
        }
    }

    private static long ElapsedSinceFrontMs(long frontTickMs) =>
        Math.Max(0L, Environment.TickCount64 - frontTickMs);

    private static async Task FireCapturePulseLoggedAsync(
        IoBoxSession session,
        IoDoExecutor doExecutor,
        object consoleLock,
        IoCaptureOptions capture)
    {
        string doLabel = capture.FormatOutputPorts();
        bool timerMode = capture.OutputMode == IoCaptureOutputMode.Timer;
        try
        {
            lock (consoleLock)
            {
                Console.WriteLine($"[{Timestamp()}] {doLabel}: очередь Capture…");
            }

            string how = await doExecutor.RunAsync(IoDoExecutor.Priority.Capture, () =>
                session.FireCapturePulse(capture)).ConfigureAwait(false);

            if (timerMode)
            {
                // Timer Software Execute: длительность задаёт MVS Timer, не SetOutput hold.
                lock (consoleLock)
                {
                    Console.WriteLine($"[{Timestamp()}] {doLabel}: OK — {how}");
                }
                return;
            }

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
            throw;
        }
    }

    private static string Timestamp() => DateTime.Now.ToString("HH:mm:ss.fff");
}

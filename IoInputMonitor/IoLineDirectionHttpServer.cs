using System.Net;
using System.Text;
using System.Text.Json;

namespace IoInputMonitor;

/// <summary>
/// HTTP API IoInputMonitor:
/// GET/PUT /line-direction — ход линии;
/// POST /capture-disarm — снять direction latch;
/// POST /reject — импульс DO → ПЛК X6/X7 (брак линии).
/// </summary>
internal sealed class IoLineDirectionHttpServer : IDisposable
{
    private readonly IoCaptureGate? _gate;
    private readonly IoBoxSession? _session;
    private readonly IoRejectOptions? _reject;
    private readonly HttpListener _listener;
    private readonly CancellationTokenSource _cts = new();
    private readonly Task _loop;
    private readonly object _consoleLock;
    private readonly IoDoExecutor? _doExecutor;
    private bool _disposed;
    /// <summary>vision_ready держим HIGH (DO1→X4) только если ready_enabled и HTTP выставил hold.</summary>
    private volatile bool _visionReadyHold;
    private volatile bool _visionReadyApplied;

    private IoLineDirectionHttpServer(
        IoCaptureGate? gate,
        IoBoxSession? session,
        IoRejectOptions? reject,
        string prefix,
        object consoleLock,
        IoDoExecutor? doExecutor)
    {
        _gate = gate;
        _session = session;
        _reject = reject;
        _consoleLock = consoleLock;
        _doExecutor = doExecutor;
        _listener = new HttpListener();
        _listener.Prefixes.Add(prefix);
        _listener.Start();
        _loop = Task.Run(ListenLoopAsync);
        var routes = new List<string>();
        if (_gate != null)
        {
            routes.Add("line-direction (GET/PUT)");
            routes.Add("capture-disarm (POST)");
        }
        if (_reject is { Enabled: true } && _session != null)
            routes.Add("reject (POST), vision-ready/vision-fault (PUT)");
        Console.WriteLine($"IO control HTTP ← {prefix}{string.Join(", ", routes)}");
    }

    public static IoLineDirectionHttpServer? TryStart(
        IoCaptureGate? gate,
        IoDirectionHttpOptions? directionHttp,
        object consoleLock,
        IoBoxSession? session = null,
        IoRejectOptions? reject = null,
        IoDoExecutor? doExecutor = null)
    {
        bool captureHttp = gate != null && directionHttp is { Enabled: true };
        bool rejectEnabled = session != null && reject is { Enabled: true };
        if (!captureHttp && !rejectEnabled)
            return null;

        IoDirectionHttpOptions http =
            directionHttp
            ?? new IoDirectionHttpOptions { Enabled = true, Host = "127.0.0.1", Port = 9101 };

        if (http.Port is < 1 or > 65535)
            throw new ArgumentOutOfRangeException(nameof(http.Port), "HTTP port должен быть 1..65535.");

        string host = string.IsNullOrWhiteSpace(http.Host) ? "127.0.0.1" : http.Host.Trim();
        string prefix = $"http://{host}:{http.Port}/";
        try
        {
            return new IoLineDirectionHttpServer(
                captureHttp ? gate : null,
                rejectEnabled ? session : null,
                rejectEnabled ? reject : null,
                prefix,
                consoleLock,
                doExecutor);
        }
        catch (Exception ex)
        {
            Console.Error.WriteLine($"IO control HTTP не стартовал ({prefix}): {ex.Message}");
            return null;
        }
    }

    private async Task ListenLoopAsync()
    {
        while (!_cts.IsCancellationRequested)
        {
            HttpListenerContext? ctx = null;
            try
            {
                ctx = await _listener.GetContextAsync().WaitAsync(_cts.Token);
            }
            catch (OperationCanceledException)
            {
                break;
            }
            catch (ObjectDisposedException)
            {
                break;
            }
            catch (HttpListenerException) when (_cts.IsCancellationRequested)
            {
                break;
            }
            catch (Exception ex)
            {
                Console.Error.WriteLine($"[{Timestamp()}] IO control HTTP accept: {ex.Message}");
                continue;
            }

            _ = Task.Run(() => HandleRequest(ctx));
        }
    }

    private void HandleRequest(HttpListenerContext ctx)
    {
        try
        {
            string path = ctx.Request.Url?.AbsolutePath?.TrimEnd('/').ToLowerInvariant() ?? "";
            WriteCors(ctx.Response);

            if (ctx.Request.HttpMethod.Equals("OPTIONS", StringComparison.OrdinalIgnoreCase))
            {
                ctx.Response.StatusCode = 204;
                ctx.Response.Close();
                return;
            }

            if (path is "/reject" or "/api/client/reject")
            {
                HandleReject(ctx);
                return;
            }

            if (path is "/capture-disarm" or "/api/client/capture-disarm" or "/disarm")
            {
                HandleCaptureDisarm(ctx);
                return;
            }

            if (path is "/vision-ready" or "/api/client/vision-ready")
            {
                HandleVisionLevel(
                    ctx,
                    "vision_ready",
                    _reject?.ReadyOutputPort ?? 1,
                    _reject?.ReadyPlcInput ?? "X4");
                return;
            }

            if (path is "/vision-fault" or "/api/client/vision-fault")
            {
                HandleVisionLevel(
                    ctx,
                    "vision_fault",
                    _reject?.FaultOutputPort ?? 2,
                    _reject?.FaultPlcInput ?? "X5");
                return;
            }

            if (path is not "/line-direction" and not "/api/client/line-direction")
            {
                WriteJson(ctx.Response, 404, new { error = "not found" });
                return;
            }

            if (_gate == null)
            {
                WriteJson(ctx.Response, 503, new { error = "line-direction disabled" });
                return;
            }

            if (ctx.Request.HttpMethod.Equals("GET", StringComparison.OrdinalIgnoreCase))
            {
                WriteJson(ctx.Response, 200, new
                {
                    direction = _gate.SelectedWireValue,
                    source = "manual",
                    armed = _gate.IsDirectionArmed,
                    latched = _gate.IsDirectionLatched,
                    expect = _gate.DescribeExpectedArm()
                });
                return;
            }

            if (ctx.Request.HttpMethod.Equals("PUT", StringComparison.OrdinalIgnoreCase)
                || ctx.Request.HttpMethod.Equals("POST", StringComparison.OrdinalIgnoreCase))
            {
                string body = ReadBody(ctx.Request);
                string? direction = null;
                if (!string.IsNullOrWhiteSpace(body))
                {
                    using JsonDocument doc = JsonDocument.Parse(body);
                    if (doc.RootElement.TryGetProperty("direction", out JsonElement dirEl))
                        direction = dirEl.GetString();
                }

                IoCaptureDecision decision;
                try
                {
                    decision = _gate.SetSelectedDirection(direction);
                }
                catch (ArgumentException ex)
                {
                    WriteJson(ctx.Response, 400, new { error = ex.Message });
                    return;
                }

                lock (_consoleLock)
                {
                    Console.WriteLine(
                        $"[{Timestamp()}] capture: UI ход → {_gate.SelectedWireValue} ({_gate.DescribeExpectedArm()})" +
                        (_gate.IsDirectionArmed ? ", уже armed по текущему DI2" : ", жду DI2"));
                    if (decision == IoCaptureDecision.None)
                        Console.WriteLine($"[{Timestamp()}] capture: ход без изменений");
                }

                WriteJson(ctx.Response, 200, new
                {
                    ok = true,
                    direction = _gate.SelectedWireValue,
                    source = "manual",
                    armed = _gate.IsDirectionArmed,
                    expect = _gate.DescribeExpectedArm()
                });
                return;
            }

            WriteJson(ctx.Response, 405, new { error = "method not allowed" });
        }
        catch (Exception ex)
        {
            try
            {
                WriteJson(ctx.Response, 500, new { error = ex.Message });
            }
            catch
            {
                // ignore
            }
        }
    }

    private void HandleCaptureDisarm(HttpListenerContext ctx)
    {
        if (_gate == null)
        {
            WriteJson(ctx.Response, 503, new { error = "capture gate disabled" });
            return;
        }

        if (!ctx.Request.HttpMethod.Equals("POST", StringComparison.OrdinalIgnoreCase)
            && !ctx.Request.HttpMethod.Equals("PUT", StringComparison.OrdinalIgnoreCase))
        {
            WriteJson(ctx.Response, 405, new { error = "method not allowed" });
            return;
        }

        IoCaptureDecision decision = _gate.Disarm();
        lock (_consoleLock)
        {
            Console.WriteLine(
                $"[{Timestamp()}] capture: HTTP disarm → armed={_gate.IsDirectionArmed} latched={_gate.IsDirectionLatched}" +
                (decision == IoCaptureDecision.None ? " (уже снято)" : ""));
        }

        WriteJson(ctx.Response, 200, new
        {
            ok = true,
            disarmed = decision == IoCaptureDecision.DirectionDisarmed || !_gate.IsDirectionArmed,
            armed = _gate.IsDirectionArmed,
            latched = _gate.IsDirectionLatched,
            expect = _gate.DescribeExpectedArm()
        });
    }

    private void HandleReject(HttpListenerContext ctx)
    {
        if (_session == null || _reject is not { Enabled: true })
        {
            WriteJson(ctx.Response, 503, new { error = "reject discrete disabled" });
            return;
        }

        if (!ctx.Request.HttpMethod.Equals("POST", StringComparison.OrdinalIgnoreCase)
            && !ctx.Request.HttpMethod.Equals("PUT", StringComparison.OrdinalIgnoreCase))
        {
            WriteJson(ctx.Response, 405, new { error = "method not allowed" });
            return;
        }

        string body = ReadBody(ctx.Request);
        int line;
        try
        {
            line = ParseRejectLine(body);
        }
        catch (ArgumentException ex)
        {
            WriteJson(ctx.Response, 400, new { error = ex.Message });
            return;
        }

        int doPort = line == 1 ? _reject.Line1OutputPort : _reject.Line2OutputPort;
        string plcInput = line == 1 ? _reject.Line1PlcInput : _reject.Line2PlcInput;
        bool lineEnabled = line == 1 ? _reject.Line1Enabled : _reject.Line2Enabled;

        // Брак на ПЛК — только DO линии 1/2 (обычно DO3/DO4). Ready/fault/capture сюда не входят.
        if (doPort is < 1 or > 8
            || doPort == _reject.ReadyOutputPort
            || doPort == _reject.FaultOutputPort)
        {
            WriteJson(ctx.Response, 400, new
            {
                error = $"reject line{line} must map to a dedicated DO (got DO{doPort})",
                line,
                do_port = doPort
            });
            return;
        }

        if (!lineEnabled)
        {
            WriteJson(ctx.Response, 200, new
            {
                ok = true,
                skipped = true,
                reason = $"line{line}_enabled=false",
                line,
                do_port = doPort,
                plc_input = plcInput
            });
            lock (_consoleLock)
            {
                Console.WriteLine(
                    $"[{Timestamp()}] reject line={line} DO{doPort}: skipped (line{line}_enabled=false)");
            }
            return;
        }

        int pulseMs = _reject.PulseDurationMs;

        // Не блокируем HTTP на Capture/COM: оркестратор ждёт ≤3 с и ловит timeout.
        // Импульс в фоне — ПЛК ack не ждём, просто DO pulse.
        lock (_consoleLock)
        {
            Console.WriteLine(
                $"[{Timestamp()}] reject line={line} DO{doPort}: queued async pulse → PLC {plcInput}");
        }

        WriteJson(ctx.Response, 200, new
        {
            ok = true,
            queued = true,
            line,
            do_port = doPort,
            plc_input = plcInput,
            pulse_ms = pulseMs,
            how = "async-pulse-queued"
        });

        _ = Task.Run(() =>
        {
            try
            {
                string how = FireRejectViaExecutor(doPort);
                lock (_consoleLock)
                {
                    Console.WriteLine(
                        $"[{Timestamp()}] reject line={line} DO{doPort} → PLC {plcInput} pulse {pulseMs} ms via {how}");
                }
            }
            catch (Exception ex)
            {
                lock (_consoleLock)
                {
                    Console.Error.WriteLine(
                        $"[{Timestamp()}] reject line={line} DO{doPort}: async FAIL {ex.Message}");
                }
            }
        });
    }

    private void HandleVisionLevel(HttpListenerContext ctx, string signal, int doPort, string plcInput)
    {
        if (_session == null || _reject is not { Enabled: true })
        {
            WriteJson(ctx.Response, 503, new { error = "plc discrete disabled" });
            return;
        }

        bool outputEnabled = signal switch
        {
            "vision_ready" => _reject.ReadyEnabled,
            "vision_fault" => _reject.FaultEnabled,
            _ => true
        };
        if (!outputEnabled)
        {
            WriteJson(ctx.Response, 200, new
            {
                ok = true,
                skipped = true,
                reason = $"{signal}_disabled — на ПЛК только DO{_reject.Line1OutputPort}/DO{_reject.Line2OutputPort} при браке",
                signal,
                do_port = doPort,
                plc_input = plcInput
            });
            lock (_consoleLock)
            {
                Console.WriteLine(
                    $"[{Timestamp()}] {signal} DO{doPort}: skipped ({signal} disabled — только reject DO{_reject.Line1OutputPort}/DO{_reject.Line2OutputPort})");
            }
            return;
        }

        if (!ctx.Request.HttpMethod.Equals("POST", StringComparison.OrdinalIgnoreCase)
            && !ctx.Request.HttpMethod.Equals("PUT", StringComparison.OrdinalIgnoreCase))
        {
            WriteJson(ctx.Response, 405, new { error = "method not allowed" });
            return;
        }

        bool value;
        try
        {
            value = ParseBoolValue(ReadBody(ctx.Request));
        }
        catch (ArgumentException ex)
        {
            WriteJson(ctx.Response, 400, new { error = ex.Message });
            return;
        }

        // vision_ready как vision_fault: value=true/false оба применяются (нет «всегда 1»).
        if (signal == "vision_ready")
            _visionReadyHold = value;

        // Уже держим HIGH — повторный SetDoLevel делает StopDo и даёт скачок на X4.
        if (signal == "vision_ready" && value && _visionReadyApplied)
        {
            lock (_consoleLock)
            {
                Console.WriteLine(
                    $"[{Timestamp()}] vision_ready DO{doPort}: already held HIGH — skip re-drive");
            }

            WriteJson(ctx.Response, 200, new
            {
                ok = true,
                queued = false,
                signal,
                value = true,
                held = true,
                do_port = doPort,
                plc_input = plcInput,
                how = "already-held"
            });
            return;
        }

        bool activeHigh = _reject.ActiveHigh;

        // Как reject: не блокируем HTTP и не делаем ReviveDi (ломает DI2/DI3).
        lock (_consoleLock)
        {
            Console.WriteLine(
                $"[{Timestamp()}] {signal} DO{doPort}: queued → PLC {plcInput} value={value}");
        }

        WriteJson(ctx.Response, 200, new
        {
            ok = true,
            queued = true,
            signal,
            value,
            do_port = doPort,
            plc_input = plcInput,
            how = "async-level-queued"
        });

        _ = Task.Run(() =>
        {
            try
            {
                string how;
                if (_doExecutor?.Arbiter is { } arbiter)
                {
                    try
                    {
                        how = arbiter.RunPlcAfterQuiet(
                            () => _session.SetDoLevel(doPort, value, activeHigh),
                            quietTimeoutMs: 2000,
                            runTimeoutMs: 3000);
                    }
                    catch (TimeoutException)
                    {
                        how = arbiter.Run(
                            IoMonitorArbiter.Domain.Plc,
                            () => _session.SetDoLevel(doPort, value, activeHigh),
                            timeoutMs: 3000);
                        how += "; forced-during-capture";
                    }
                }
                else
                {
                    how = _session.SetDoLevel(doPort, value, activeHigh);
                }

                lock (_consoleLock)
                {
                    Console.WriteLine(
                        $"[{Timestamp()}] {signal} DO{doPort} → PLC {plcInput} value={value} via {how}");
                }

                if (signal == "vision_ready")
                    _visionReadyApplied = value;
            }
            catch (Exception ex)
            {
                lock (_consoleLock)
                {
                    Console.Error.WriteLine(
                        $"[{Timestamp()}] {signal} DO{doPort}: async FAIL {ex.Message}");
                }
            }
        });
    }

    public static bool ParseBoolValue(string? body)
    {
        if (string.IsNullOrWhiteSpace(body))
            throw new ArgumentException("JSON body required: {\"value\":true|false}");

        using JsonDocument doc = JsonDocument.Parse(body);
        JsonElement root = doc.RootElement;
        if (root.TryGetProperty("value", out JsonElement valueEl))
            return ReadBool(valueEl);
        if (root.TryGetProperty("ready", out JsonElement readyEl))
            return ReadBool(readyEl);
        if (root.TryGetProperty("fault", out JsonElement faultEl))
            return ReadBool(faultEl);
        throw new ArgumentException("JSON body required: {\"value\":true|false}");
    }

    private static bool ReadBool(JsonElement el) =>
        el.ValueKind switch
        {
            JsonValueKind.True => true,
            JsonValueKind.False => false,
            JsonValueKind.Number => el.GetInt32() != 0,
            JsonValueKind.String => bool.TryParse(el.GetString(), out bool b)
                ? b
                : el.GetString() is "1" or "on" or "yes",
            _ => throw new ArgumentException("value must be boolean")
        };

    /// <summary>
    /// Зона Plc: тишина Capture (+cooldown) → быстрый board-exact reject.
    /// После успеха — soft cleanup (без SetInput); full revive только при DI/SDK аварии.
    /// </summary>
    private string FireRejectViaExecutor(int doPort)
    {
        if (_session == null || _reject == null)
            throw new InvalidOperationException("reject session not available");

        int pulseMs = Math.Clamp(_reject.PulseDurationMs, 1, 5000);
        bool activeHigh = _reject.ActiveHigh;
        int maxAttempts = Math.Clamp(_reject.PulseRetries, 1, 20);
        Exception? last = null;
        var arbiter = _doExecutor?.Arbiter;
        var sw = System.Diagnostics.Stopwatch.StartNew();

        for (int attempt = 1; attempt <= maxAttempts; attempt++)
        {
            try
            {
                string how;
                if (arbiter != null)
                {
                    try
                    {
                        how = arbiter.RunPlcAfterQuiet(
                            () => _session.FireRejectPulse(doPort, pulseMs, activeHigh),
                            quietTimeoutMs: 2500,
                            runTimeoutMs: 3000);
                    }
                    catch (TimeoutException)
                    {
                        // Capture долго занят — всё равно короткий импульс (не ждём ПЛК/тишину вечно).
                        lock (_consoleLock)
                        {
                            Console.WriteLine(
                                $"[{Timestamp()}] reject DO{doPort}: Capture busy >2.5s — force pulse");
                        }

                        how = arbiter.RunPlcForced(
                            () => _session.FireRejectPulse(doPort, pulseMs, activeHigh),
                            timeoutMs: 3000);
                        how += "; forced-during-capture";
                    }
                }
                else
                {
                    how = _session.FireRejectPulse(doPort, pulseMs, activeHigh);
                }

                return $"{how}; soft-cleanup; duration_ms={sw.ElapsedMilliseconds}";
            }
            catch (Exception ex)
            {
                last = Unwrap(ex);
                string msg = last?.Message ?? "";
                bool busy = msg.Contains("80000004", StringComparison.OrdinalIgnoreCase)
                    || msg.Contains("80000204", StringComparison.OrdinalIgnoreCase)
                    || msg.Contains("Capture window", StringComparison.OrdinalIgnoreCase)
                    || msg.Contains("busy", StringComparison.OrdinalIgnoreCase);
                bool diDead = msg.Contains("80000003", StringComparison.OrdinalIgnoreCase)
                    || msg.Contains("+save", StringComparison.OrdinalIgnoreCase);
                bool lastAttempt = attempt >= maxAttempts;

                // Full revive только если DI/SDK могли умереть — не на каждый busy.
                if (diDead || lastAttempt)
                {
                    try
                    {
                        RunOnDoExecutor(() =>
                        {
                            _session.ReviveDiAfterReject();
                            return "di-revive";
                        }, IoDoExecutor.Priority.Input);
                        lock (_consoleLock)
                        {
                            Console.WriteLine($"[{Timestamp()}] reject DO{doPort}: full DI revive after fail");
                        }
                    }
                    catch
                    {
                        // best-effort
                    }
                }

                if (!busy || lastAttempt)
                    break;

                Thread.Sleep(80 * attempt);
            }
        }

        throw new InvalidOperationException(
            $"DO{doPort} reject pulse failed after {sw.ElapsedMilliseconds} ms: {Unwrap(last)?.Message ?? "unknown"}");
    }

    /// <summary>Снова удержать DO1→X4 HIGH (fallback, если sticky в session ещё не запомнен).</summary>
    private string ReassertVisionReadyHold()
    {
        if (!_visionReadyHold || _session == null || _reject is not { Enabled: true, ReadyEnabled: true })
            return "";

        string sticky = _session.ReassertStickyReady();
        if (!string.IsNullOrEmpty(sticky) && sticky != "sticky-ready-fail")
            return sticky;

        int readyPort = _reject.ReadyOutputPort is >= 1 and <= 8 ? _reject.ReadyOutputPort : 1;
        bool activeHigh = _reject.ActiveHigh;
        try
        {
            string how;
            if (_doExecutor?.Arbiter is { } arbiter)
            {
                how = arbiter.Run(
                    IoMonitorArbiter.Domain.Plc,
                    () => _session.SetDoLevel(readyPort, active: true, activeHigh),
                    timeoutMs: 2000);
            }
            else
            {
                how = _session.SetDoLevel(readyPort, active: true, activeHigh);
            }

            return $"ready-rehold={how}";
        }
        catch (Exception ex)
        {
            lock (_consoleLock)
            {
                Console.Error.WriteLine(
                    $"[{Timestamp()}] vision_ready DO{readyPort}: re-hold FAIL {ex.Message}");
            }

            return "ready-rehold-fail";
        }
    }

    private static Exception? Unwrap(Exception? ex) =>
        ex is AggregateException ae ? ae.Flatten().InnerException ?? ae : ex;

    private void RestoreDiMonitor()
    {
        if (_session == null)
            return;
        try
        {
            RunOnDoExecutor(() =>
            {
                _session.ReviveDiAfterReject();
                return "di-revive";
            }, IoDoExecutor.Priority.Input);
        }
        catch
        {
            // best-effort
        }
    }

    private string RunOnDoExecutor(Func<string> action, IoDoExecutor.Priority priority = IoDoExecutor.Priority.Plc)
    {
        if (_doExecutor != null)
            return _doExecutor.Run(priority, action, timeoutMs: 10000);

        return action();
    }

    /// <summary>
    /// line: 1|2; group_id: 0|1; signal: reject_line_1|reject_line_2.
    /// </summary>
    public static int ParseRejectLine(string? body)
    {
        if (string.IsNullOrWhiteSpace(body))
            throw new ArgumentException("JSON body required: {\"line\":1|2} or {\"group_id\":0|1}");

        using JsonDocument doc = JsonDocument.Parse(body);
        JsonElement root = doc.RootElement;

        if (root.TryGetProperty("line", out JsonElement lineEl))
        {
            int line = lineEl.ValueKind == JsonValueKind.String
                ? int.Parse(lineEl.GetString() ?? "")
                : lineEl.GetInt32();
            if (line is 1 or 2)
                return line;
            throw new ArgumentException("line must be 1 or 2");
        }

        if (root.TryGetProperty("group_id", out JsonElement groupEl))
        {
            int group = groupEl.ValueKind == JsonValueKind.String
                ? int.Parse(groupEl.GetString() ?? "")
                : groupEl.GetInt32();
            if (group is 0 or 1)
                return group + 1;
            throw new ArgumentException("group_id must be 0 or 1");
        }

        if (root.TryGetProperty("signal", out JsonElement signalEl))
        {
            string? signal = signalEl.GetString()?.Trim().ToLowerInvariant();
            return signal switch
            {
                "reject_line_1" => 1,
                "reject_line_2" => 2,
                _ => throw new ArgumentException("signal must be reject_line_1 or reject_line_2")
            };
        }

        throw new ArgumentException("JSON body required: {\"line\":1|2} or {\"group_id\":0|1}");
    }

    private static string ReadBody(HttpListenerRequest request)
    {
        using var reader = new StreamReader(request.InputStream, request.ContentEncoding);
        return reader.ReadToEnd();
    }

    private static void WriteCors(HttpListenerResponse response)
    {
        response.Headers["Access-Control-Allow-Origin"] = "*";
        response.Headers["Access-Control-Allow-Methods"] = "GET, PUT, POST, OPTIONS";
        response.Headers["Access-Control-Allow-Headers"] = "Content-Type";
    }

    private static void WriteJson(HttpListenerResponse response, int status, object payload)
    {
        byte[] bytes = Encoding.UTF8.GetBytes(JsonSerializer.Serialize(payload));
        response.StatusCode = status;
        response.ContentType = "application/json; charset=utf-8";
        response.ContentLength64 = bytes.Length;
        response.OutputStream.Write(bytes, 0, bytes.Length);
        response.Close();
    }

    private static string Timestamp() => DateTime.Now.ToString("HH:mm:ss.fff");

    public void Dispose()
    {
        if (_disposed)
            return;

        _disposed = true;
        _cts.Cancel();
        try
        {
            _listener.Stop();
            _listener.Close();
        }
        catch
        {
            // ignore
        }

        try
        {
            _loop.Wait(TimeSpan.FromSeconds(2));
        }
        catch
        {
            // ignore
        }

        _cts.Dispose();
    }
}

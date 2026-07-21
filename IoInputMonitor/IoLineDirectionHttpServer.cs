using System.Net;
using System.Text;
using System.Text.Json;

namespace IoInputMonitor;

/// <summary>
/// HTTP API IoInputMonitor:
/// GET/PUT /line-direction — ход линии;
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
    private readonly object _rejectPulseLock = new();
    private bool _disposed;

    private IoLineDirectionHttpServer(
        IoCaptureGate? gate,
        IoBoxSession? session,
        IoRejectOptions? reject,
        string prefix,
        object consoleLock)
    {
        _gate = gate;
        _session = session;
        _reject = reject;
        _consoleLock = consoleLock;
        _listener = new HttpListener();
        _listener.Prefixes.Add(prefix);
        _listener.Start();
        _loop = Task.Run(ListenLoopAsync);
        var routes = new List<string>();
        if (_gate != null)
            routes.Add("line-direction (GET/PUT)");
        if (_reject is { Enabled: true } && _session != null)
            routes.Add("reject (POST), vision-ready/vision-fault (PUT)");
        Console.WriteLine($"IO control HTTP ← {prefix}{string.Join(", ", routes)}");
    }

    public static IoLineDirectionHttpServer? TryStart(
        IoCaptureGate? gate,
        IoDirectionHttpOptions? directionHttp,
        object consoleLock,
        IoBoxSession? session = null,
        IoRejectOptions? reject = null)
    {
        bool directionEnabled = gate != null && directionHttp is { Enabled: true };
        bool rejectEnabled = session != null && reject is { Enabled: true };
        if (!directionEnabled && !rejectEnabled)
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
                directionEnabled ? gate : null,
                rejectEnabled ? session : null,
                rejectEnabled ? reject : null,
                prefix,
                consoleLock);
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

            if (path is "/vision-ready" or "/api/client/vision-ready")
            {
                HandleVisionLevel(ctx, "vision_ready", _reject?.ReadyOutputPort ?? 1, "X4");
                return;
            }

            if (path is "/vision-fault" or "/api/client/vision-fault")
            {
                HandleVisionLevel(ctx, "vision_fault", _reject?.FaultOutputPort ?? 2, "X5");
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
        string plcInput = line == 1 ? "X6" : "X7";
        string how;
        try
        {
            lock (_rejectPulseLock)
            {
                // Level pulse only — board-exact SetOutput can leave DO high and freeze PLC reject.
                how = _session.FireDoLevelPulse(doPort, _reject.PulseDurationMs, _reject.ActiveHigh);
            }
        }
        catch (Exception ex)
        {
            lock (_consoleLock)
            {
                Console.Error.WriteLine($"[{Timestamp()}] reject line={line} DO{doPort}: FAIL {ex.Message}");
            }
            WriteJson(ctx.Response, 500, new { error = ex.Message, line, do_port = doPort });
            return;
        }

        lock (_consoleLock)
        {
            Console.WriteLine(
                $"[{Timestamp()}] reject line={line} DO{doPort} → PLC {plcInput} pulse {_reject.PulseDurationMs} ms via {how}");
        }

        WriteJson(ctx.Response, 200, new
        {
            ok = true,
            line,
            do_port = doPort,
            plc_input = plcInput,
            pulse_ms = _reject.PulseDurationMs,
            how
        });
    }

    private void HandleVisionLevel(HttpListenerContext ctx, string signal, int doPort, string plcInput)
    {
        if (_session == null || _reject is not { Enabled: true })
        {
            WriteJson(ctx.Response, 503, new { error = "plc discrete disabled" });
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

        string how;
        try
        {
            lock (_rejectPulseLock)
            {
                how = _session.SetDoLevel(doPort, value, _reject.ActiveHigh);
            }
        }
        catch (Exception ex)
        {
            lock (_consoleLock)
            {
                Console.Error.WriteLine($"[{Timestamp()}] {signal} DO{doPort}: FAIL {ex.Message}");
            }
            WriteJson(ctx.Response, 500, new { error = ex.Message, signal, do_port = doPort });
            return;
        }

        lock (_consoleLock)
        {
            Console.WriteLine(
                $"[{Timestamp()}] {signal} DO{doPort} → PLC {plcInput} value={value} via {how}");
        }

        WriteJson(ctx.Response, 200, new
        {
            ok = true,
            signal,
            value,
            do_port = doPort,
            plc_input = plcInput,
            how
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

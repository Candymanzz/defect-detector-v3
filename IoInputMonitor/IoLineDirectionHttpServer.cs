using System.Net;
using System.Text;
using System.Text.Json;

namespace IoInputMonitor;

/// <summary>
/// HTTP API хода линии для UI/оркестратора:
/// GET/PUT http://host:port/line-direction  {"direction":"forward"|"reverse"}
/// </summary>
internal sealed class IoLineDirectionHttpServer : IDisposable
{
    private readonly IoCaptureGate _gate;
    private readonly HttpListener _listener;
    private readonly CancellationTokenSource _cts = new();
    private readonly Task _loop;
    private readonly object _consoleLock;
    private bool _disposed;

    private IoLineDirectionHttpServer(IoCaptureGate gate, string prefix, object consoleLock)
    {
        _gate = gate;
        _consoleLock = consoleLock;
        _listener = new HttpListener();
        _listener.Prefixes.Add(prefix);
        _listener.Start();
        _loop = Task.Run(ListenLoopAsync);
        Console.WriteLine($"Direction HTTP ← {prefix}line-direction (GET/PUT)");
    }

    public static IoLineDirectionHttpServer? TryStart(
        IoCaptureGate? gate,
        IoDirectionHttpOptions? options,
        object consoleLock)
    {
        if (gate == null || options is not { Enabled: true })
            return null;

        if (options.Port is < 1 or > 65535)
            throw new ArgumentOutOfRangeException(nameof(options.Port), "HTTP port должен быть 1..65535.");

        string host = string.IsNullOrWhiteSpace(options.Host) ? "127.0.0.1" : options.Host.Trim();
        string prefix = $"http://{host}:{options.Port}/";
        try
        {
            return new IoLineDirectionHttpServer(gate, prefix, consoleLock);
        }
        catch (Exception ex)
        {
            Console.Error.WriteLine($"Direction HTTP не стартовал ({prefix}): {ex.Message}");
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
                Console.Error.WriteLine($"[{Timestamp()}] direction HTTP accept: {ex.Message}");
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

            if (path is not "/line-direction" and not "/api/client/line-direction")
            {
                WriteJson(ctx.Response, 404, new { error = "not found" });
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

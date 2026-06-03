using System.Text;

namespace LightServer.Logging;

/// <summary>Логирует тело запроса и ответа для API (в тот же файл, что и остальные логи).</summary>
public sealed class HttpExchangeLoggingMiddleware
{
    private const int MaxBodyChars = 16_384;
    private readonly RequestDelegate _next;
    private readonly ILogger<HttpExchangeLoggingMiddleware> _log;

    public HttpExchangeLoggingMiddleware(RequestDelegate next, ILogger<HttpExchangeLoggingMiddleware> log)
    {
        _next = next;
        _log = log;
    }

    public async Task InvokeAsync(HttpContext context)
    {
        if (!ShouldLog(context.Request.Path))
        {
            await _next(context);
            return;
        }

        string method = context.Request.Method;
        string path = context.Request.Path.Value ?? "/";
        string query = context.Request.QueryString.HasValue ? context.Request.QueryString.Value! : "";

        string requestBody = await ReadRequestBodyAsync(context.Request);

        Stream originalResponseBody = context.Response.Body;
        await using var responseBuffer = new MemoryStream();
        context.Response.Body = responseBuffer;

        try
        {
            await _next(context);
        }
        finally
        {
            responseBuffer.Seek(0, SeekOrigin.Begin);
            string responseBody = await ReadStreamAsStringAsync(responseBuffer);
            responseBuffer.Seek(0, SeekOrigin.Begin);
            await responseBuffer.CopyToAsync(originalResponseBody);
            context.Response.Body = originalResponseBody;

            _log.LogInformation(
                "HTTP {Method} {Path}{Query} => {StatusCode}\n  >> Request: {RequestBody}\n  << Response: {ResponseBody}",
                method,
                path,
                query,
                context.Response.StatusCode,
                FormatBody(requestBody),
                FormatBody(responseBody));
        }
    }

    private static bool ShouldLog(PathString path) =>
        path.StartsWithSegments("/api", StringComparison.OrdinalIgnoreCase);

    private static async Task<string> ReadRequestBodyAsync(HttpRequest request)
    {
        if (request.ContentLength is 0 or null && !request.Headers.ContainsKey("Transfer-Encoding"))
            return "";

        request.EnableBuffering();
        request.Body.Position = 0;
        using var reader = new StreamReader(request.Body, Encoding.UTF8, detectEncodingFromByteOrderMarks: false, leaveOpen: true);
        string body = await reader.ReadToEndAsync();
        request.Body.Position = 0;
        return body;
    }

    private static async Task<string> ReadStreamAsStringAsync(Stream stream)
    {
        if (stream.Length == 0)
            return "";

        using var reader = new StreamReader(stream, Encoding.UTF8, detectEncodingFromByteOrderMarks: false, leaveOpen: true);
        return await reader.ReadToEndAsync();
    }

    private static string FormatBody(string body)
    {
        if (string.IsNullOrWhiteSpace(body))
            return "(empty)";

        string trimmed = body.Trim();
        if (trimmed.Length <= MaxBodyChars)
            return trimmed;

        return trimmed[..MaxBodyChars] + $"... (+{trimmed.Length - MaxBodyChars} chars)";
    }
}

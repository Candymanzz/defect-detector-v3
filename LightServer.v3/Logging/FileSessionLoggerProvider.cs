using System.Text;
using Microsoft.Extensions.Configuration;
using Microsoft.Extensions.Logging;

namespace LightServer.Logging;

/// <summary>Один файл на запуск приложения: logs/yyyy-MM-dd_HH-mm-ss.log</summary>
public sealed class FileSessionLoggerProvider : ILoggerProvider
{
    private readonly StreamWriter _writer;
    private readonly object _writeLock = new();
    private readonly IConfiguration _configuration;
    private bool _disposed;

    public string FilePath { get; }

    public FileSessionLoggerProvider(string filePath, IConfiguration configuration)
    {
        FilePath = filePath;
        _configuration = configuration;
        string? dir = Path.GetDirectoryName(filePath);
        if (!string.IsNullOrEmpty(dir))
            Directory.CreateDirectory(dir);

        _writer = new StreamWriter(filePath, append: true, Encoding.UTF8) { AutoFlush = true };
        _writer.WriteLine($"--- LightServer log started {DateTime.Now:yyyy-MM-dd HH:mm:ss} ---");
    }

    public ILogger CreateLogger(string categoryName) =>
        new FileSessionLogger(categoryName, _writer, _writeLock, ResolveMinLevel(categoryName));

    public void Dispose()
    {
        if (_disposed)
            return;

        _disposed = true;
        lock (_writeLock)
        {
            _writer.WriteLine($"--- LightServer log ended {DateTime.Now:yyyy-MM-dd HH:mm:ss} ---");
            _writer.Dispose();
        }
    }

    private LogLevel ResolveMinLevel(string category)
    {
        const string prefix = "Logging:LogLevel:";
        for (string? key = category; key != null; key = TrimCategory(key))
        {
            string? value = _configuration[prefix + key];
            if (!string.IsNullOrEmpty(value) && Enum.TryParse(value, ignoreCase: true, out LogLevel level))
                return level;
        }

        string? defaultLevel = _configuration[prefix + "Default"];
        if (!string.IsNullOrEmpty(defaultLevel) && Enum.TryParse(defaultLevel, ignoreCase: true, out LogLevel parsed))
            return parsed;

        return LogLevel.Information;
    }

    private static string? TrimCategory(string category)
    {
        int lastDot = category.LastIndexOf('.');
        return lastDot > 0 ? category[..lastDot] : null;
    }

    private sealed class FileSessionLogger : ILogger
    {
        private readonly string _category;
        private readonly StreamWriter _writer;
        private readonly object _writeLock;
        private readonly LogLevel _minLevel;

        public FileSessionLogger(string category, StreamWriter writer, object writeLock, LogLevel minLevel)
        {
            _category = category;
            _writer = writer;
            _writeLock = writeLock;
            _minLevel = minLevel;
        }

        public IDisposable? BeginScope<TState>(TState state) where TState : notnull => null;

        public bool IsEnabled(LogLevel logLevel) => logLevel >= _minLevel && logLevel != LogLevel.None;

        public void Log<TState>(
            LogLevel logLevel,
            EventId eventId,
            TState state,
            Exception? exception,
            Func<TState, Exception?, string> formatter)
        {
            if (!IsEnabled(logLevel))
                return;

            string message = formatter(state, exception);
            var line = new StringBuilder()
                .Append(DateTime.Now.ToString("yyyy-MM-dd HH:mm:ss.fff"))
                .Append(" [")
                .Append(logLevel)
                .Append("] ")
                .Append(_category)
                .Append(": ")
                .Append(message);

            if (exception != null)
            {
                line.AppendLine();
                line.Append(exception);
            }

            lock (_writeLock)
                _writer.WriteLine(line);
        }
    }
}

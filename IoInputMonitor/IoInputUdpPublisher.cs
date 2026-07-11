using System.Net;
using System.Net.Sockets;
using System.Text;
using System.Text.Json;
using System.Threading.Channels;

namespace IoInputMonitor;

public enum IoInputUdpPayloadFormat
{
    /// <summary>Legacy: один байт 0/1 без номера DI.</summary>
    Byte,

    /// <summary>Legacy: текст "0"/"1" без номера DI.</summary>
    Text,

    /// <summary>JSON: {"di":3,"value":1} — value 1=замкнуто, 0=разомкнуто.</summary>
    Json,

    /// <summary>Текст: "3:1" — di:value.</summary>
    TextDi,

    /// <summary>Два байта: [di, value].</summary>
    ByteDi
}

public sealed class IoInputUdpPublishOptions
{
    public bool Enabled { get; set; }

    public string Host { get; set; } = "127.0.0.1";

    public int Port { get; set; } = 9100;

    public IoInputUdpPayloadFormat Format { get; set; } = IoInputUdpPayloadFormat.Json;

    public int[] PublishInputs { get; set; } = [];

    public bool SendInitialState { get; set; }

    /// <summary>DI-порт триггера съёмки (обычно 3). Отправляется без очереди, пакеты не отбрасываются.</summary>
    public int TriggerPort { get; set; } = 3;

    /// <summary>
    /// Синхронный UDP для всех publish.inputs из SDK callback (DI1/DI2/DI3 без очереди).
    /// false — все DI идут через очередь (legacy).
    /// </summary>
    public bool LowLatencyTrigger { get; set; } = true;

    /// <summary>Не слать начальное состояние trigger_port (избегает ложного FIRE при старте).</summary>
    public bool SendInitialTriggerState { get; set; }
}

internal readonly record struct IoInputStateChange(int Port, bool Closed);

internal sealed class IoInputUdpPublisher : IDisposable
{
    private readonly IoInputUdpPublishOptions _options;
    private readonly HashSet<int> _publishPorts;
    private readonly Channel<IoInputStateChange> _channel;
    private readonly CancellationTokenSource _cts = new();
    private readonly Task _worker;
    private readonly object _syncSendLock = new();
    private UdpClient? _client;
    private UdpClient? _syncClient;
    private IPEndPoint? _remote;
    private bool _disposed;

    private IoInputUdpPublisher(IoInputUdpPublishOptions options, IEnumerable<int> defaultInputs)
    {
        _options = options;
        int[] ports = options.PublishInputs.Length > 0 ? options.PublishInputs : defaultInputs.ToArray();
        _publishPorts = new HashSet<int>(ports);
        _channel = Channel.CreateBounded<IoInputStateChange>(new BoundedChannelOptions(256)
        {
            FullMode = BoundedChannelFullMode.DropOldest,
            SingleReader = true,
            SingleWriter = false
        });
        _worker = Task.Run(RunAsync);
    }

    public static IoInputUdpPublisher? TryCreate(IoInputUdpPublishOptions? options, int[] inputPorts)
    {
        if (options is not { Enabled: true })
            return null;

        if (options.Port is < 1 or > 65535)
            throw new ArgumentOutOfRangeException(nameof(options.Port), "UDP port должен быть 1..65535.");

        return new IoInputUdpPublisher(options, inputPorts);
    }

    public void Publish(int port, bool closed)
    {
        if (!_publishPorts.Contains(port))
            return;

        if (IsImmediatePublishPort(port))
        {
            SendImmediate(port, closed);
            return;
        }

        if (!_channel.Writer.TryWrite(new IoInputStateChange(port, closed)))
        {
            Console.Error.WriteLine(
                $"[{Timestamp()}] UDP очередь переполнена, пропуск DI{port}={(closed ? 1 : 0)}");
        }
    }

    private bool IsImmediatePublishPort(int port) =>
        _options.LowLatencyTrigger && _publishPorts.Contains(port);

    private void SendImmediate(int port, bool closed)
    {
        byte[] payload = BuildPayload(port, closed, includeTimestamp: true);
        lock (_syncSendLock)
        {
            try
            {
                EnsureSyncClient();
                _syncClient!.Send(payload, payload.Length, _remote!);
            }
            catch (Exception ex)
            {
                Console.Error.WriteLine(
                    $"[{Timestamp()}] UDP immediate send failed DI{port}={(closed ? 1 : 0)}: {ex.Message}");
            }
        }
    }

    private void EnsureSyncClient()
    {
        if (_syncClient != null)
            return;

        _remote ??= new IPEndPoint(IPAddress.Parse(_options.Host), _options.Port);
        _syncClient = new UdpClient();
    }

    private async Task RunAsync()
    {
        IPEndPoint remote = new(IPAddress.Parse(_options.Host), _options.Port);
        lock (_syncSendLock)
        {
            _remote = remote;
        }

        try
        {
            _client = new UdpClient();
            string triggerMode = _options.LowLatencyTrigger
                ? $"immediate DI [{string.Join(", ", _publishPorts.OrderBy(static p => p))}]"
                : "queued";
            Console.WriteLine(
                $"UDP publish → {_options.Host}:{_options.Port} format={FormatDescription(_options.Format)} ({triggerMode})");

            await foreach (IoInputStateChange change in _channel.Reader.ReadAllAsync(_cts.Token))
            {
                byte[] payload = BuildPayload(change.Port, change.Closed);
                try
                {
                    await _client.SendAsync(payload, remote, _cts.Token);
                }
                catch (Exception ex) when (ex is not OperationCanceledException)
                {
                    Console.Error.WriteLine(
                        $"[{Timestamp()}] UDP publish failed DI{change.Port}={(change.Closed ? 1 : 0)}: {ex.Message}");
                }
            }
        }
        catch (OperationCanceledException)
        {
            // shutdown
        }
        catch (Exception ex)
        {
            Console.Error.WriteLine($"[{Timestamp()}] UDP publisher stopped: {ex.Message}");
        }
    }

    internal static byte[] BuildPayload(
        IoInputUdpPayloadFormat format,
        int diPort,
        bool closed,
        bool includeTimestamp = false)
    {
        int value = closed ? 1 : 0;
        return format switch
        {
            IoInputUdpPayloadFormat.Text => Encoding.UTF8.GetBytes(value.ToString()),
            IoInputUdpPayloadFormat.Byte => [(byte)value],
            IoInputUdpPayloadFormat.Json when includeTimestamp => Encoding.UTF8.GetBytes(
                JsonSerializer.Serialize(new { di = diPort, value, ts_ms = Environment.TickCount64 })),
            IoInputUdpPayloadFormat.Json => Encoding.UTF8.GetBytes(
                JsonSerializer.Serialize(new { di = diPort, value })),
            IoInputUdpPayloadFormat.TextDi => Encoding.UTF8.GetBytes($"{diPort}:{value}"),
            IoInputUdpPayloadFormat.ByteDi =>
            [
                (byte)diPort,
                (byte)value
            ],
            _ => includeTimestamp
                ? Encoding.UTF8.GetBytes(JsonSerializer.Serialize(new { di = diPort, value, ts_ms = Environment.TickCount64 }))
                : Encoding.UTF8.GetBytes(JsonSerializer.Serialize(new { di = diPort, value }))
        };
    }

    private byte[] BuildPayload(int diPort, bool closed, bool includeTimestamp = false) =>
        BuildPayload(_options.Format, diPort, closed, includeTimestamp);

    private static string FormatDescription(IoInputUdpPayloadFormat format) => format switch
    {
        IoInputUdpPayloadFormat.Byte => "byte (legacy 0/1)",
        IoInputUdpPayloadFormat.Text => "text (legacy \"0\"/\"1\")",
        IoInputUdpPayloadFormat.Json => "json {\"di\":N,\"value\":0|1}",
        IoInputUdpPayloadFormat.TextDi => "text_di \"N:0|1\"",
        IoInputUdpPayloadFormat.ByteDi => "byte_di [di,value]",
        _ => format.ToString().ToLowerInvariant()
    };

    private static string Timestamp() =>
        DateTime.Now.ToString("HH:mm:ss.fff");

    public void Dispose()
    {
        if (_disposed)
            return;

        _disposed = true;
        _channel.Writer.TryComplete();
        _cts.Cancel();
        try
        {
            _worker.Wait(TimeSpan.FromSeconds(2));
        }
        catch
        {
            // ignore on shutdown
        }

        _client?.Dispose();
        _syncClient?.Dispose();
        _cts.Dispose();
    }
}

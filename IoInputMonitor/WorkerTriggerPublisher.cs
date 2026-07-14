using System.Net;
using System.Net.Sockets;
using System.Text;

namespace IoInputMonitor;

public sealed class WorkerTriggerPublishOptions
{
    public bool Enabled { get; set; }

    public string Host { get; set; } = "127.0.0.1";

    /// <summary>Порт воркера = port_base + camera_id (extern_trigger_udp_port_base в config.json).</summary>
    public int PortBase { get; set; } = 9210;

    public int[] CameraIds { get; set; } = [0, 1, 2, 3, 4, 5, 6, 7, 8, 9];

    public bool RequireDirectionHigh { get; set; } = true;

    public int DirectionPort { get; set; } = 2;

    public int TriggerPort { get; set; } = 3;
}

/// <summary>
/// DI3↑ при DI2=1 → UDP TRIG сразу в camera-worker (минуя IPC оркестратора).
/// Оркестратору DI по-прежнему уходит через IoInputUdpPublisher.
/// </summary>
internal sealed class WorkerTriggerPublisher : IDisposable
{
    private static readonly byte[] TriggerPayload = Encoding.ASCII.GetBytes("TRIG");

    private readonly WorkerTriggerPublishOptions _options;
    private readonly object _sendLock = new();
    private readonly IPEndPoint[] _endpoints;
    private UdpClient? _client;
    private bool _disposed;

    private WorkerTriggerPublisher(WorkerTriggerPublishOptions options)
    {
        _options = options;
        IPAddress host = IPAddress.Parse(options.Host);
        _endpoints = options.CameraIds
            .Select(id => new IPEndPoint(host, options.PortBase + id))
            .ToArray();
    }

    public static WorkerTriggerPublisher? TryCreate(WorkerTriggerPublishOptions? options)
    {
        if (options is not { Enabled: true })
            return null;

        if (options.PortBase is < 1 or > 65535)
            throw new ArgumentOutOfRangeException(nameof(options.PortBase), "port_base должен быть 1..65535.");

        if (options.CameraIds.Length == 0)
            throw new ArgumentException("camera_ids пуст.");

        var publisher = new WorkerTriggerPublisher(options);
        Console.WriteLine(
            $"Worker TRIG → {options.Host}:{options.PortBase}+id cams=[{string.Join(",", options.CameraIds)}] " +
            $"(DI{options.TriggerPort}↑ при DI{options.DirectionPort}=1)");
        return publisher;
    }

    public void FireAll()
    {
        lock (_sendLock)
        {
            EnsureClient();
            foreach (IPEndPoint ep in _endpoints)
            {
                try
                {
                    _client!.Send(TriggerPayload, TriggerPayload.Length, ep);
                }
                catch (Exception ex)
                {
                    Console.Error.WriteLine($"[{Timestamp()}] worker TRIG failed {ep}: {ex.Message}");
                }
            }
        }
    }

    private void EnsureClient() => _client ??= new UdpClient();

    private static string Timestamp() => DateTime.Now.ToString("HH:mm:ss.fff");

    public void Dispose()
    {
        if (_disposed)
            return;
        _disposed = true;
        _client?.Dispose();
    }
}

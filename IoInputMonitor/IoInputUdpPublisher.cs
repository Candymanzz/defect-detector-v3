using System.Net;
using System.Net.Sockets;
using System.Text;
using System.Threading.Channels;

namespace IoInputMonitor;

public enum IoInputUdpPayloadFormat
{
    Byte,
    Text
}

public sealed class IoInputUdpPublishOptions
{
    public bool Enabled { get; set; }

    public string Host { get; set; } = "127.0.0.1";

    public int Port { get; set; } = 9100;

    public IoInputUdpPayloadFormat Format { get; set; } = IoInputUdpPayloadFormat.Byte;

    public int[] PublishInputs { get; set; } = [];

    public bool SendInitialState { get; set; }
}

internal readonly record struct IoInputStateChange(int Port, bool Closed);

internal sealed class IoInputUdpPublisher : IDisposable
{
    private readonly IoInputUdpPublishOptions _options;
    private readonly HashSet<int> _publishPorts;
    private readonly Channel<IoInputStateChange> _channel;
    private readonly CancellationTokenSource _cts = new();
    private readonly Task _worker;
    private UdpClient? _client;

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

        if (!_channel.Writer.TryWrite(new IoInputStateChange(port, closed)))
        {
            Console.Error.WriteLine(
                $"[{Timestamp()}] UDP очередь переполнена, пропуск DI{port}={(closed ? 1 : 0)}");
        }
    }

    private async Task RunAsync()
    {
        IPEndPoint remote = new(IPAddress.Parse(_options.Host), _options.Port);
        try
        {
            _client = new UdpClient();
            Console.WriteLine(
                $"UDP publish → {_options.Host}:{_options.Port} " +
                $"format={_options.Format.ToString().ToLowerInvariant()} " +
                $"(1=замкнуто/HIGH, 0=разомкнуто/LOW)");

            await foreach (IoInputStateChange change in _channel.Reader.ReadAllAsync(_cts.Token))
            {
                byte[] payload = BuildPayload(change.Closed);
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

    private byte[] BuildPayload(bool closed) =>
        _options.Format switch
        {
            IoInputUdpPayloadFormat.Text => Encoding.UTF8.GetBytes(closed ? "1" : "0"),
            _ => [(byte)(closed ? 1 : 0)]
        };

    private static string Timestamp() =>
        DateTime.Now.ToString("HH:mm:ss.fff");

    public void Dispose()
    {
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
        _cts.Dispose();
    }
}

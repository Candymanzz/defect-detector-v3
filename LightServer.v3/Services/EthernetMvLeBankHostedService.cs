using System.Diagnostics;
using LightServer;

namespace LightServer.Services;

/// <summary>Фоновая инициализация Ethernet MV-LE банка (постоянные GigE-сессии).</summary>
public sealed class EthernetMvLeBankHostedService : IHostedService
{
    private readonly EthernetMvLeBank _bank;
    private readonly LightHardwareRegistry _hardware;

    public EthernetMvLeBankHostedService(EthernetMvLeBank bank, LightHardwareRegistry hardware)
    {
        _bank = bank;
        _hardware = hardware;
    }

    public Task StartAsync(CancellationToken cancellationToken)
    {
        _hardware.EnsureFresh();
        int ethCount = _hardware.Options.Devices.Count(static d => d.Enabled && d.IsEthernet);
        if (ethCount == 0)
            return Task.CompletedTask;

        Console.WriteLine($"[LightServer] Инициализация Ethernet MV-LE банка в фоне ({ethCount} устройств)…");
        _ = Task.Run(() =>
        {
            try
            {
                var sw = Stopwatch.StartNew();
                var (ok, err) = _bank.EnsureInitialized();
                if (ok)
                    Console.WriteLine(
                        $"[LightServer] Ethernet bank готов за {sw.ElapsedMilliseconds} ms (ready={_bank.ReadyCount})");
                else
                    Console.WriteLine($"[LightServer] Ethernet bank не инициализирован: {err}");
            }
            catch (Exception ex)
            {
                Console.WriteLine($"[LightServer] Ethernet bank: исключение: {ex.Message}");
            }
        }, cancellationToken);

        return Task.CompletedTask;
    }

    public Task StopAsync(CancellationToken cancellationToken)
    {
        _bank.Dispose();
        return Task.CompletedTask;
    }
}

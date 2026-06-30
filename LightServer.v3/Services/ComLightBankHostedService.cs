using System.Diagnostics;
using Microsoft.Extensions.Options;

namespace LightServer.Services;

public sealed class ComLightBankHostedService : IHostedService
{
    private readonly ComLightBankService _bank;
    private readonly ComLightDevicesOptions _options;

    public ComLightBankHostedService(ComLightBankService bank, IOptions<ComLightDevicesOptions> options)
    {
        _bank = bank;
        _options = options.Value;
    }

    public Task StartAsync(CancellationToken cancellationToken)
    {
        if (!_options.InitializeOnStartup)
            return Task.CompletedTask;

        // В фоне: синхронный EnumDevices/Open блокировал Kestrel — казалось, что «Building…» завис.
        int deviceCount = _options.Devices.Count(static d => !string.IsNullOrWhiteSpace(d.ComPort));
        Console.WriteLine($"[LightServer] Инициализация COM-банка в фоне ({deviceCount} порт(ов))…");
        _ = Task.Run(() =>
        {
            try
            {
                var sw = Stopwatch.StartNew();
                (bool ok, string? err) = _bank.EnsureInitialized();
                if (ok)
                    Console.WriteLine($"[LightServer] COM-банк готов за {sw.ElapsedMilliseconds} ms");
                else
                    Console.WriteLine($"[LightServer] COM-банк не инициализирован: {err}");
            }
            catch (Exception ex)
            {
                Console.WriteLine($"[LightServer] COM-банк: исключение при инициализации: {ex.Message}");
            }
        }, cancellationToken);

        return Task.CompletedTask;
    }

    public Task StopAsync(CancellationToken cancellationToken) => Task.CompletedTask;
}

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

        // Синхронно: без гонки с первым POST (Task.Run + SDK lock давали «вечный» off).
        _bank.EnsureInitialized();
        return Task.CompletedTask;
    }

    public Task StopAsync(CancellationToken cancellationToken) => Task.CompletedTask;
}

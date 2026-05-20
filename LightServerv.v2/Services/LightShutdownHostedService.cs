using LightServer.Models;
using Microsoft.Extensions.Configuration;

namespace LightServer.Services;

/// <summary>При остановке хоста — выключить MV-LE (иначе каналы остаются включёнными).</summary>
public sealed class LightShutdownHostedService : IHostedService
{
    private readonly LightControlService _light;
    private readonly IConfiguration _configuration;
    private readonly ILogger<LightShutdownHostedService> _logger;

    public LightShutdownHostedService(
        LightControlService light,
        IConfiguration configuration,
        ILogger<LightShutdownHostedService> logger)
    {
        _light = light;
        _configuration = configuration;
        _logger = logger;
    }

    public Task StartAsync(CancellationToken cancellationToken) => Task.CompletedTask;

    public Task StopAsync(CancellationToken cancellationToken)
    {
        int deviceIndex = _configuration.GetValue("LightSettings:DeviceIndex", 1);
        int[] channels = _configuration.GetSection("LightSettings:ShutdownChannels").Get<int[]>()
                         ?? [1, 2, 3, 4];

        var request = new LightCommandRequest
        {
            DeviceIndex = deviceIndex,
            LightControllerSource = "Off",
            Channels = channels
        };
        var (ok, error) = _light.SetLight(request);
        if (ok)
            _logger.LogInformation("MV-LE выключен при остановке (device={Device}, channels=[{Channels}])",
                deviceIndex, string.Join(',', channels));
        else
            _logger.LogWarning("Не удалось выключить MV-LE при остановке: {Error}", error);
        return Task.CompletedTask;
    }
}

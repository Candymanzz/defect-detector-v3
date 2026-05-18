using MvCameraControl;

namespace LightServer.Services;

public sealed class MvsSdkLifetime : IHostedService
{
    public Task StartAsync(CancellationToken cancellationToken)
    {
        SDKSystem.Initialize();
        return Task.CompletedTask;
    }

    public Task StopAsync(CancellationToken cancellationToken)
    {
        SDKSystem.Finalize();
        return Task.CompletedTask;
    }
}

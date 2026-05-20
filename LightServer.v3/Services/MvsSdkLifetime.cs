using MvCameraControl;

namespace LightServer.Services;

public sealed class MvsSdkLifetime : IHostedService
{
    private readonly IoControllerComService _ioCom;

    public MvsSdkLifetime(IoControllerComService ioCom) => _ioCom = ioCom;

    public Task StartAsync(CancellationToken cancellationToken)
    {
        SDKSystem.Initialize();
        return Task.CompletedTask;
    }

    public Task StopAsync(CancellationToken cancellationToken)
    {
        _ioCom.Dispose();
        SDKSystem.Finalize();
        return Task.CompletedTask;
    }
}

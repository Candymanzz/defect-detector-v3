using MvCameraControl;

namespace LightServer.Services;

public sealed class MvsSdkLifetime : IHostedService
{
    private readonly IoControllerComService _ioCom;
    private readonly MvLeSerialLightSessions _mvLeSessions;

    public MvsSdkLifetime(IoControllerComService ioCom, MvLeSerialLightSessions mvLeSessions)
    {
        _ioCom = ioCom;
        _mvLeSessions = mvLeSessions;
    }

    public Task StartAsync(CancellationToken cancellationToken)
    {
        SDKSystem.Initialize();
        return Task.CompletedTask;
    }

    public Task StopAsync(CancellationToken cancellationToken)
    {
        _mvLeSessions.Dispose();
        _ioCom.Dispose();
        SDKSystem.Finalize();
        return Task.CompletedTask;
    }
}

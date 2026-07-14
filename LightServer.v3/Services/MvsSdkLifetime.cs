using MvCameraControl;

namespace LightServer.Services;

/// <summary>SDKSystem.Initialize при старте; при остановке — закрыть все COM-сессии и Finalize.</summary>
public sealed class MvsSdkLifetime : IHostedService
{
    private readonly IoControllerComService _ioCom;
    private readonly MvLeSerialLightSessions _mvLeSessions;
    private readonly ComLightIsolatedBank _isolatedBank;

    public MvsSdkLifetime(
        IoControllerComService ioCom,
        MvLeSerialLightSessions mvLeSessions,
        ComLightIsolatedBank isolatedBank)
    {
        _ioCom = ioCom;
        _mvLeSessions = mvLeSessions;
        _isolatedBank = isolatedBank;
    }

    public Task StartAsync(CancellationToken cancellationToken)
    {
        SDKSystem.Initialize();
        return Task.CompletedTask;
    }

    public Task StopAsync(CancellationToken cancellationToken)
    {
        _isolatedBank.Dispose();
        _mvLeSessions.Dispose();
        _ioCom.Dispose();
        SDKSystem.Finalize();
        return Task.CompletedTask;
    }
}

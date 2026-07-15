using Xunit;

namespace IoInputMonitor.Tests;

public class IoCaptureGateTests
{
    private static IoCaptureGate CreateGate(bool requireDirection = true) =>
        new(new IoCaptureOptions
        {
            Enabled = true,
            DirectionPort = 2,
            TriggerPort = 3,
            RequireDirection = requireDirection
        });

    [Fact]
    public void IgnoresDi3UntilDi2Arms()
    {
        var gate = CreateGate();
        Assert.False(gate.TryFireCapture(3, true, risingEdge: true));
        Assert.True(gate.TryFireCapture(2, true, risingEdge: true));
        Assert.True(gate.IsDirectionArmed);
        Assert.True(gate.TryFireCapture(3, true, risingEdge: true));
    }

    [Fact]
    public void FiresDi3WithoutDirectionWhenNotRequired()
    {
        var gate = CreateGate(requireDirection: false);
        Assert.True(gate.TryFireCapture(3, true, risingEdge: true));
    }

    [Fact]
    public void OnlyOneCapturePerDi3Pulse()
    {
        var gate = CreateGate();
        gate.SeedDirection(true);
        Assert.True(gate.TryFireCapture(3, true, risingEdge: true));
        Assert.False(gate.TryFireCapture(3, true, risingEdge: false));
    }

    [Fact]
    public void ResetsCaptureFlagOnDi3Release()
    {
        var gate = CreateGate();
        gate.SeedDirection(true);
        Assert.True(gate.TryFireCapture(3, true, risingEdge: true));
        Assert.False(gate.TryFireCapture(3, false, risingEdge: false));
        Assert.True(gate.TryFireCapture(3, true, risingEdge: true));
    }
}

using Xunit;

namespace IoInputMonitor.Tests;

public class IoCaptureGateTests
{
    private static IoCaptureGate CreateGate(
        bool requireDirection = true,
        string initialDirection = "reverse") =>
        new(new IoCaptureOptions
        {
            Enabled = true,
            DirectionPort = 2,
            TriggerPort = 3,
            RequireDirection = requireDirection,
            InitialDirection = initialDirection
        });

    [Fact]
    public void ForwardRequiresDi2HighThenDi3()
    {
        var gate = CreateGate(initialDirection: "forward");
        Assert.Equal(IoCaptureDecision.SkipNoDirection, gate.Evaluate(3, true, risingEdge: true));
        Assert.Equal(IoCaptureDecision.None, gate.Evaluate(3, false, risingEdge: false));
        Assert.Equal(IoCaptureDecision.DirectionArmed, gate.Evaluate(2, true, risingEdge: true));
        Assert.True(gate.IsDirectionArmed);
        Assert.Equal(IoCaptureDecision.FireDo, gate.Evaluate(3, true, risingEdge: true));
    }

    [Fact]
    public void ReverseRequiresDi2LowThenDi3()
    {
        var gate = CreateGate(initialDirection: "reverse");
        gate.SeedDirection(true);
        Assert.False(gate.IsDirectionArmed);
        Assert.Equal(IoCaptureDecision.SkipNoDirection, gate.Evaluate(3, true, risingEdge: true));
        Assert.Equal(IoCaptureDecision.None, gate.Evaluate(3, false, risingEdge: false));

        Assert.Equal(IoCaptureDecision.DirectionArmed, gate.Evaluate(2, false, risingEdge: false));
        Assert.True(gate.IsDirectionArmed);
        Assert.Equal(IoCaptureDecision.FireDo, gate.Evaluate(3, true, risingEdge: true));
    }

    [Fact]
    public void UiSwitchChangesArmRule()
    {
        var gate = CreateGate(initialDirection: "reverse");
        gate.SeedDirection(true);
        Assert.False(gate.IsDirectionArmed);

        gate.SetSelectedDirection("forward");
        Assert.True(gate.IsDirectionArmed);
        Assert.Equal("forward", gate.SelectedWireValue);
        Assert.Equal(IoCaptureDecision.FireDo, gate.Evaluate(3, true, risingEdge: true));
    }

    [Fact]
    public void FiresDi3WithoutDirectionWhenNotRequired()
    {
        var gate = CreateGate(requireDirection: false);
        Assert.Equal(IoCaptureDecision.FireDo, gate.Evaluate(3, true, risingEdge: true));
    }

    [Fact]
    public void OnlyOneCapturePerDi3Pulse()
    {
        var gate = CreateGate(initialDirection: "forward");
        gate.SeedDirection(true);
        Assert.Equal(IoCaptureDecision.FireDo, gate.Evaluate(3, true, risingEdge: true));
        Assert.Equal(IoCaptureDecision.None, gate.Evaluate(3, true, risingEdge: false));
    }

    [Fact]
    public void ResetsCaptureFlagOnDi3Release()
    {
        var gate = CreateGate(initialDirection: "forward");
        gate.SeedDirection(true);
        Assert.Equal(IoCaptureDecision.FireDo, gate.Evaluate(3, true, risingEdge: true));
        Assert.Equal(IoCaptureDecision.None, gate.Evaluate(3, false, risingEdge: false));
        Assert.Equal(IoCaptureDecision.FireDo, gate.Evaluate(3, true, risingEdge: true));
    }

    [Fact]
    public void DisarmsWhenDi2LeavesSelectedTravel()
    {
        var gate = CreateGate(initialDirection: "forward");
        gate.SeedDirection(true);
        Assert.True(gate.IsDirectionArmed);
        gate.Evaluate(2, false, risingEdge: false);
        Assert.False(gate.IsDirectionArmed);
        Assert.Equal(IoCaptureDecision.SkipNoDirection, gate.Evaluate(3, true, risingEdge: true));
    }
}

using Xunit;

namespace IoInputMonitor.Tests;

public class IoCaptureGateTests
{
    private static IoCaptureGate CreateGate(
        bool requireDirection = true,
        bool directionLatch = true,
        bool disarmOnWorkLow = false,
        int workPort = 1) =>
        new(new IoCaptureOptions
        {
            Enabled = true,
            DirectionPort = 2,
            TriggerPort = 3,
            WorkPort = workPort,
            DisarmOnWorkLow = disarmOnWorkLow,
            RequireDirection = requireDirection,
            DirectionLatch = directionLatch,
            InitialDirection = "forward"
        });

    [Fact]
    public void RequiresDi2HighThenDi3()
    {
        var gate = CreateGate();
        Assert.Equal(IoCaptureDecision.SkipNoDirection, gate.Evaluate(3, true, risingEdge: true));
        Assert.Equal(IoCaptureDecision.None, gate.Evaluate(3, false, risingEdge: false));
        Assert.Equal(IoCaptureDecision.DirectionArmed, gate.Evaluate(2, true, risingEdge: true));
        Assert.True(gate.IsDirectionArmed);
        Assert.True(gate.IsDirectionLatched);
        Assert.Equal(IoCaptureDecision.FireDo, gate.Evaluate(3, true, risingEdge: true));
    }

    [Fact]
    public void SkipsDi3WhenDi2LowBeforeLatch()
    {
        var gate = CreateGate();
        gate.SeedDirection(false);
        Assert.False(gate.IsDirectionArmed);
        Assert.Equal(IoCaptureDecision.SkipNoDirection, gate.Evaluate(3, true, risingEdge: true));
    }

    [Fact]
    public void AfterLatch_Di2Idle_StillFiresDi3()
    {
        var gate = CreateGate(directionLatch: true);
        Assert.Equal(IoCaptureDecision.DirectionArmed, gate.Evaluate(2, true, risingEdge: true));
        Assert.True(gate.IsDirectionLatched);

        // Дальнейшие DI2 — холостые.
        Assert.Equal(IoCaptureDecision.None, gate.Evaluate(2, false, risingEdge: false));
        Assert.True(gate.IsDirectionArmed);
        Assert.Equal(IoCaptureDecision.None, gate.Evaluate(2, true, risingEdge: true));

        Assert.Equal(IoCaptureDecision.FireDo, gate.Evaluate(3, true, risingEdge: true));
    }

    [Fact]
    public void WorkLow_DisarmsLatch_ThenDi3Skipped()
    {
        var gate = CreateGate(disarmOnWorkLow: true, workPort: 1);
        Assert.Equal(IoCaptureDecision.DirectionArmed, gate.Evaluate(2, true, risingEdge: true));
        Assert.Equal(IoCaptureDecision.DirectionDisarmed, gate.Evaluate(1, false, risingEdge: false));
        Assert.False(gate.IsDirectionArmed);
        Assert.False(gate.IsDirectionLatched);
        Assert.Equal(IoCaptureDecision.SkipNoDirection, gate.Evaluate(3, true, risingEdge: true));
    }

    [Fact]
    public void Disarm_AllowsRearmOnNextDi2()
    {
        var gate = CreateGate(disarmOnWorkLow: true);
        gate.SeedDirection(true);
        Assert.True(gate.IsDirectionLatched);
        Assert.Equal(IoCaptureDecision.DirectionDisarmed, gate.Disarm());
        Assert.Equal(IoCaptureDecision.DirectionArmed, gate.Evaluate(2, true, risingEdge: true));
        Assert.Equal(IoCaptureDecision.FireDo, gate.Evaluate(3, true, risingEdge: true));
    }

    [Fact]
    public void ReleaseCaptureFireSlot_AllowsSecondFireWhileTriggerHeld()
    {
        var gate = CreateGate();
        gate.SeedDirection(true);
        Assert.Equal(IoCaptureDecision.FireDo, gate.Evaluate(3, true, risingEdge: true));
        gate.ReleaseCaptureFireSlot();
        // Триггер ещё «активен» — Evaluate rising снова не сработает без release;
        // после отпускания слота повторный rising после falling должен пройти.
        Assert.Equal(IoCaptureDecision.None, gate.Evaluate(3, false, risingEdge: false));
        Assert.Equal(IoCaptureDecision.FireDo, gate.Evaluate(3, true, risingEdge: true));
    }

    [Fact]
    public void WithoutLatch_DisarmsWhenDi2GoesLow()
    {
        var gate = CreateGate(directionLatch: false);
        gate.SeedDirection(true);
        Assert.True(gate.IsDirectionArmed);
        gate.Evaluate(2, false, risingEdge: false);
        Assert.False(gate.IsDirectionArmed);
        Assert.Equal(IoCaptureDecision.SkipNoDirection, gate.Evaluate(3, true, risingEdge: true));
    }

    [Fact]
    public void UiSwitchDoesNotChangeDi2ArmRule()
    {
        var gate = CreateGate();
        gate.SeedDirection(true);
        Assert.True(gate.IsDirectionArmed);

        gate.SetSelectedDirection("reverse");
        Assert.True(gate.IsDirectionArmed);
        Assert.Equal("reverse", gate.SelectedWireValue);
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
        var gate = CreateGate();
        gate.SeedDirection(true);
        Assert.Equal(IoCaptureDecision.FireDo, gate.Evaluate(3, true, risingEdge: true));
        Assert.Equal(IoCaptureDecision.None, gate.Evaluate(3, true, risingEdge: false));
    }

    [Fact]
    public void ResetsCaptureFlagOnDi3Release()
    {
        var gate = CreateGate();
        gate.SeedDirection(true);
        Assert.Equal(IoCaptureDecision.FireDo, gate.Evaluate(3, true, risingEdge: true));
        Assert.Equal(IoCaptureDecision.None, gate.Evaluate(3, false, risingEdge: false));
        Assert.Equal(IoCaptureDecision.FireDo, gate.Evaluate(3, true, risingEdge: true));
    }
}

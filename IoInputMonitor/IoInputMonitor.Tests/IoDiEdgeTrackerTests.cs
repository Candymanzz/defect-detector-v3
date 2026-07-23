using Xunit;

namespace IoInputMonitor.Tests;

public class IoDiEdgeTrackerTests
{
    [Fact]
    public void Both_RejectsDuplicateRising()
    {
        var tracker = new IoDiEdgeTracker(softwareRefractoryMs: 0);
        tracker.Seed(3, pressed: false);

        Assert.True(tracker.TryAccept(3, MvIoNative.IoEdgeType.Rising, IoInputEdgeMode.Both, out bool closed));
        Assert.True(closed);
        Assert.False(tracker.TryAccept(3, MvIoNative.IoEdgeType.Rising, IoInputEdgeMode.Both, out _));
    }

    [Fact]
    public void Refractory_DropsBounce()
    {
        var tracker = new IoDiEdgeTracker(softwareRefractoryMs: 50);
        tracker.Seed(3, pressed: false);

        Assert.True(tracker.TryAccept(3, MvIoNative.IoEdgeType.Rising, IoInputEdgeMode.Both, out _));
        Assert.True(tracker.TryAccept(3, MvIoNative.IoEdgeType.Falling, IoInputEdgeMode.Both, out _));
        // Immediate re-rise within refractory window.
        Assert.False(tracker.TryAccept(3, MvIoNative.IoEdgeType.Rising, IoInputEdgeMode.Both, out _));
    }

    [Fact]
    public void CapturePulseScheduler_AllowsOnlyOneInflight()
    {
        var scheduler = new IoCapturePulseScheduler();
        Assert.True(scheduler.TryBegin());
        Assert.True(scheduler.IsBusy);
        Assert.False(scheduler.TryBegin());
        scheduler.End();
        Assert.True(scheduler.TryBegin());
        scheduler.End();
    }
}

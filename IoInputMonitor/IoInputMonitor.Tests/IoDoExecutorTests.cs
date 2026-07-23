using System.Collections.Concurrent;
using Xunit;

namespace IoInputMonitor.Tests;

public sealed class IoDoExecutorTests
{
    [Fact]
    public async Task Input_jumps_ahead_of_queued_Capture()
    {
        using var exec = new IoDoExecutor();
        var order = new ConcurrentQueue<string>();
        var gateOpen = new ManualResetEventSlim(false);
        var blockerRunning = new ManualResetEventSlim(false);

        Task blocker = exec.RunAsync(IoDoExecutor.Priority.Capture, () =>
        {
            blockerRunning.Set();
            gateOpen.Wait(5000);
            order.Enqueue("blocker");
            return true;
        });

        Assert.True(blockerRunning.Wait(2000));

        Task capture = exec.RunAsync(IoDoExecutor.Priority.Capture, () =>
        {
            order.Enqueue("capture");
            return true;
        });

        Task input = exec.RunAsync(IoDoExecutor.Priority.Input, () =>
        {
            order.Enqueue("input");
            return true;
        });

        gateOpen.Set();
        await Task.WhenAll(blocker, input, capture);

        Assert.Equal(new[] { "blocker", "input", "capture" }, order.ToArray());
    }

    [Fact]
    public async Task Capture_still_runs_after_Input()
    {
        using var exec = new IoDoExecutor();
        var order = new ConcurrentQueue<string>();

        Task input = exec.RunAsync(IoDoExecutor.Priority.Input, () =>
        {
            Thread.Sleep(30);
            order.Enqueue("input");
            return true;
        });

        Task capture = exec.RunAsync(IoDoExecutor.Priority.Capture, () =>
        {
            order.Enqueue("capture");
            return true;
        });

        await Task.WhenAll(input, capture);
        Assert.Equal(new[] { "input", "capture" }, order.ToArray());
    }
}

using System.Collections.Concurrent;
using Xunit;

namespace IoInputMonitor.Tests;

public sealed class IoDoExecutorTests
{
    [Fact]
    public async Task Input_jumps_ahead_of_queued_Capture_and_Plc()
    {
        using var exec = new IoDoExecutor();
        var order = new ConcurrentQueue<string>();
        var gateOpen = new ManualResetEventSlim(false);
        var blockerRunning = new ManualResetEventSlim(false);

        Task blocker = exec.RunAsync(IoDoExecutor.Priority.Plc, () =>
        {
            blockerRunning.Set();
            gateOpen.Wait(5000);
            order.Enqueue("blocker");
            return true;
        });

        Assert.True(blockerRunning.Wait(2000));

        Task plc = exec.RunAsync(IoDoExecutor.Priority.Plc, () =>
        {
            order.Enqueue("plc");
            return true;
        });

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
        await Task.WhenAll(blocker, input, capture, plc);

        Assert.Equal(new[] { "blocker", "input", "capture", "plc" }, order.ToArray());
    }

    [Fact]
    public async Task Plc_waits_until_Capture_window_closes()
    {
        using var exec = new IoDoExecutor();
        exec.Arbiter.PlcCooldownMs = 50;
        var order = new ConcurrentQueue<string>();
        var plcStarted = new ManualResetEventSlim(false);

        exec.Arbiter.EnterCaptureWindow();
        try
        {
            Task plc = Task.Run(() =>
            {
                string r = exec.Arbiter.RunPlcAfterQuiet(() =>
                {
                    plcStarted.Set();
                    order.Enqueue("plc");
                    return "ok";
                }, quietTimeoutMs: 3000, runTimeoutMs: 2000);
                Assert.Equal("ok", r);
            });

            await Task.Delay(80);
            Assert.False(plcStarted.IsSet);
            order.Enqueue("capture-hold");

            exec.Arbiter.LeaveCaptureWindow();
            await plc.WaitAsync(TimeSpan.FromSeconds(3));
        }
        finally
        {
            exec.Arbiter.PlcCooldownMs = 0;
            if (exec.Arbiter.IsCaptureBusy)
            {
                exec.Arbiter.EnterCaptureWindow();
                exec.Arbiter.LeaveCaptureWindow();
            }
        }

        Assert.Equal(new[] { "capture-hold", "plc" }, order.ToArray());
    }

    [Fact]
    public async Task Plc_still_runs_after_Capture()
    {
        using var exec = new IoDoExecutor();
        var order = new ConcurrentQueue<string>();

        Task capture = exec.RunAsync(IoDoExecutor.Priority.Capture, () =>
        {
            Thread.Sleep(30);
            order.Enqueue("capture");
            return true;
        });

        Task plc = exec.RunAsync(IoDoExecutor.Priority.Plc, () =>
        {
            order.Enqueue("plc");
            return true;
        });

        await Task.WhenAll(capture, plc);
        Assert.Equal(new[] { "capture", "plc" }, order.ToArray());
    }
}

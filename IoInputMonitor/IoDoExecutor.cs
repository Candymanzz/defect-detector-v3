namespace IoInputMonitor;

/// <summary>
/// Арбитр одного COM/SDK: Input (DI re-arm) и Capture (DO5 Line0).
/// Sleep импульсов — вне worker.
/// </summary>
internal sealed class IoMonitorArbiter : IDisposable
{
    public enum Domain
    {
        /// <summary>DI edge re-arm — всегда первый.</summary>
        Input = 0,
        /// <summary>DO5 съёмка Line0.</summary>
        Capture = 1
    }

    private readonly PriorityQueue<WorkItem, (int Prio, long Seq)> _ready = new();
    private readonly object _lock = new();
    private readonly AutoResetEvent _signal = new(false);
    private readonly Thread _worker;
    private readonly CancellationTokenSource _cts = new();
    private long _seq;
    private int _captureHold;
    private bool _disposed;

    public IoMonitorArbiter(string threadName = "io-monitor-arbiter")
    {
        _worker = new Thread(RunLoop)
        {
            IsBackground = true,
            Name = threadName,
            Priority = ThreadPriority.AboveNormal
        };
        _worker.Start();
    }

    public bool IsCaptureBusy
    {
        get
        {
            lock (_lock)
                return _captureHold > 0;
        }
    }

    public void EnterCaptureWindow()
    {
        lock (_lock)
            _captureHold++;
    }

    public void LeaveCaptureWindow()
    {
        lock (_lock)
            _captureHold = Math.Max(0, _captureHold - 1);
    }

    public IDisposable CaptureWindow()
    {
        EnterCaptureWindow();
        return new CaptureWindowScope(this);
    }

    public Task RunAsync(Domain domain, Action action, CancellationToken cancellationToken = default) =>
        RunAsync(domain, () =>
        {
            action();
            return true;
        }, cancellationToken);

    public Task<T> RunAsync<T>(
        Domain domain,
        Func<T> action,
        CancellationToken cancellationToken = default)
    {
        ObjectDisposedException.ThrowIf(_disposed, this);
        ArgumentNullException.ThrowIfNull(action);

        var tcs = new TaskCompletionSource<T>(TaskCreationOptions.RunContinuationsAsynchronously);
        if (cancellationToken.IsCancellationRequested)
        {
            tcs.TrySetCanceled(cancellationToken);
            return tcs.Task;
        }

        CancellationTokenRegistration reg = default;
        if (cancellationToken.CanBeCanceled)
            reg = cancellationToken.Register(() => tcs.TrySetCanceled(cancellationToken));

        var item = new WorkItem(
            domain,
            () =>
            {
                try
                {
                    if (cancellationToken.IsCancellationRequested)
                    {
                        tcs.TrySetCanceled(cancellationToken);
                        return;
                    }

                    T result = action();
                    tcs.TrySetResult(result);
                }
                catch (Exception ex)
                {
                    tcs.TrySetException(ex);
                }
                finally
                {
                    reg.Dispose();
                }
            });

        lock (_lock)
        {
            long seq = ++_seq;
            _ready.Enqueue(item, ((int)domain, seq));
        }

        _signal.Set();
        return tcs.Task;
    }

    public T Run<T>(Domain domain, Func<T> action, int timeoutMs = 8000)
    {
        Task<T> task = RunAsync(domain, action);
        if (!task.Wait(Math.Max(1, timeoutMs)))
            throw new TimeoutException($"IoMonitorArbiter timed out after {timeoutMs} ms (domain={domain})");
        try
        {
            return task.GetAwaiter().GetResult();
        }
        catch (AggregateException ae)
        {
            throw ae.Flatten().InnerException ?? ae;
        }
    }

    public void Run(Domain domain, Action action, int timeoutMs = 8000) =>
        Run(domain, () =>
        {
            action();
            return true;
        }, timeoutMs);

    private void RunLoop()
    {
        while (!_cts.IsCancellationRequested)
        {
            WorkItem? item = null;
            lock (_lock)
            {
                if (_ready.TryDequeue(out WorkItem? next, out _))
                    item = next;
            }

            if (item == null)
            {
                _signal.WaitOne(100);
                continue;
            }

            try
            {
                item.Execute();
            }
            catch
            {
                // в TCS
            }
        }
    }

    public void Dispose()
    {
        if (_disposed)
            return;
        _disposed = true;
        _cts.Cancel();
        _signal.Set();
        _ = _worker.Join(2000);
        _signal.Dispose();
        _cts.Dispose();
    }

    private sealed class WorkItem(Domain domain, Action execute)
    {
        public Domain Domain { get; } = domain;
        public void Execute() => execute();
    }

    private sealed class CaptureWindowScope(IoMonitorArbiter arbiter) : IDisposable
    {
        private bool _done;
        public void Dispose()
        {
            if (_done)
                return;
            _done = true;
            arbiter.LeaveCaptureWindow();
        }
    }
}

/// <summary>Фасад приоритетов Input / Capture для DO5.</summary>
internal sealed class IoDoExecutor : IDisposable
{
    public enum Priority
    {
        Input = 0,
        Capture = 1
    }

    private readonly IoMonitorArbiter _arbiter;

    public IoDoExecutor() => _arbiter = new IoMonitorArbiter();

    public IoMonitorArbiter Arbiter => _arbiter;

    public Task RunAsync(Priority priority, Action action, CancellationToken cancellationToken = default) =>
        _arbiter.RunAsync(Map(priority), action, cancellationToken);

    public Task<T> RunAsync<T>(Priority priority, Func<T> action, CancellationToken cancellationToken = default) =>
        _arbiter.RunAsync(Map(priority), action, cancellationToken);

    public T Run<T>(Priority priority, Func<T> action, int timeoutMs = 8000) =>
        _arbiter.Run(Map(priority), action, timeoutMs);

    public void Run(Priority priority, Action action, int timeoutMs = 8000) =>
        _arbiter.Run(Map(priority), action, timeoutMs);

    public void Dispose() => _arbiter.Dispose();

    private static IoMonitorArbiter.Domain Map(Priority p) => p switch
    {
        Priority.Input => IoMonitorArbiter.Domain.Input,
        _ => IoMonitorArbiter.Domain.Capture
    };
}

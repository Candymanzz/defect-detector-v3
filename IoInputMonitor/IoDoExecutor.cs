namespace IoInputMonitor;

/// <summary>
/// Арбитр одного COM/SDK на две логические зоны:
/// <list type="bullet">
/// <item><b>Capture</b> — DI2/DI3 (+ re-arm) и DO5 Line0</item>
/// <item><b>Plc</b> — DO1–4 (ready/fault/reject → ПЛК)</item>
/// </list>
/// Пока открыто Capture-окно, PLC в очереди ждёт (не долбит SDK).
/// Sleep импульсов — вне worker; Input/re-arm не голодает.
/// </summary>
internal sealed class IoMonitorArbiter : IDisposable
{
    public enum Domain
    {
        /// <summary>DI edge re-arm — всегда первый.</summary>
        Input = 0,
        /// <summary>DO5 съёмка.</summary>
        Capture = 1,
        /// <summary>DO1–4 ПЛК.</summary>
        Plc = 2
    }

    private readonly PriorityQueue<WorkItem, (int Prio, long Seq)> _ready = new();
    private readonly Queue<WorkItem> _plcWaiting = new();
    private readonly object _lock = new();
    private readonly AutoResetEvent _signal = new(false);
    private readonly ManualResetEventSlim _captureQuiet = new(true);
    private readonly Thread _worker;
    private readonly CancellationTokenSource _cts = new();
    private long _seq;
    private int _captureHold;
    private int _plcCooldownMs = 200;
    private long _quietReadyTick;
    private int _cooldownGen;
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

    /// <summary>Пауза после DO5 перед PLC (мс). Съёмку не режет — только откладывает reject.</summary>
    public int PlcCooldownMs
    {
        get { lock (_lock) return _plcCooldownMs; }
        set { lock (_lock) _plcCooldownMs = Math.Clamp(value, 0, 2000); }
    }

    public bool IsCaptureBusy
    {
        get
        {
            lock (_lock)
                return _captureHold > 0 || !_captureQuiet.IsSet;
        }
    }

    /// <summary>Открыть окно съёмки (DI3↑→DO5…). PLC не стартует, пока не Close + cooldown.</summary>
    public void EnterCaptureWindow()
    {
        lock (_lock)
        {
            _captureHold++;
            _cooldownGen++; // отменить отложенный quiet
            _captureQuiet.Reset();
        }
    }

    public void LeaveCaptureWindow()
    {
        int gen;
        int cooldown;
        lock (_lock)
        {
            _captureHold = Math.Max(0, _captureHold - 1);
            if (_captureHold != 0)
                return;

            cooldown = _plcCooldownMs;
            if (cooldown <= 0)
            {
                _captureQuiet.Set();
                PromotePlcIfQuiet_NoLock();
                _signal.Set();
                return;
            }

            // COM ещё «горячий» после DO5 — не открываем PLC сразу.
            _captureQuiet.Reset();
            gen = ++_cooldownGen;
            _quietReadyTick = Environment.TickCount64 + cooldown;
        }

        _ = Task.Run(async () =>
        {
            try
            {
                await Task.Delay(cooldown).ConfigureAwait(false);
                lock (_lock)
                {
                    if (gen != _cooldownGen || _captureHold != 0)
                        return;
                    if (Environment.TickCount64 < _quietReadyTick)
                        return;
                    _captureQuiet.Set();
                    PromotePlcIfQuiet_NoLock();
                    _signal.Set();
                }
            }
            catch
            {
                // shutdown
            }
        });
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

    public Task<T> RunAsync<T>(Domain domain, Func<T> action, CancellationToken cancellationToken = default)
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
            if (domain == Domain.Plc && _captureHold > 0)
                _plcWaiting.Enqueue(item);
            else
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

    /// <summary>
    /// PLC: дождаться тишины Capture, затем короткий SDK-слот.
    /// Не долбит COM, пока идёт DO5.
    /// </summary>
    public T RunPlcAfterQuiet<T>(Func<T> action, int quietTimeoutMs = 5000, int runTimeoutMs = 8000)
    {
        if (!_captureQuiet.Wait(Math.Max(1, quietTimeoutMs)))
        {
            throw new TimeoutException(
                $"IoMonitorArbiter: Capture window still open after {quietTimeoutMs} ms — PLC deferred");
        }

        return Run(Domain.Plc, action, runTimeoutMs);
    }

    private void PromotePlcIfQuiet_NoLock()
    {
        // Во время cooldown (_captureQuiet reset при hold=0) PLC не поднимаем.
        if (_captureHold != 0 || !_captureQuiet.IsSet)
            return;

        while (_plcWaiting.Count > 0)
        {
            WorkItem item = _plcWaiting.Dequeue();
            long seq = ++_seq;
            _ready.Enqueue(item, ((int)Domain.Plc, seq));
        }
    }

    private void RunLoop()
    {
        while (!_cts.IsCancellationRequested)
        {
            WorkItem? item = null;
            lock (_lock)
            {
                PromotePlcIfQuiet_NoLock();
                if (_ready.TryDequeue(out WorkItem? next, out _))
                    item = next;
            }

            if (item == null)
            {
                _signal.WaitOne(100);
                continue;
            }

            // PLC не стартовать в окне съёмки и во время cooldown после DO5.
            if (item.Domain == Domain.Plc)
            {
                bool defer;
                lock (_lock)
                {
                    defer = _captureHold > 0 || !_captureQuiet.IsSet;
                    if (defer)
                    {
                        _plcWaiting.Enqueue(item);
                        item = null;
                    }
                }

                if (item == null)
                {
                    _captureQuiet.Wait(100);
                    _signal.Set();
                    continue;
                }
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
        _captureQuiet.Set();
        _signal.Set();
        _ = _worker.Join(2000);
        _signal.Dispose();
        _captureQuiet.Dispose();
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

/// <summary>Совместимость со старым именем в логах/тестах.</summary>
internal sealed class IoDoExecutor : IDisposable
{
    public enum Priority
    {
        Input = 0,
        Capture = 1,
        Plc = 2
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
        Priority.Capture => IoMonitorArbiter.Domain.Capture,
        _ => IoMonitorArbiter.Domain.Plc
    };
}

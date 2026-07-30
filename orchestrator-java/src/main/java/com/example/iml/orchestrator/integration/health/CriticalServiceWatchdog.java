package com.example.iml.orchestrator.integration.health;

import com.example.iml.orchestrator.integration.bootstrap.context.port.ProcessRestartHost;
import com.example.iml.orchestrator.integration.bootstrap.lifecycle.IntegrationComponent;
import com.example.iml.orchestrator.integration.lighting.LightServerLauncher;
import com.example.iml.orchestrator.integration.lighting.LightsShutdown;
import com.example.iml.orchestrator.integration.python.AnalisSurfaceLauncher;
import com.example.iml.orchestrator.integration.subprocess.ExternalServiceProcess;
import com.example.iml.orchestrator.integration.subprocess.IntegrationExternalProcessLauncher;
import com.example.iml.orchestrator.integration.subprocess.IoInputMonitorShutdown;
import org.apache.logging.log4j.Logger;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.BooleanSupplier;
import java.util.function.Supplier;

/**
 * Следит за критичными внешними процессами: death → ServiceHealthGate + рестарт с backoff.
 * Без backoff рестарт-луп IoInputMonitor (COM busy) заливает терминал Cursor → OOM.
 */
public final class CriticalServiceWatchdog implements IntegrationComponent {

    private static final long POLL_MS = 2000L;
    /** После стольких неудачных рестартов подряд — пауза {@link #BACKOFF_MS}. */
    private static final int MAX_FAST_FAILURES = 3;
    private static final long BACKOFF_MS = 60_000L;

    private final Logger log;
    private final ServiceHealthGate healthGate;
    private final ProcessRestartHost ctx;
    private final IntegrationExternalProcessLauncher externalLauncher;
    private final LightServerLauncher lightLauncher;
    private final AnalisSurfaceLauncher analisLauncher;
    private final ScheduledExecutorService scheduler;
    private final AtomicBoolean closed = new AtomicBoolean(false);
    private final AtomicBoolean restarting = new AtomicBoolean(false);
    private final List<WatchedExternal> watched = new ArrayList<>();
    private final Map<String, Integer> consecutiveFailures = new java.util.concurrent.ConcurrentHashMap<>();
    private final Map<String, Long> nextRestartAtMs = new java.util.concurrent.ConcurrentHashMap<>();

    private CriticalServiceWatchdog(
            Logger log,
            ServiceHealthGate healthGate,
            ProcessRestartHost ctx,
            IntegrationExternalProcessLauncher externalLauncher,
            LightServerLauncher lightLauncher,
            AnalisSurfaceLauncher analisLauncher
    ) {
        this.log = log;
        this.healthGate = healthGate;
        this.ctx = ctx;
        this.externalLauncher = externalLauncher;
        this.lightLauncher = lightLauncher;
        this.analisLauncher = analisLauncher;
        this.scheduler = Executors.newSingleThreadScheduledExecutor(r -> {
            Thread t = new Thread(r, "critical-service-watchdog");
            t.setDaemon(true);
            return t;
        });
    }

    public static CriticalServiceWatchdog start(
            Logger log,
            ProcessRestartHost ctx,
            ServiceHealthGate healthGate
    ) {
        CriticalServiceWatchdog watchdog = new CriticalServiceWatchdog(
                log,
                healthGate,
                ctx,
                new IntegrationExternalProcessLauncher(log),
                new LightServerLauncher(log),
                new AnalisSurfaceLauncher(log)
        );
        watchdog.bindExternals();
        watchdog.bindSupervisors();
        watchdog.scheduler.scheduleAtFixedRate(watchdog::poll, POLL_MS, POLL_MS, TimeUnit.MILLISECONDS);
        log.info("critical service watchdog started poll_ms={}", POLL_MS);
        return watchdog;
    }

    private void bindExternals() {
        if (ctx.ioInputMonitorProcess() != null) {
            watchExternal(
                    "io_input_monitor",
                    ctx::ioInputMonitorProcess,
                    this::restartIoInputMonitor
            );
        }
        if (ctx.lightServerProcess() != null) {
            watchExternal(
                    "light_server",
                    ctx::lightServerProcess,
                    this::restartLightServer
            );
        }
        if (ctx.analisSurfaceProcesses() != null && !ctx.analisSurfaceProcesses().isEmpty()) {
            watchExternal(
                    "analis_surface",
                    () -> {
                        List<ExternalServiceProcess> list = ctx.analisSurfaceProcesses();
                        if (list == null || list.isEmpty()) {
                            return null;
                        }
                        for (ExternalServiceProcess process : list) {
                            if (process != null && !process.isAlive() && !process.isClosing()) {
                                return process;
                            }
                        }
                        return list.get(0);
                    },
                    this::restartAnalisSurfacePool
            );
            for (ExternalServiceProcess process : ctx.analisSurfaceProcesses()) {
                if (process != null) {
                    attachExit(process, "analis_surface", this::restartAnalisSurfacePool);
                }
            }
        }
    }

    private void watchExternal(String name, Supplier<ExternalServiceProcess> current, BooleanSupplier restart) {
        ExternalServiceProcess process = current.get();
        if (process != null) {
            attachExit(process, name, restart);
        }
        watched.add(new WatchedExternal(name, current, restart));
    }

    private void attachExit(ExternalServiceProcess process, String name, BooleanSupplier restart) {
        process.onUnexpectedExit(() -> handleDeath(name, restart));
    }

    private void bindSupervisors() {
        if (ctx.workersByCamera() != null) {
            ctx.workersByCamera().forEach((cameraId, worker) -> {
                if (worker == null) {
                    return;
                }
                String key = "camera_worker_" + cameraId;
                worker.setHealthListener(ok -> applySupervisorHealth(key, ok));
            });
        }
        bindPool(ctx.geometryPool(), "geometry");
        bindPool(ctx.positioningPool(), "positioning");
        // python HTTP pool is client-side; analis_surface OS process is watched separately
    }

    private void bindPool(List<?> pool, String prefix) {
        if (pool == null) {
            return;
        }
        for (int i = 0; i < pool.size(); i++) {
            Object item = pool.get(i);
            if (item instanceof com.example.iml.orchestrator.integration.binaryrpc.AbstractBinaryRpcSupervisor supervisor) {
                String key = prefix + "_" + i;
                supervisor.setHealthListener(ok -> applySupervisorHealth(key, ok));
            }
        }
    }

    private void applySupervisorHealth(String key, boolean ok) {
        if (closed.get()) {
            return;
        }
        if (ok) {
            healthGate.markHealthy(key);
        } else {
            healthGate.markUnhealthy(key);
        }
    }

    private void poll() {
        if (closed.get() || restarting.get()) {
            return;
        }
        for (WatchedExternal item : watched) {
            ExternalServiceProcess process = item.current.get();
            if (process == null) {
                continue;
            }
            if (!process.isAlive() && !process.isClosing()) {
                handleDeath(item.name, item.restart);
            }
        }
        if (ctx.workersByCamera() != null) {
            ctx.workersByCamera().forEach((cameraId, worker) -> {
                if (worker == null || closed.get()) {
                    return;
                }
                String key = "camera_worker_" + cameraId;
                if (!worker.processAlive()) {
                    healthGate.markUnhealthy(key);
                }
            });
        }
    }

    private void handleDeath(String name, BooleanSupplier restart) {
        if (closed.get()) {
            return;
        }
        healthGate.markUnhealthy(name);
        long now = System.currentTimeMillis();
        Long gatedUntil = nextRestartAtMs.get(name);
        if (gatedUntil != null && now < gatedUntil) {
            return;
        }
        log.warn("critical service dead name={} — vision_fault; attempting restart", name);
        if (!restarting.compareAndSet(false, true)) {
            return;
        }
        try {
            boolean ok = false;
            try {
                ok = restart.getAsBoolean();
            } catch (Exception e) {
                log.warn("critical service restart failed name={}: {}", name, e.getMessage());
            }
            if (ok) {
                consecutiveFailures.remove(name);
                nextRestartAtMs.remove(name);
                healthGate.markHealthy(name);
                log.info("critical service restarted name={} — clearing fault if all healthy", name);
                reattachAfterRestart(name);
            } else {
                int fails = consecutiveFailures.merge(name, 1, Integer::sum);
                log.error(
                        "critical service restart unsuccessful name={} fails={} — vision_fault stays",
                        name,
                        fails
                );
                if (fails >= MAX_FAST_FAILURES) {
                    long until = now + BACKOFF_MS;
                    nextRestartAtMs.put(name, until);
                    consecutiveFailures.put(name, 0);
                    log.error(
                            "critical service restart backoff name={} for {} ms (stops terminal spam / Cursor OOM)",
                            name,
                            BACKOFF_MS
                    );
                }
            }
        } finally {
            restarting.set(false);
        }
    }

    private void reattachAfterRestart(String name) {
        for (WatchedExternal item : watched) {
            if (!item.name.equals(name)) {
                continue;
            }
            ExternalServiceProcess process = item.current.get();
            if (process != null) {
                attachExit(process, name, item.restart);
            }
            if ("analis_surface".equals(name) && ctx.analisSurfaceProcesses() != null) {
                for (ExternalServiceProcess p : ctx.analisSurfaceProcesses()) {
                    if (p != null) {
                        attachExit(p, name, item.restart);
                    }
                }
            }
        }
    }

    private boolean restartIoInputMonitor() {
        if (closed.get()) {
            return false;
        }
        ExternalServiceProcess old = ctx.ioInputMonitorProcess();
        if (old != null) {
            old.close();
        }
        IoInputMonitorShutdown.clearProcessRefOnly();
        ExternalServiceProcess next = externalLauncher.startIfConfigured(
                ctx.integration(),
                ctx.projectRoot(),
                ctx.windows(),
                "io_input_monitor_autostart",
                "io_input_monitor_command_windows",
                "io_input_monitor_command_linux",
                "io-input-monitor",
                "."
        );
        ctx.setIoInputMonitorProcess(next);
        if (next != null) {
            IoInputMonitorShutdown.replaceProcess(next);
        }
        return next != null && next.isAlive();
    }

    private boolean restartLightServer() {
        ExternalServiceProcess old = ctx.lightServerProcess();
        if (old != null) {
            old.close();
        }
        LightsShutdown.clearProcessRefOnly();
        ExternalServiceProcess next = lightLauncher.startIfConfigured(
                ctx.integration(),
                ctx.projectRoot(),
                ctx.windows(),
                ctx.bootConfig().lightStartupDelayMs()
        );
        ctx.setLightServerProcess(next);
        if (next != null) {
            LightsShutdown.replaceProcess(next);
        }
        return next != null && next.isAlive();
    }

    private boolean restartAnalisSurfacePool() {
        List<ExternalServiceProcess> old = ctx.analisSurfaceProcesses();
        if (old != null) {
            for (ExternalServiceProcess process : old) {
                if (process != null) {
                    process.close();
                }
            }
        }
        Map<String, Object> pythonCfg = ctx.pythonCfg();
        String baseUrl = pythonCfg == null ? null : String.valueOf(pythonCfg.getOrDefault("base_url", "http://127.0.0.1:8000"));
        int poolSize = Math.max(1, ctx.pythonPool() == null ? 1 : ctx.pythonPool().size());
        AnalisSurfaceLauncher.PoolStartResult result = analisLauncher.startPoolIfConfigured(
                ctx.integration(),
                ctx.projectRoot(),
                ctx.windows(),
                baseUrl,
                poolSize,
                ctx.bootConfig().workerStartupStaggerMs()
        );
        ctx.setAnalisSurfaceProcesses(result.processes());
        return result.processes() != null
                && !result.processes().isEmpty()
                && result.processes().stream().allMatch(p -> p != null && p.isAlive());
    }

    @Override
    public void start() {
        // already started in factory
    }

    @Override
    public void close() {
        if (!closed.compareAndSet(false, true)) {
            return;
        }
        scheduler.shutdownNow();
        try {
            scheduler.awaitTermination(2, TimeUnit.SECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    private record WatchedExternal(
            String name,
            Supplier<ExternalServiceProcess> current,
            BooleanSupplier restart
    ) {
    }
}

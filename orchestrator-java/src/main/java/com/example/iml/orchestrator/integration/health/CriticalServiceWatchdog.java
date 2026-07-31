package com.example.iml.orchestrator.integration.health;

import com.example.iml.orchestrator.integration.bootstrap.context.port.ProcessRestartHost;
import com.example.iml.orchestrator.integration.bootstrap.lifecycle.IntegrationComponent;
import com.example.iml.orchestrator.integration.lighting.LightServerLauncher;
import com.example.iml.orchestrator.integration.python.AnalisSurfaceLauncher;
import com.example.iml.orchestrator.integration.subprocess.ExternalServiceProcess;
import com.example.iml.orchestrator.integration.subprocess.IntegrationExternalProcessLauncher;
import org.apache.logging.log4j.Logger;

import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.BooleanSupplier;

/**
 * Следит за критичными внешними процессами: death → ServiceHealthGate + одна попытка рестарта.
 */
public final class CriticalServiceWatchdog implements IntegrationComponent {

    private static final long POLL_MS = 2000L;

    private final Logger log;
    private final ServiceHealthGate healthGate;
    private final ProcessRestartHost ctx;
    private final ScheduledExecutorService scheduler;
    private final AtomicBoolean closed = new AtomicBoolean(false);
    private final AtomicBoolean restarting = new AtomicBoolean(false);
    private final CriticalServiceBindings bindings;

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
        this.scheduler = Executors.newSingleThreadScheduledExecutor(r -> {
            Thread t = new Thread(r, "critical-service-watchdog");
            t.setDaemon(true);
            return t;
        });
        CriticalServiceRestarter restarter = new CriticalServiceRestarter(
                ctx, externalLauncher, lightLauncher, analisLauncher);
        this.bindings = new CriticalServiceBindings(
                ctx, restarter, this::applySupervisorHealth, this::handleDeath);
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
        watchdog.bindings.bindExternals();
        watchdog.bindings.bindSupervisors();
        watchdog.scheduler.scheduleAtFixedRate(watchdog::poll, POLL_MS, POLL_MS, TimeUnit.MILLISECONDS);
        log.info("critical service watchdog started poll_ms={}", POLL_MS);
        return watchdog;
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
        for (CriticalServiceBindings.WatchedExternal item : bindings.watched()) {
            ExternalServiceProcess process = item.current().get();
            if (process == null) {
                continue;
            }
            if (!process.isAlive() && !process.isClosing()) {
                handleDeath(item.name(), item.restart());
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
                healthGate.markHealthy(name);
                log.info("critical service restarted name={} — clearing fault if all healthy", name);
                bindings.reattachAfterRestart(name);
            } else {
                log.error("critical service restart unsuccessful name={} — vision_fault stays", name);
            }
        } finally {
            restarting.set(false);
        }
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
}

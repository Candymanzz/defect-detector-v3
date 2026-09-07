package com.example.iml.orchestrator.integration.health;

import com.example.iml.orchestrator.integration.bootstrap.context.IntegrationRuntimeContext;
import com.example.iml.orchestrator.integration.bootstrap.lifecycle.IntegrationComponent;
import com.example.iml.orchestrator.integration.binaryrpc.BinaryRpcSupervisor;
import com.example.iml.orchestrator.integration.lighting.LightServersConfig;
import com.example.iml.orchestrator.integration.python.AnalisSurfaceLauncher;
import com.example.iml.orchestrator.integration.lighting.LightServerLauncher;
import com.example.iml.orchestrator.integration.lighting.LightsShutdown;
import com.example.iml.orchestrator.integration.subprocess.ExternalServiceProcess;
import com.example.iml.orchestrator.integration.subprocess.IntegrationExternalProcessLauncher;
import com.example.iml.orchestrator.integration.services.ServiceProcessSupervisor;
import org.apache.logging.log4j.Logger;

import java.io.IOException;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.BooleanSupplier;
import java.util.function.Supplier;

/**
 * Демон-поток recovery: death → vision_fault + пауза пайплайна; периодически перезапускает
 * io_input_monitor, analis_surface, geometry/positioning и восстанавливает health.
 */
public final class CriticalServiceWatchdog implements IntegrationComponent {

    private static final long POLL_MS = 2000L;
    /** Повторный restart упавших сервисов, если первая попытка не удалась. */
    private static final long RECOVERY_RETRY_MS = 10_000L;
    /** HTTP direction latch IoInputMonitor (config direction_http.port, default 9101). */
    private static final int IO_INPUT_HTTP_PORT = 9101;
    /** Пауза после close/kill — Windows часто ещё держит COM/handle. */
    private static final long IO_COM_RELEASE_MS = 1200L;
    /** Процесс должен прожить grace, иначе рестарт считается неудачным (анти-storm). */
    private static final long IO_ALIVE_GRACE_MS = 1500L;

    private final Logger log;
    private final ServiceHealthGate healthGate;
    private final IntegrationRuntimeContext ctx;
    private final IntegrationExternalProcessLauncher externalLauncher;
    private final LightServerLauncher lightLauncher;
    private final AnalisSurfaceLauncher analisLauncher;
    private final ScheduledExecutorService scheduler;
    private final AtomicBoolean closed = new AtomicBoolean(false);
    private final AtomicBoolean restarting = new AtomicBoolean(false);
    private final List<WatchedExternal> watched = new ArrayList<>();
    private final ConcurrentHashMap<String, Long> restartNotBeforeMs = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, Integer> consecutiveRestartFailures = new ConcurrentHashMap<>();
    private volatile long lastRecoveryAttemptMs = 0L;

    private CriticalServiceWatchdog(
            Logger log,
            ServiceHealthGate healthGate,
            IntegrationRuntimeContext ctx,
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
            IntegrationRuntimeContext ctx,
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
        log.info("critical service watchdog started poll_ms={} recovery_retry_ms={}", POLL_MS, RECOVERY_RETRY_MS);
        return watchdog;
    }

    private void bindExternals() {
        if (ioInputMonitorAutostartEnabled()) {
            watchExternal(
                    "io_input_monitor",
                    ctx::ioInputMonitorProcess,
                    this::restartIoInputMonitor
            );
        }
        if (lightServerAutostartEnabled()) {
            watchExternal(
                    "light_server",
                    ctx::lightServerProcess,
                    this::restartLightServer
            );
        }
        if (analisSurfaceAutostartEnabled()) {
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
            attachAnalisSurfaceExitHandlers();
        }
    }

    private void attachAnalisSurfaceExitHandlers() {
        List<ExternalServiceProcess> processes = ctx.analisSurfaceProcesses();
        if (processes == null) {
            return;
        }
        for (ExternalServiceProcess process : processes) {
            if (process != null) {
                attachExit(process, "analis_surface", this::restartAnalisSurfacePool);
            }
        }
    }

    private boolean ioInputMonitorAutostartEnabled() {
        return externalLauncher.parseAutostart(
                ctx.integration(),
                "io_input_monitor_autostart",
                ctx.projectRoot(),
                "."
        ).enabled();
    }

    private boolean lightServerAutostartEnabled() {
        return LightServersConfig.fromRootYaml(ctx.root()).enabled();
    }

    private boolean analisSurfaceAutostartEnabled() {
        return AnalisSurfaceLauncher.parseSettings(ctx.integration(), ctx.projectRoot()).enabled();
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
        bindPythonPool(ctx.pythonPool());
        bindPool(ctx.geometryPool(), "geometry");
        bindPool(ctx.positioningPool(), "positioning");
    }

    private void bindPythonPool(List<?> pool) {
        if (pool == null) {
            return;
        }
        for (int i = 0; i < pool.size(); i++) {
            Object item = pool.get(i);
            if (item instanceof BinaryRpcSupervisor supervisor) {
                String key = "analis_surface_" + i;
                if (supervisor instanceof com.example.iml.orchestrator.integration.binaryrpc.AbstractBinaryRpcSupervisor rpc) {
                    rpc.setHealthListener(ok -> applySupervisorHealth(key, ok));
                } else if (supervisor instanceof com.example.iml.orchestrator.integration.clientapi.AnalisSurfaceHttpBinaryRpcSupervisor http) {
                    http.setHealthListener(ok -> applySupervisorHealth(key, ok));
                }
            }
        }
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
        probeAllServicesHealth();
        detectDeadExternals();
        detectDeadWorkers();
        healSupervisorPools();
        attemptPeriodicRecovery();
    }

    /** Проактивный health-check всех критичных сервисов в daemon-потоке. */
    private void probeAllServicesHealth() {
        probeExternalHealth();
        probePythonHttpPool();
        probeSupervisorPoolHealth(ctx.geometryPool(), "geometry");
        probeSupervisorPoolHealth(ctx.positioningPool(), "positioning");
        probeWorkersHealth();
    }

    private void probeExternalHealth() {
        for (WatchedExternal item : watched) {
            ExternalServiceProcess process = item.current.get();
            if (process == null || !process.isAlive() || process.isClosing()) {
                healthGate.markUnhealthy(item.name);
            } else {
                healthGate.markHealthy(item.name);
            }
        }
    }

    private void probeSupervisorPoolHealth(List<ServiceProcessSupervisor> pool, String prefix) {
        if (pool == null) {
            return;
        }
        for (int i = 0; i < pool.size(); i++) {
            ServiceProcessSupervisor supervisor = pool.get(i);
            String key = prefix + "_" + i;
            if (supervisor == null) {
                healthGate.markUnhealthy(key);
                continue;
            }
            if (!supervisor.processAlive()) {
                healthGate.markUnhealthy(key);
                continue;
            }
            try {
                supervisor.health();
                healthGate.markHealthy(key);
            } catch (IOException e) {
                healthGate.markUnhealthy(key);
            }
        }
    }

    private void probeWorkersHealth() {
        if (ctx.workersByCamera() == null) {
            return;
        }
        ctx.workersByCamera().forEach((cameraId, worker) -> {
            if (worker == null || closed.get()) {
                return;
            }
            String key = "camera_worker_" + cameraId;
            if (!worker.processAlive()) {
                healthGate.markUnhealthy(key);
                return;
            }
            try {
                worker.health();
                healthGate.markHealthy(key);
            } catch (IOException e) {
                healthGate.markUnhealthy(key);
            }
        });
    }

    private void detectDeadExternals() {
        for (WatchedExternal item : watched) {
            ExternalServiceProcess process = item.current.get();
            if (process == null) {
                healthGate.markUnhealthy(item.name);
                continue;
            }
            if (!process.isAlive() && !process.isClosing()) {
                handleDeath(item.name, item.restart);
            }
        }
    }

    private void detectDeadWorkers() {
        if (ctx.workersByCamera() == null) {
            return;
        }
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

    private void attemptPeriodicRecovery() {
        if (healthGate.healthy()) {
            return;
        }
        long now = System.currentTimeMillis();
        if (now - lastRecoveryAttemptMs < RECOVERY_RETRY_MS) {
            return;
        }
        lastRecoveryAttemptMs = now;
        Set<String> reasons = new HashSet<>(healthGate.unhealthyReasons());
        if (reasons.isEmpty()) {
            return;
        }
        log.warn("pipeline recovery daemon tick unhealthy={}", reasons);
        for (String reason : reasons) {
            if (closed.get() || restarting.get()) {
                return;
            }
            tryRecover(reason);
        }
        if (healthGate.healthy()) {
            log.info("pipeline recovery daemon — all critical services healthy");
        }
    }

    private void tryRecover(String reason) {
        if ("io_input_monitor".equals(reason)) {
            attemptServiceRestart("io_input_monitor", this::restartIoInputMonitor);
            return;
        }
        if ("analis_surface".equals(reason)) {
            attemptServiceRestart("analis_surface", this::restartAnalisSurfacePool);
            return;
        }
        if ("light_server".equals(reason)) {
            attemptServiceRestart("light_server", this::restartLightServer);
            return;
        }
        if (reason.startsWith("geometry_")) {
            recoverGeometrySupervisor(reason);
            return;
        }
        if (reason.startsWith("positioning_")) {
            recoverPositioningSupervisor(reason);
            return;
        }
        if (reason.startsWith("analis_surface_")) {
            recoverPythonHttpSupervisor(reason);
            return;
        }
        if (reason.startsWith("camera_worker_")) {
            recoverCameraWorker(reason);
        }
    }

    private void attemptServiceRestart(String name, BooleanSupplier restart) {
        if (!mayAttemptRestart(name)) {
            return;
        }
        if (!restarting.compareAndSet(false, true)) {
            return;
        }
        try {
            boolean ok = false;
            try {
                ok = restart.getAsBoolean();
            } catch (Exception e) {
                log.warn("pipeline recovery restart error name={}: {}", name, e.getMessage());
            }
            onRestartOutcome(name, ok);
            if (ok) {
                healthGate.markHealthy(name);
                reattachAfterRestart(name);
                if ("analis_surface".equals(name)) {
                    probePythonHttpPool();
                    attachAnalisSurfaceExitHandlers();
                }
                log.info("pipeline recovery restarted name={}", name);
            } else {
                log.warn("pipeline recovery restart failed name={}", name);
            }
        } finally {
            restarting.set(false);
        }
    }

    private void healSupervisorPools() {
        recoverSupervisorPool(ctx.geometryPool(), "geometry");
        recoverSupervisorPool(ctx.positioningPool(), "positioning");
        if (healthGate.unhealthyReasons().stream().noneMatch(k -> k.startsWith("analis_surface"))) {
            probePythonHttpPool();
        }
    }

    private void recoverSupervisorPool(List<ServiceProcessSupervisor> pool, String prefix) {
        if (pool == null) {
            return;
        }
        for (int i = 0; i < pool.size(); i++) {
            ServiceProcessSupervisor supervisor = pool.get(i);
            if (supervisor == null) {
                continue;
            }
            String key = prefix + "_" + i;
            if (supervisor.processAlive()) {
                healthGate.markHealthy(key);
                continue;
            }
            if (!healthGate.unhealthyReasons().contains(key)) {
                continue;
            }
            try {
                supervisor.restart();
                healthGate.markHealthy(key);
                log.info("pipeline recovery supervisor restarted key={}", key);
            } catch (IOException e) {
                healthGate.markUnhealthy(key);
                log.warn("pipeline recovery supervisor restart failed key={}: {}", key, e.getMessage());
            }
        }
    }

    private void recoverGeometrySupervisor(String key) {
        recoverIndexedSupervisor(ctx.geometryPool(), key, "geometry_");
    }

    private void recoverPositioningSupervisor(String key) {
        recoverIndexedSupervisor(ctx.positioningPool(), key, "positioning_");
    }

    private void recoverIndexedSupervisor(List<ServiceProcessSupervisor> pool, String key, String prefix) {
        int index = parseSupervisorIndex(key, prefix);
        if (pool == null || index < 0 || index >= pool.size()) {
            return;
        }
        ServiceProcessSupervisor supervisor = pool.get(index);
        if (supervisor == null) {
            return;
        }
        try {
            supervisor.restart();
            healthGate.markHealthy(key);
            log.info("pipeline recovery supervisor restarted key={}", key);
        } catch (IOException e) {
            healthGate.markUnhealthy(key);
            log.warn("pipeline recovery supervisor restart failed key={}: {}", key, e.getMessage());
        }
    }

    private void recoverPythonHttpSupervisor(String key) {
        int index = parseSupervisorIndex(key, "analis_surface_");
        List<BinaryRpcSupervisor> pool = ctx.pythonPool();
        if (pool == null || index < 0 || index >= pool.size()) {
            probePythonHttpPool();
            return;
        }
        BinaryRpcSupervisor supervisor = pool.get(index);
        if (supervisor == null) {
            return;
        }
        try {
            supervisor.restart();
            healthGate.markHealthy(key);
            log.info("pipeline recovery python http restarted key={}", key);
        } catch (IOException e) {
            healthGate.markUnhealthy(key);
            log.warn("pipeline recovery python http restart failed key={}: {}", key, e.getMessage());
        }
    }

    private void recoverCameraWorker(String key) {
        int cameraId = parseSupervisorIndex(key, "camera_worker_");
        if (cameraId < 0 || ctx.workersByCamera() == null) {
            return;
        }
        var worker = ctx.workersByCamera().get(cameraId);
        if (worker == null) {
            return;
        }
        try {
            worker.restart();
            healthGate.markHealthy(key);
            log.info("pipeline recovery camera_worker restarted camera={}", cameraId);
        } catch (IOException e) {
            healthGate.markUnhealthy(key);
            log.warn("pipeline recovery camera_worker restart failed camera={}: {}", cameraId, e.getMessage());
        }
    }

    private void probePythonHttpPool() {
        List<BinaryRpcSupervisor> pool = ctx.pythonPool();
        if (pool == null || pool.isEmpty()) {
            return;
        }
        boolean allOk = true;
        for (int i = 0; i < pool.size(); i++) {
            BinaryRpcSupervisor supervisor = pool.get(i);
            String key = "analis_surface_" + i;
            if (supervisor == null) {
                healthGate.markUnhealthy(key);
                allOk = false;
                continue;
            }
            try {
                supervisor.health();
                healthGate.markHealthy(key);
            } catch (IOException e) {
                healthGate.markUnhealthy(key);
                allOk = false;
            }
        }
        if (allOk) {
            healthGate.markHealthy("analis_surface");
        }
    }

    private static int parseSupervisorIndex(String key, String prefix) {
        if (key == null || !key.startsWith(prefix)) {
            return -1;
        }
        try {
            return Integer.parseInt(key.substring(prefix.length()));
        } catch (NumberFormatException e) {
            return -1;
        }
    }

    private void handleDeath(String name, BooleanSupplier restart) {
        if (closed.get()) {
            return;
        }
        healthGate.markUnhealthy(name);
        if (!mayAttemptRestart(name)) {
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
            onRestartOutcome(name, ok);
            if (ok) {
                healthGate.markHealthy(name);
                log.info("critical service restarted name={} — clearing fault if all healthy", name);
                reattachAfterRestart(name);
                if ("analis_surface".equals(name)) {
                    probePythonHttpPool();
                    attachAnalisSurfaceExitHandlers();
                }
            } else {
                log.error("critical service restart unsuccessful name={} — vision_fault stays", name);
            }
        } finally {
            restarting.set(false);
        }
    }

    private boolean mayAttemptRestart(String name) {
        long notBefore = restartNotBeforeMs.getOrDefault(name, 0L);
        long now = System.currentTimeMillis();
        if (now < notBefore) {
            log.debug(
                    "critical service restart deferred name={} wait_ms={}",
                    name,
                    notBefore - now
            );
            return false;
        }
        return true;
    }

    private void onRestartOutcome(String name, boolean ok) {
        if (ok) {
            consecutiveRestartFailures.remove(name);
            restartNotBeforeMs.remove(name);
            return;
        }
        int failures = consecutiveRestartFailures.merge(name, 1, Integer::sum);
        long delayMs = Math.min(30_000L, 2_000L * (1L << Math.min(failures - 1, 4)));
        restartNotBeforeMs.put(name, System.currentTimeMillis() + delayMs);
        log.warn(
                "critical service backoff name={} failures={} next_retry_ms={}",
                name,
                failures,
                delayMs
        );
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
        ExternalServiceProcess old = ctx.ioInputMonitorProcess();
        if (old != null) {
            old.close();
        }
        // Сироты после crash/Ctrl+C держат COM и HTTP 9101 → мгновенный рестарт падает в loop.
        ExternalServiceProcess.killOrphansMatchingCommand("IoInputMonitor", log);
        ExternalServiceProcess.killOrphansMatchingCommand("io-input-monitor", log);
        ExternalServiceProcess.killListenersOnPort(IO_INPUT_HTTP_PORT, log);
        sleepQuiet(IO_COM_RELEASE_MS);
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
        if (next == null || !next.isAlive()) {
            return false;
        }
        if (!waitProcessAlive(next, IO_ALIVE_GRACE_MS)) {
            log.warn("io_input_monitor exited during grace_ms={} — treating restart as failed", IO_ALIVE_GRACE_MS);
            return false;
        }
        return true;
    }

    private static void sleepQuiet(long ms) {
        if (ms <= 0) {
            return;
        }
        try {
            Thread.sleep(ms);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    private static boolean waitProcessAlive(ExternalServiceProcess process, long graceMs) {
        long deadline = System.currentTimeMillis() + Math.max(0L, graceMs);
        while (System.currentTimeMillis() < deadline) {
            if (!process.isAlive()) {
                return false;
            }
            sleepQuiet(100L);
        }
        return process.isAlive();
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

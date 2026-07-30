package com.example.iml.orchestrator.integration.bootstrap.service.impl;

import com.example.iml.orchestrator.integration.bootstrap.service.api.ChildProcessStartup;

import com.example.iml.orchestrator.integration.bootstrap.service.api.AbstractBootstrapService;

import com.example.iml.orchestrator.integration.bootstrap.context.ChildProcessesContext;
import com.example.iml.orchestrator.integration.bootstrap.factory.IntegrationServicePoolFactory;
import com.example.iml.orchestrator.integration.config.CameraWorkerPaths;
import com.example.iml.orchestrator.integration.config.YamlMaps;
import com.example.iml.orchestrator.integration.config.PythonDetectorConfig;
import com.example.iml.orchestrator.integration.config.YamlScalars;
import com.example.iml.orchestrator.integration.lighting.LightServerLauncher;
import com.example.iml.orchestrator.integration.lighting.LightServersConfig;
import com.example.iml.orchestrator.integration.python.AnalisSurfaceLauncher;
import com.example.iml.orchestrator.integration.services.ServiceProcessSupervisor;
import com.example.iml.orchestrator.integration.subprocess.ExternalServiceProcess;
import com.example.iml.orchestrator.integration.subprocess.IntegrationExternalProcessLauncher;
import com.example.iml.orchestrator.integration.trigger.config.InspectionTriggerConfig;
import org.apache.logging.log4j.Logger;

import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Только запуск child OS-процессов и RPC/HTTP пулов.
 */
public final class ChildProcessStartupImpl extends AbstractBootstrapService implements ChildProcessStartup {

    private static final AtomicInteger CHILD_START_SEQ = new AtomicInteger();

    private final AnalisSurfaceLauncher analisSurfaceLauncher;
    private final LightServerLauncher lightServerLauncher;
    private final IntegrationExternalProcessLauncher externalProcessLauncher;

    public ChildProcessStartupImpl(Logger log) {
        super(log);
        this.analisSurfaceLauncher = new AnalisSurfaceLauncher(log);
        this.lightServerLauncher = new LightServerLauncher(log);
        this.externalProcessLauncher = new IntegrationExternalProcessLauncher(log);
    }

    @Override
    public boolean start(ChildProcessesContext processes, IntegrationServicePoolFactory poolFactory) {
        var env = processes.env();
        var preflight = processes.preflight();
        PythonDetectorConfig pythonDetectorCfg = PythonDetectorConfig.fromRootYaml(env.root());

        // Независимые сервисы стартуют параллельно с analisSurface (и друг с другом).
        // 6 слотов: light + io + frontend + geometry + positioning (+ запас).
        ExecutorService parallel = Executors.newFixedThreadPool(
                6,
                r -> {
                    Thread t = new Thread(r, "child-process-start-" + CHILD_START_SEQ.incrementAndGet());
                    t.setDaemon(true);
                    return t;
                }
        );
        try {
            LightServersConfig lightServersCfg = LightServersConfig.fromRootYaml(env.root());
            InspectionTriggerConfig inspectionTriggerConfig = InspectionTriggerConfig.parse(preflight.integration());

            CompletableFuture<ExternalServiceProcess> lightFuture = CompletableFuture.supplyAsync(() -> {
                if (!lightServersCfg.enabled()) {
                    log.info("light_servers.enabled=false — LightServer не запускается, COM-вспышки отключены");
                    return null;
                }
                return lightServerLauncher.startIfConfigured(
                        preflight.integration(),
                        env.projectRoot(),
                        env.windows(),
                        preflight.bootConfig().lightStartupDelayMs());
            }, parallel);

            CompletableFuture<ExternalServiceProcess> ioFuture = CompletableFuture.supplyAsync(() -> {
                if (!inspectionTriggerConfig.usesIoInputMonitor()) {
                    return null;
                }
                return externalProcessLauncher.startIfConfigured(
                        preflight.integration(),
                        env.projectRoot(),
                        env.windows(),
                        "io_input_monitor_autostart",
                        "io_input_monitor_command_windows",
                        "io_input_monitor_command_linux",
                        "io-input-monitor",
                        "."
                );
            }, parallel);

            CompletableFuture<ExternalServiceProcess> frontendFuture = CompletableFuture.supplyAsync(() -> {
                if (!shouldAutostartFrontend()) {
                    return null;
                }
                return externalProcessLauncher.startIfConfigured(
                        preflight.integration(),
                        env.projectRoot(),
                        env.windows(),
                        "frontend_autostart",
                        "frontend_command_windows",
                        "frontend_command_linux",
                        "frontend",
                        "front-end"
                );
            }, parallel);

            AnalisSurfaceLauncher.PoolStartResult analisSurfacePool = analisSurfaceLauncher.startPoolIfConfigured(
                    preflight.integration(),
                    env.projectRoot(),
                    env.windows(),
                    pythonDetectorCfg.baseUrl(),
                    preflight.bootConfig().pythonServerPoolSize(),
                    preflight.bootConfig().workerStartupStaggerMs()
            );
            processes.setAnalisSurfaceProcesses(analisSurfacePool.processes());

            processes.setPythonPool(poolFactory.createPythonHttpPool(
                    analisSurfacePool.baseUrls(),
                    preflight.bootConfig()
            ));
            if (processes.pythonPool().isEmpty()) {
                log.error(
                        "analisSurface FastAPI pool is empty (urls={}). "
                                + "Проверьте venv в analisSurface/backend, integration.analis_surface_autostart и python_detector.base_url.",
                        analisSurfacePool.baseUrls()
                );
                for (ExternalServiceProcess process : processes.analisSurfaceProcesses()) {
                    process.close();
                }
                closeQuietly(joinExternal(lightFuture, "light-server"));
                closeQuietly(joinExternal(ioFuture, "io-input-monitor"));
                closeQuietly(joinExternal(frontendFuture, "frontend"));
                return false;
            }
            log.info(
                    "python detector transport=http servers={} clients={} autostart={} urls={}",
                    analisSurfacePool.baseUrls().size(),
                    processes.pythonPool().size(),
                    analisSurfacePool.autostartEnabled(),
                    analisSurfacePool.baseUrls()
            );

            List<String> positioningCommand = CameraWorkerPaths.pickIntegrationCommandList(
                    preflight.integration(), env.windows(), "positioning_command_windows", "positioning_command_linux");

            // geometry + positioning пулы — параллельно (размер пулов не меняем).
            CompletableFuture<List<ServiceProcessSupervisor>> geometryFuture = CompletableFuture.supplyAsync(
                    () -> poolFactory.createGeometryPool(
                            preflight.bootConfig().geometryCommand(),
                            env.projectRoot(),
                            preflight.bootConfig()
                    ),
                    parallel
            );
            CompletableFuture<List<ServiceProcessSupervisor>> positioningFuture = CompletableFuture.supplyAsync(
                    () -> poolFactory.createPositioningPool(
                            env.root(),
                            preflight.integration(),
                            positioningCommand,
                            env.projectRoot(),
                            preflight.bootConfig()
                    ),
                    parallel
            );

            processes.setGeometryPool(joinPool(geometryFuture, "geometry"));
            processes.setPositioningPool(joinPool(positioningFuture, "positioning"));
            processes.setLightServerProcess(joinExternal(lightFuture, "light-server"));
            processes.setIoInputMonitorProcess(joinExternal(ioFuture, "io-input-monitor"));
            processes.setFrontendProcess(joinExternal(frontendFuture, "frontend"));

            processes.setPythonCfg(YamlMaps.stringObjectMapOrNull(env.root().get("python_detector")));
            processes.setGeometryCfg(YamlMaps.stringObjectMapOrNull(env.root().get("java_geometry")));
            Map<String, Object> positioningCfgEarly = YamlMaps.stringObjectMapOrNull(env.root().get("java_positioning"));
            boolean positioningEnabled = YamlScalars.toBool(
                    positioningCfgEarly == null ? null : positioningCfgEarly.get("enabled"),
                    true
            );
            processes.setPositioningCfg(positioningCfgEarly != null
                    ? positioningCfgEarly
                    : Map.of("enabled", positioningEnabled));
            processes.setUiCfg(YamlMaps.stringObjectMapOrNull(env.root().get("ui_http")));
            return true;
        } finally {
            parallel.shutdownNow();
            try {
                parallel.awaitTermination(5, TimeUnit.SECONDS);
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
            }
        }
    }

    private List<ServiceProcessSupervisor> joinPool(
            CompletableFuture<List<ServiceProcessSupervisor>> future,
            String label
    ) {
        try {
            List<ServiceProcessSupervisor> pool = future.join();
            return pool == null ? List.of() : pool;
        } catch (CompletionException e) {
            Throwable cause = e.getCause() != null ? e.getCause() : e;
            log.warn("failed to start {} pool in parallel: {}", label, cause.getMessage());
            return List.of();
        }
    }

    private ExternalServiceProcess joinExternal(
            CompletableFuture<ExternalServiceProcess> future,
            String label
    ) {
        try {
            return future.join();
        } catch (CompletionException e) {
            Throwable cause = e.getCause() != null ? e.getCause() : e;
            log.warn("failed to start {} in parallel: {}", label, cause.getMessage());
            return null;
        }
    }

    private static void closeQuietly(ExternalServiceProcess process) {
        if (process != null) {
            process.close();
        }
    }

    /** {@code IML_FRONTEND_AUTOSTART=false} — отключить UI при {@code run.ps1 -NoFrontend}. */
    private static boolean shouldAutostartFrontend() {
        String raw = System.getenv("IML_FRONTEND_AUTOSTART");
        return raw == null || !raw.equalsIgnoreCase("false");
    }
}

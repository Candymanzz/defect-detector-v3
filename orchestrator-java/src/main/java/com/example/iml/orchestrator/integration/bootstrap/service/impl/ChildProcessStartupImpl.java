package com.example.iml.orchestrator.integration.bootstrap.service.impl;

import com.example.iml.orchestrator.integration.bootstrap.service.api.ChildProcessStartup;
import com.example.iml.orchestrator.integration.bootstrap.service.api.AbstractBootstrapService;
import com.example.iml.orchestrator.integration.bootstrap.context.ChildProcessesContext;
import com.example.iml.orchestrator.integration.bootstrap.factory.IntegrationServicePoolFactory;
import com.example.iml.orchestrator.integration.config.YamlMaps;
import com.example.iml.orchestrator.integration.config.PythonDetectorConfig;
import com.example.iml.orchestrator.integration.config.YamlScalars;
import com.example.iml.orchestrator.integration.lighting.LightServerLauncher;
import com.example.iml.orchestrator.integration.python.AnalisSurfaceLauncher;
import com.example.iml.orchestrator.integration.subprocess.ExternalServiceProcess;
import com.example.iml.orchestrator.integration.subprocess.IntegrationExternalProcessLauncher;
import org.apache.logging.log4j.Logger;

import java.util.Map;
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
            ChildProcessParallelLaunchSupport.ExternalLaunchFutures externals =
                    ChildProcessParallelLaunchSupport.startExternalFutures(
                            processes, lightServerLauncher, externalProcessLauncher, parallel, log);

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
                ChildProcessParallelLaunchSupport.closeQuietly(
                        ChildProcessParallelLaunchSupport.joinExternal(externals.light(), "light-server", log));
                ChildProcessParallelLaunchSupport.closeQuietly(
                        ChildProcessParallelLaunchSupport.joinExternal(externals.io(), "io-input-monitor", log));
                ChildProcessParallelLaunchSupport.closeQuietly(
                        ChildProcessParallelLaunchSupport.joinExternal(externals.frontend(), "frontend", log));
                return false;
            }
            log.info(
                    "python detector transport=http servers={} clients={} autostart={} urls={}",
                    analisSurfacePool.baseUrls().size(),
                    processes.pythonPool().size(),
                    analisSurfacePool.autostartEnabled(),
                    analisSurfacePool.baseUrls()
            );

            // geometry + positioning пулы — параллельно (размер пулов не меняем).
            ChildProcessParallelLaunchSupport.startGeometryAndPositioning(processes, poolFactory, parallel, log);
            processes.setLightServerProcess(
                    ChildProcessParallelLaunchSupport.joinExternal(externals.light(), "light-server", log));
            processes.setIoInputMonitorProcess(
                    ChildProcessParallelLaunchSupport.joinExternal(externals.io(), "io-input-monitor", log));
            processes.setFrontendProcess(
                    ChildProcessParallelLaunchSupport.joinExternal(externals.frontend(), "frontend", log));

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
}

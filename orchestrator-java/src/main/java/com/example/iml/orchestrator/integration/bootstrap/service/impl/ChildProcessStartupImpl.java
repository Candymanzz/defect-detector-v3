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
import com.example.iml.orchestrator.integration.subprocess.ExternalServiceProcess;
import com.example.iml.orchestrator.integration.subprocess.IntegrationExternalProcessLauncher;
import com.example.iml.orchestrator.integration.trigger.config.InspectionTriggerConfig;
import org.apache.logging.log4j.Logger;

import java.util.List;
import java.util.Map;

/**
 * Только запуск child OS-процессов и RPC/HTTP пулов.
 */
public final class ChildProcessStartupImpl extends AbstractBootstrapService implements ChildProcessStartup {

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
            return false;
        }
        log.info(
                "python detector transport=http servers={} clients={} autostart={} urls={}",
                analisSurfacePool.baseUrls().size(),
                processes.pythonPool().size(),
                analisSurfacePool.autostartEnabled(),
                analisSurfacePool.baseUrls()
        );

        processes.setGeometryPool(poolFactory.createGeometryPool(
                preflight.bootConfig().geometryCommand(),
                env.projectRoot(),
                preflight.bootConfig()
        ));

        List<String> positioningCommand = CameraWorkerPaths.pickIntegrationCommandList(
                preflight.integration(), env.windows(), "positioning_command_windows", "positioning_command_linux");
        processes.setPositioningPool(poolFactory.createPositioningPool(
                env.root(),
                preflight.integration(),
                positioningCommand,
                env.projectRoot(),
                preflight.bootConfig()
        ));

        LightServersConfig lightServersCfg = LightServersConfig.fromRootYaml(env.root());
        if (lightServersCfg.enabled()) {
            processes.setLightServerProcess(lightServerLauncher.startIfConfigured(
                    preflight.integration(),
                    env.projectRoot(),
                    env.windows(),
                    preflight.bootConfig().lightStartupDelayMs()));
        } else {
            log.info("light_servers.enabled=false — LightServer не запускается, COM-вспышки отключены");
        }

        InspectionTriggerConfig inspectionTriggerConfig = InspectionTriggerConfig.parse(preflight.integration());
        if (inspectionTriggerConfig.usesIoInputMonitor()) {
            processes.setIoInputMonitorProcess(externalProcessLauncher.startIfConfigured(
                    preflight.integration(),
                    env.projectRoot(),
                    env.windows(),
                    "io_input_monitor_autostart",
                    "io_input_monitor_command_windows",
                    "io_input_monitor_command_linux",
                    "io-input-monitor",
                    "."
            ));
        }
        if (shouldAutostartFrontend()) {
            processes.setFrontendProcess(externalProcessLauncher.startIfConfigured(
                    preflight.integration(),
                    env.projectRoot(),
                    env.windows(),
                    "frontend_autostart",
                    "frontend_command_windows",
                    "frontend_command_linux",
                    "frontend",
                    "front-end"
            ));
        }

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
    }

    /** {@code IML_FRONTEND_AUTOSTART=false} — отключить UI при {@code run.ps1 -NoFrontend}. */
    private static boolean shouldAutostartFrontend() {
        String raw = System.getenv("IML_FRONTEND_AUTOSTART");
        return raw == null || !raw.equalsIgnoreCase("false");
    }
}

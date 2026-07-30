package com.example.iml.orchestrator.integration.bootstrap.config;

import com.example.iml.orchestrator.integration.camera.WorkerIpcMode;
import com.example.iml.orchestrator.integration.config.ReferenceSource;
import com.example.iml.orchestrator.integration.config.YamlScalars;

import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * Сборка {@link IntegrationBootConfig}: YAML → record и обогащение командами пулов.
 */
public final class IntegrationBootConfigMapper {

    private IntegrationBootConfigMapper() {
    }

    public static IntegrationBootConfig fromYaml(
            Map<String, Object> integration,
            int cameraCount,
            boolean isWindows
    ) {
        Map<String, Object> cfg = Optional.ofNullable(integration).orElseGet(Map::of);

        String defaultPipeTemplate = isWindows
                ? "\\\\.\\pipe\\iml-camera-%d-binary"
                : "/tmp/iml-camera-%d.pipe";
        String pipeKey = isWindows ? "worker_named_pipe_template" : "worker_named_pipe_template_linux";

        int cameraParallelism = Math.max(
                1,
                intValue(cfg, "camera_parallelism", Math.min(5, cameraCount))
        );
        int pythonParallelism = Math.max(
                1,
                intValue(cfg, "python_parallelism", Math.min(cameraParallelism, 2))
        );

        return new IntegrationBootConfig(
                WorkerIpcMode.fromConfig(cfg.get("worker_ipc_mode")),
                stringValue(cfg, pipeKey)
                        .or(() -> stringValue(cfg, "worker_named_pipe_template"))
                        .orElse(defaultPipeTemplate),
                intValue(cfg, "worker_named_pipe_connect_timeout_ms", 3000),
                intValue(cfg, "worker_command_timeout_ms", 5000),
                Math.max(0, intValue(cfg, "worker_startup_stagger_ms", 0)),
                Math.max(0, intValue(cfg, "capture_trigger_stagger_ms", 0)),
                intValue(cfg, "service_command_timeout_ms", 7000),
                intValue(cfg, "light_server_startup_delay_ms", 1200),
                cameraParallelism,
                Math.max(1, intValue(cfg, "geometry_pool_size", 2)),
                boolValue(cfg, "reload_reference", false),
                ReferenceSource.fromConfig(cfg.get("reference_source")),
                pythonParallelism,
                Math.max(1, intValue(cfg, "python_server_pool_size", pythonParallelism)),
                List.of(),
                List.of(),
                Math.max(1, intValue(cfg, "stage_queue_size", cameraParallelism * 2))
        );
    }

    /** Команды пулов подставляются снаружи (нужен projectRoot и pickIntegrationCommandList). */
    public static IntegrationBootConfig withPoolCommands(
            IntegrationBootConfig cfg,
            List<String> pythonCommand,
            List<String> geometryCommand
    ) {
        return new IntegrationBootConfig(
                cfg.workerIpcMode(),
                cfg.workerPipeTemplate(),
                cfg.workerPipeConnectTimeoutMs(),
                cfg.workerCommandTimeoutMs(),
                cfg.workerStartupStaggerMs(),
                cfg.captureTriggerStaggerMs(),
                cfg.serviceCommandTimeoutMs(),
                cfg.lightStartupDelayMs(),
                cfg.cameraParallelism(),
                cfg.geometryPoolSize(),
                cfg.reloadReference(),
                cfg.referenceSource(),
                cfg.pythonParallelism(),
                cfg.pythonServerPoolSize(),
                pythonCommand,
                geometryCommand,
                cfg.stageQueueSize()
        );
    }

    private static Optional<String> stringValue(Map<String, Object> cfg, String key) {
        return Optional.ofNullable(cfg.get(key))
                .map(String::valueOf)
                .filter(s -> !s.isBlank() && !"null".equalsIgnoreCase(s));
    }

    private static int intValue(Map<String, Object> cfg, String key, int fallback) {
        return YamlScalars.toInt(cfg.get(key), fallback);
    }

    private static boolean boolValue(Map<String, Object> cfg, String key, boolean fallback) {
        return YamlScalars.toBool(cfg.get(key), fallback);
    }
}

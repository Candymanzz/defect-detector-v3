package com.example.iml.orchestrator.integration.bootstrap.config;

import com.example.iml.orchestrator.integration.camera.WorkerIpcMode;
import com.example.iml.orchestrator.integration.config.YamlScalars;

import java.util.List;
import java.util.Map;

/**
 * Срез integration-* из YAML (без чтения камер, light, ui — только integration map).
 */
public record IntegrationBootConfig(
        WorkerIpcMode workerIpcMode,
        String workerPipeTemplate,
        int workerPipeConnectTimeoutMs,
        int workerCommandTimeoutMs,
        int serviceCommandTimeoutMs,
        int lightStartupDelayMs,
        int cameraParallelism,
        int geometryPoolSize,
        boolean reloadReference,
        int pythonParallelism,
        List<String> geometryCommand,
        int stageQueueSize
) {

    public static IntegrationBootConfig load(Map<String, Object> integration, int cameraCount, boolean isWindows) {
        WorkerIpcMode workerIpcMode = WorkerIpcMode.fromConfig(integration == null ? null : integration.get("worker_ipc_mode"));
        String defaultPipeTemplate = isWindows ? "\\\\.\\pipe\\iml-camera-%d-binary" : "/tmp/iml-camera-%d.pipe";
        String workerPipeTemplate = String.valueOf(integration == null
                ? defaultPipeTemplate
                : integration.getOrDefault(
                        isWindows ? "worker_named_pipe_template" : "worker_named_pipe_template_linux",
                        integration.getOrDefault("worker_named_pipe_template", defaultPipeTemplate)));
        int workerPipeConnectTimeoutMs = YamlScalars.toInt(integration == null ? null : integration.get("worker_named_pipe_connect_timeout_ms"), 3000);
        int workerCommandTimeoutMs = YamlScalars.toInt(integration == null ? null : integration.get("worker_command_timeout_ms"), 5000);
        int serviceCommandTimeoutMs = YamlScalars.toInt(integration == null ? null : integration.get("service_command_timeout_ms"), 7000);
        int lightStartupDelayMs = YamlScalars.toInt(integration == null ? null : integration.get("light_server_startup_delay_ms"), 1200);
        int cameraParallelism = Math.max(1, YamlScalars.toInt(integration == null ? null : integration.get("camera_parallelism"), Math.min(5, cameraCount)));
        int geometryPoolSize = Math.max(1, YamlScalars.toInt(integration == null ? null : integration.get("geometry_pool_size"), 2));
        boolean reloadReference = YamlScalars.toBool(integration == null ? null : integration.get("reload_reference"), false);
        int pythonParallelism = Math.max(1, YamlScalars.toInt(integration == null ? null : integration.get("python_parallelism"), Math.min(cameraParallelism, 2)));
        int stageQueueSize = Math.max(4, YamlScalars.toInt(integration == null ? null : integration.get("stage_queue_size"), cameraParallelism * 2));
        return new IntegrationBootConfig(
                workerIpcMode,
                workerPipeTemplate,
                workerPipeConnectTimeoutMs,
                workerCommandTimeoutMs,
                serviceCommandTimeoutMs,
                lightStartupDelayMs,
                cameraParallelism,
                geometryPoolSize,
                reloadReference,
                pythonParallelism,
                List.of(),
                stageQueueSize
        );
    }

    /** Команда пула java-geometry подставляется снаружи (нужен projectRoot и pickIntegrationCommandList). */
    public IntegrationBootConfig withGeometryCommand(List<String> geometryCommand) {
        return new IntegrationBootConfig(
                workerIpcMode,
                workerPipeTemplate,
                workerPipeConnectTimeoutMs,
                workerCommandTimeoutMs,
                serviceCommandTimeoutMs,
                lightStartupDelayMs,
                cameraParallelism,
                geometryPoolSize,
                reloadReference,
                pythonParallelism,
                geometryCommand,
                stageQueueSize
        );
    }
}

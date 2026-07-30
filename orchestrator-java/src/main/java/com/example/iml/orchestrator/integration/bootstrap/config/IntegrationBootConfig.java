package com.example.iml.orchestrator.integration.bootstrap.config;

import com.example.iml.orchestrator.integration.camera.WorkerIpcMode;
import com.example.iml.orchestrator.integration.config.ReferenceSource;

import java.util.List;

/**
 * Срез integration-* из YAML (без чтения камер, light, ui).
 * Сборка/обогащение — {@link IntegrationBootConfigMapper}.
 */
public record IntegrationBootConfig(
        WorkerIpcMode workerIpcMode,
        String workerPipeTemplate,
        int workerPipeConnectTimeoutMs,
        int workerCommandTimeoutMs,
        int workerStartupStaggerMs,
        int captureTriggerStaggerMs,
        int serviceCommandTimeoutMs,
        int lightStartupDelayMs,
        int cameraParallelism,
        int geometryPoolSize,
        boolean reloadReference,
        ReferenceSource referenceSource,
        int pythonParallelism,
        int pythonServerPoolSize,
        List<String> pythonCommand,
        List<String> geometryCommand,
        int stageQueueSize
) {
}

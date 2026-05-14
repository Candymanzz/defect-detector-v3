package com.example.iml.orchestrator.integration.pipeline.spi;

import com.example.iml.orchestrator.integration.config.IntegrationFeatureConfig;
import com.example.iml.orchestrator.integration.camera.WorkerProcessSupervisor;
import com.example.iml.orchestrator.integration.lighting.LightTriggerClient;
import com.example.iml.orchestrator.integration.pipeline.PipelineState;
import com.example.iml.orchestrator.integration.pipeline.ReferenceSnapshot;
import com.example.iml.orchestrator.protocol.BinaryProtocol;

import java.nio.file.Path;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;

/** Захват кадра с воркера камеры и сохранение эталона/текущего кадра. */
public interface CameraCaptureStage {

    void saveReferenceCapture(
            Path projectRoot,
            IntegrationFeatureConfig.SaveCapturesConfig saveCaptures,
            BinaryProtocol.Message referenceCapture
    );

    CompletableFuture<PipelineState> scheduleCapture(
            Path projectRoot,
            IntegrationFeatureConfig.SaveCapturesConfig saveCaptures,
            int cameraId,
            ReferenceSnapshot activeReference,
            int flashLeadMs,
            WorkerProcessSupervisor worker,
            LightTriggerClient lightClient,
            ExecutorService captureStageExecutor,
            String debugLogSuffix
    );

    PipelineState runCaptureSync(
            Path projectRoot,
            IntegrationFeatureConfig.SaveCapturesConfig saveCaptures,
            int cameraId,
            ReferenceSnapshot activeReference,
            int flashLeadMs,
            WorkerProcessSupervisor worker,
            LightTriggerClient lightClient,
            String debugLogSuffix
    );
}

package com.example.iml.orchestrator.integration.pipeline.spi;

import com.example.iml.orchestrator.integration.config.IntegrationFeatureConfig;
import com.example.iml.orchestrator.integration.camera.WorkerProcessSupervisor;
import com.example.iml.orchestrator.integration.lighting.LightTriggerClient;
import com.example.iml.orchestrator.integration.pipeline.PipelineState;
import com.example.iml.orchestrator.integration.pipeline.ReferenceSnapshot;
import com.example.iml.orchestrator.protocol.BinaryProtocol;

import java.nio.file.Path;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;

/** Захват кадра с воркера камеры и сохранение эталона/текущего кадра. */
public interface CameraCaptureStage {

    void saveReferenceCapture(
            Path projectRoot,
            IntegrationFeatureConfig.SaveCapturesConfig saveCaptures,
            BinaryProtocol.Message referenceCapture
    );

    /**
     * Optionally remap capture descriptor to downscaled SHM before downstream stages.
     */
    default BinaryProtocol.Message maybeDownscaleInspectionCapture(
            BinaryProtocol.Message capture,
            int cameraId
    ) {
        return capture;
    }

    /**
     * Optionally remap reference descriptor to downscaled SHM before set_reference_shm.
     */
    default Map<String, Object> maybeDownscaleReferenceHeader(
            Map<String, Object> referenceHeader,
            int cameraId
    ) {
        return referenceHeader;
    }

    /**
     * Optionally remap client reference-bundle frame descriptor to downscaled SHM.
     */
    default Map<String, Object> maybeDownscaleClientReferenceHeader(
            Map<String, Object> referenceHeader,
            int cameraId
    ) {
        return referenceHeader;
    }

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

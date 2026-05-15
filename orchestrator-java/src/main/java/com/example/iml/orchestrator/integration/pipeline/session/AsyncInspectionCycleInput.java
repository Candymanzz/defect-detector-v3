package com.example.iml.orchestrator.integration.pipeline.session;

import com.example.iml.orchestrator.integration.camera.WorkerProcessSupervisor;
import com.example.iml.orchestrator.integration.config.IntegrationFeatureConfig;
import com.example.iml.orchestrator.integration.fanout.FanOutCoordinator;
import com.example.iml.orchestrator.integration.lighting.LightTriggerClient;
import com.example.iml.orchestrator.integration.logging.PipelineStagesLog;
import com.example.iml.orchestrator.integration.pipeline.ReferenceSnapshot;
import com.example.iml.orchestrator.integration.binaryrpc.BinaryRpcSupervisor;
import com.example.iml.orchestrator.integration.ui.UiHttpServer;

import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Semaphore;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Все данные для одного асинхронного цикла capture→geometry→python→решение (без «божественного» списка параметров метода).
 */
public record AsyncInspectionCycleInput(
        Path projectRoot,
        IntegrationFeatureConfig.SaveCapturesConfig saveCaptures,
        int cameraId,
        String productType,
        String detectorId,
        ReferenceSnapshot activeReference,
        long referenceMsFinal,
        long tCameraStartNanos,
        WorkerProcessSupervisor worker,
        LightTriggerClient lightClient,
        List<? extends BinaryRpcSupervisor> pythonPool,
        List<? extends BinaryRpcSupervisor> geometryPool,
        Map<String, Object> pythonCfg,
        Map<String, Object> geometryCfg,
        FanOutCoordinator fanOut,
        Semaphore geometrySlots,
        Semaphore pythonSlots,
        AtomicInteger geometryRoundRobin,
        AtomicInteger pythonRoundRobin,
        ExecutorService captureStageExecutor,
        ExecutorService pythonStageExecutor,
        ExecutorService geometryStageExecutor,
        ExecutorService decisionStageExecutor,
        Map<String, Object> uiCfg,
        UiHttpServer uiServer,
        String analisSurfaceHttpBaseUrl,
        int analisSurfaceHttpTimeoutMs,
        ExecutorService uiArtifactsExecutor,
        int flashLeadMs,
        PipelineStagesLog pipelineStagesLog
) {

    /** Conveyor: меняется product_type эталона и тайминг ref по ведру. */
    public AsyncInspectionCycleInput withPerCycleIdentity(
            String productType,
            ReferenceSnapshot activeReference,
            long referenceMsFinal
    ) {
        return new AsyncInspectionCycleInput(
                projectRoot,
                saveCaptures,
                cameraId,
                productType,
                detectorId,
                activeReference,
                referenceMsFinal,
                tCameraStartNanos,
                worker,
                lightClient,
                pythonPool,
                geometryPool,
                pythonCfg,
                geometryCfg,
                fanOut,
                geometrySlots,
                pythonSlots,
                geometryRoundRobin,
                pythonRoundRobin,
                captureStageExecutor,
                pythonStageExecutor,
                geometryStageExecutor,
                decisionStageExecutor,
                uiCfg,
                uiServer,
                analisSurfaceHttpBaseUrl,
                analisSurfaceHttpTimeoutMs,
                uiArtifactsExecutor,
                flashLeadMs,
                pipelineStagesLog
        );
    }

    public static AsyncInspectionCycleInput of(
            Path projectRoot,
            IntegrationFeatureConfig.SaveCapturesConfig saveCaptures,
            int cameraId,
            String productType,
            String detectorId,
            ReferenceSnapshot activeReference,
            long referenceMsFinal,
            long tCameraStartNanos,
            WorkerProcessSupervisor worker,
            LightTriggerClient lightClient,
            List<? extends BinaryRpcSupervisor> pythonPool,
            List<? extends BinaryRpcSupervisor> geometryPool,
            Map<String, Object> pythonCfg,
            Map<String, Object> geometryCfg,
            FanOutCoordinator fanOut,
            Semaphore geometrySlots,
            Semaphore pythonSlots,
            AtomicInteger geometryRoundRobin,
            AtomicInteger pythonRoundRobin,
            ExecutorService captureStageExecutor,
            ExecutorService pythonStageExecutor,
            ExecutorService geometryStageExecutor,
            ExecutorService decisionStageExecutor,
            Map<String, Object> uiCfg,
            UiHttpServer uiServer,
            String analisSurfaceHttpBaseUrl,
            int analisSurfaceHttpTimeoutMs,
            ExecutorService uiArtifactsExecutor,
            int flashLeadMs,
            PipelineStagesLog pipelineStagesLog
    ) {
        return new AsyncInspectionCycleInput(
                projectRoot,
                saveCaptures,
                cameraId,
                productType,
                detectorId,
                activeReference,
                referenceMsFinal,
                tCameraStartNanos,
                worker,
                lightClient,
                pythonPool,
                geometryPool,
                pythonCfg,
                geometryCfg,
                fanOut,
                geometrySlots,
                pythonSlots,
                geometryRoundRobin,
                pythonRoundRobin,
                captureStageExecutor,
                pythonStageExecutor,
                geometryStageExecutor,
                decisionStageExecutor,
                uiCfg,
                uiServer,
                analisSurfaceHttpBaseUrl,
                analisSurfaceHttpTimeoutMs,
                uiArtifactsExecutor,
                flashLeadMs,
                pipelineStagesLog
        );
    }
}

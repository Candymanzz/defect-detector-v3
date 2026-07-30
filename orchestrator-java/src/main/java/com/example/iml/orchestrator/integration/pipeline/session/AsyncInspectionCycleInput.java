package com.example.iml.orchestrator.integration.pipeline.session;

import com.example.iml.orchestrator.integration.camera.WorkerProcessSupervisor;
import com.example.iml.orchestrator.integration.config.IntegrationFeatureConfig;
import com.example.iml.orchestrator.integration.fanout.FanOutCoordinator;
import com.example.iml.orchestrator.integration.logging.PipelineStagesLog;
import com.example.iml.orchestrator.integration.pipeline.ReferenceSnapshot;
import com.example.iml.orchestrator.integration.pipeline.bucket.BucketInspectionAggregator;
import com.example.iml.orchestrator.integration.binaryrpc.BinaryRpcSupervisor;
import com.example.iml.orchestrator.integration.pipeline.spi.CaptureLightingPort;

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
        CaptureLightingPort lighting,
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
        BinaryRpcSupervisor uiVisualsPython,
        int flashLeadMs,
        PipelineStagesLog pipelineStagesLog,
        long inspectionId,
        long triggerSequence,
        BucketInspectionAggregator bucketAggregator
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
                lighting,
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
                uiVisualsPython,
                flashLeadMs,
                pipelineStagesLog,
                inspectionId,
                triggerSequence,
                bucketAggregator
        );
    }

    public AsyncInspectionCycleInput withTriggerSequence(long triggerSequence) {
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
                lighting,
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
                uiVisualsPython,
                flashLeadMs,
                pipelineStagesLog,
                inspectionId,
                triggerSequence,
                bucketAggregator
        );
    }

    public AsyncInspectionCycleInput withInspectionId(long inspectionId) {
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
                lighting,
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
                uiVisualsPython,
                flashLeadMs,
                pipelineStagesLog,
                inspectionId,
                triggerSequence,
                bucketAggregator
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
            CaptureLightingPort lighting,
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
            BinaryRpcSupervisor uiVisualsPython,
            int flashLeadMs,
            PipelineStagesLog pipelineStagesLog,
            long inspectionId,
            long triggerSequence,
            BucketInspectionAggregator bucketAggregator
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
                lighting,
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
                uiVisualsPython,
                flashLeadMs,
                pipelineStagesLog,
                inspectionId,
                triggerSequence,
                bucketAggregator
        );
    }

    /** Build cycle input from shared deps + per-camera identity. */
    public static AsyncInspectionCycleInput fromDeps(
            Path projectRoot,
            IntegrationFeatureConfig.SaveCapturesConfig saveCaptures,
            int cameraId,
            String productType,
            String detectorId,
            ReferenceSnapshot activeReference,
            long referenceMsFinal,
            long tCameraStartNanos,
            WorkerProcessSupervisor worker,
            com.example.iml.orchestrator.integration.pipeline.CameraInspectionDeps deps
    ) {
        return of(
                projectRoot,
                saveCaptures,
                cameraId,
                productType,
                detectorId,
                activeReference,
                referenceMsFinal,
                tCameraStartNanos,
                worker,
                deps.lighting(),
                deps.pythonPool(),
                deps.geometryPool(),
                deps.pythonCfg(),
                deps.geometryCfg(),
                deps.fanOut(),
                deps.geometrySlots(),
                deps.pythonSlots(),
                deps.geometryRoundRobin(),
                deps.pythonRoundRobin(),
                deps.captureStageExecutor(),
                deps.pythonStageExecutor(),
                deps.geometryStageExecutor(),
                deps.decisionStageExecutor(),
                deps.uiVisualsPython(),
                deps.flashLeadMs(),
                deps.pipelineStagesLog(),
                0L,
                0L,
                deps.bucketAggregator()
        );
    }
}

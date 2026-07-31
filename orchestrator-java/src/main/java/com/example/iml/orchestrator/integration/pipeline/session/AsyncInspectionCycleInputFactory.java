package com.example.iml.orchestrator.integration.pipeline.session;

import com.example.iml.orchestrator.integration.camera.WorkerProcessSupervisor;
import com.example.iml.orchestrator.integration.config.IntegrationFeatureConfig;
import com.example.iml.orchestrator.integration.fanout.FanOutCoordinator;
import com.example.iml.orchestrator.integration.logging.PipelineStagesLog;
import com.example.iml.orchestrator.integration.pipeline.CameraInspectionDeps;
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

/** Factory and wither helpers for {@link AsyncInspectionCycleInput}. */
final class AsyncInspectionCycleInputFactory {

    private AsyncInspectionCycleInputFactory() {
    }

    static AsyncInspectionCycleInput withPerCycleIdentity(
            AsyncInspectionCycleInput src,
            String productType,
            ReferenceSnapshot activeReference,
            long referenceMsFinal
    ) {
        return copy(src, productType, activeReference, referenceMsFinal, src.inspectionId(), src.triggerSequence());
    }

    static AsyncInspectionCycleInput withTriggerSequence(AsyncInspectionCycleInput src, long triggerSequence) {
        return copy(src, src.productType(), src.activeReference(), src.referenceMsFinal(), src.inspectionId(), triggerSequence);
    }

    static AsyncInspectionCycleInput withInspectionId(AsyncInspectionCycleInput src, long inspectionId) {
        return copy(src, src.productType(), src.activeReference(), src.referenceMsFinal(), inspectionId, src.triggerSequence());
    }

    private static AsyncInspectionCycleInput copy(
            AsyncInspectionCycleInput src,
            String productType,
            ReferenceSnapshot activeReference,
            long referenceMsFinal,
            long inspectionId,
            long triggerSequence
    ) {
        return new AsyncInspectionCycleInput(
                src.projectRoot(), src.saveCaptures(), src.cameraId(), productType, src.detectorId(),
                activeReference, referenceMsFinal, src.tCameraStartNanos(), src.worker(), src.lighting(),
                src.pythonPool(), src.geometryPool(), src.pythonCfg(), src.geometryCfg(), src.fanOut(),
                src.geometrySlots(), src.pythonSlots(), src.geometryRoundRobin(), src.pythonRoundRobin(),
                src.captureStageExecutor(), src.pythonStageExecutor(), src.geometryStageExecutor(),
                src.decisionStageExecutor(), src.uiVisualsPython(), src.flashLeadMs(), src.pipelineStagesLog(),
                inspectionId, triggerSequence, src.bucketAggregator()
        );
    }

    static AsyncInspectionCycleInput of(
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
                projectRoot, saveCaptures, cameraId, productType, detectorId, activeReference, referenceMsFinal,
                tCameraStartNanos, worker, lighting, pythonPool, geometryPool, pythonCfg, geometryCfg, fanOut,
                geometrySlots, pythonSlots, geometryRoundRobin, pythonRoundRobin, captureStageExecutor,
                pythonStageExecutor, geometryStageExecutor, decisionStageExecutor, uiVisualsPython, flashLeadMs,
                pipelineStagesLog, inspectionId, triggerSequence, bucketAggregator
        );
    }

    static AsyncInspectionCycleInput fromDeps(
            Path projectRoot,
            IntegrationFeatureConfig.SaveCapturesConfig saveCaptures,
            int cameraId,
            String productType,
            String detectorId,
            ReferenceSnapshot activeReference,
            long referenceMsFinal,
            long tCameraStartNanos,
            WorkerProcessSupervisor worker,
            CameraInspectionDeps deps
    ) {
        return of(
                projectRoot, saveCaptures, cameraId, productType, detectorId, activeReference, referenceMsFinal,
                tCameraStartNanos, worker, deps.lighting(), deps.pythonPool(), deps.geometryPool(),
                deps.pythonCfg(), deps.geometryCfg(), deps.fanOut(), deps.geometrySlots(), deps.pythonSlots(),
                deps.geometryRoundRobin(), deps.pythonRoundRobin(), deps.captureStageExecutor(),
                deps.pythonStageExecutor(), deps.geometryStageExecutor(), deps.decisionStageExecutor(),
                deps.uiVisualsPython(), deps.flashLeadMs(), deps.pipelineStagesLog(), 0L, 0L, deps.bucketAggregator()
        );
    }
}

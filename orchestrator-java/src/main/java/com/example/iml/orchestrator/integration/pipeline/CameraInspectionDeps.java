package com.example.iml.orchestrator.integration.pipeline;

import com.example.iml.orchestrator.integration.binaryrpc.BinaryRpcSupervisor;
import com.example.iml.orchestrator.integration.config.ReferenceSource;
import com.example.iml.orchestrator.integration.fanout.FanOutCoordinator;
import com.example.iml.orchestrator.integration.logging.PipelineStagesLog;
import com.example.iml.orchestrator.integration.pipeline.bucket.BucketInspectionAggregator;
import com.example.iml.orchestrator.integration.pipeline.session.PerCameraInspectionGate;
import com.example.iml.orchestrator.integration.pipeline.spi.CaptureLightingPort;

import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Semaphore;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Shared runtime deps for per-camera inspection (pools, lighting, UI visuals RPC, gates).
 */
public record CameraInspectionDeps(
        List<? extends BinaryRpcSupervisor> pythonPool,
        List<? extends BinaryRpcSupervisor> geometryPool,
        CaptureLightingPort lighting,
        Map<String, Object> pythonCfg,
        Map<String, Object> geometryCfg,
        FanOutCoordinator fanOut,
        Semaphore geometrySlots,
        Semaphore pythonSlots,
        AtomicInteger geometryRoundRobin,
        AtomicInteger pythonRoundRobin,
        Map<Integer, ReferenceSnapshot> referenceByCamera,
        ReferenceSource referenceSource,
        boolean reloadReferenceGlobal,
        ExecutorService captureStageExecutor,
        ExecutorService pythonStageExecutor,
        ExecutorService geometryStageExecutor,
        ExecutorService decisionStageExecutor,
        BinaryRpcSupervisor uiVisualsPython,
        int flashLeadMs,
        PipelineStagesLog pipelineStagesLog,
        PerCameraInspectionGate inspectionGate,
        long inspectionCycleTimeoutMs,
        BucketInspectionAggregator bucketAggregator
) {
}

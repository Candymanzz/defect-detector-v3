package com.example.iml.orchestrator.integration.pipeline.session;

import com.example.iml.orchestrator.integration.config.IntegrationFeatureConfig;
import com.example.iml.orchestrator.integration.config.YamlScalars;
import com.example.iml.orchestrator.integration.pipeline.InspectionDecision;
import com.example.iml.orchestrator.integration.pipeline.InspectionPipelineServices;
import com.example.iml.orchestrator.integration.pipeline.PipelineState;
import com.example.iml.orchestrator.integration.pipeline.ReferenceSnapshot;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.CompletableFuture;

/**
 * Режим single_frame_benchmark: один capture, многократно geometry→python→решение.
 */
public final class SingleFrameBenchmarkOrchestrator {

    private SingleFrameBenchmarkOrchestrator() {
    }

    public static void run(
            InspectionPipelineServices svc,
            AsyncInspectionCycleInput in,
            IntegrationFeatureConfig.SingleFrameBenchmarkConfig singleFrameBenchmark,
            long tCameraStartNanos,
            long referenceMsFinal,
            ReferenceSnapshot activeReference
    ) throws Exception {
        int cameraId = in.cameraId();
        long tBench0 = System.nanoTime();
        PipelineState captureState = svc.captureStage().scheduleCapture(
                in.projectRoot(),
                in.saveCaptures(),
                cameraId,
                activeReference,
                in.flashLeadMs(),
                in.worker(),
                in.lightClient(),
                in.captureStageExecutor(),
                "benchmark capture"
        ).get();
        for (int iter = 0; iter < singleFrameBenchmark.inspectionRepeats(); iter++) {
            long tIter0 = System.nanoTime();
            PipelineState afterGeom = CompletableFuture.supplyAsync(
                    () -> svc.geometryStage().apply(
                            captureState,
                            cameraId,
                            activeReference,
                            in.geometryCfg(),
                            in.geometryPool(),
                            in.geometrySlots(),
                            in.geometryRoundRobin()
                    ),
                    in.geometryStageExecutor()
            ).get();
            PipelineState afterPy = CompletableFuture.supplyAsync(
                    () -> svc.pythonStage().apply(
                            afterGeom,
                            cameraId,
                            in.productType(),
                            in.detectorId(),
                            in.pythonCfg(),
                            in.pythonPool(),
                            in.pythonSlots(),
                            in.pythonRoundRobin()
                    ),
                    in.pythonStageExecutor()
            ).get();
            long tDecision0 = System.nanoTime();
            InspectionDecision decision = svc.decisionPolicy().decide(cameraId, afterPy.capture(), afterPy.py(), afterPy.geom());
            long tAfterDecision = System.nanoTime();
            long inspectionCycleEndNanos = System.nanoTime();
            svc.afterInspectionSidecar().scheduleAfterInspection(
                    in.uiServer(),
                    in.uiCfg(),
                    in.analisSurfaceHttpBaseUrl(),
                    in.analisSurfaceHttpTimeoutMs(),
                    in.uiArtifactsExecutor(),
                    cameraId,
                    in.productType(),
                    in.detectorId(),
                    afterPy.capture(),
                    afterPy.py(),
                    afterPy.geom(),
                    inspectionCycleEndNanos
            );
            in.fanOut().publish(svc.fanOutEventFactory().toFanOut(decision));
            long tAfterFanout = System.nanoTime();
            long iterMs = YamlScalars.nanosToMs(System.nanoTime() - tIter0);
            Map<String, Object> pyHeader = afterPy.py() == null ? Map.of() : afterPy.py().header();
            svc.log().info("single_frame_benchmark_iteration cam={} iter={}/{} iter_total_ms={} py_total_ms={}",
                    cameraId,
                    iter + 1,
                    singleFrameBenchmark.inspectionRepeats(),
                    iterMs,
                    YamlScalars.toDouble(pyHeader.get("stage_ms_total"), 0.0));
            InspectionStageTimingLogger.logStageTiming(
                    svc.log(),
                    cameraId,
                    decision,
                    tCameraStartNanos,
                    referenceMsFinal,
                    afterPy,
                    tDecision0,
                    tAfterDecision,
                    tAfterFanout
            );
            Map<String, Object> benchExtras = new LinkedHashMap<>();
            benchExtras.put("single_frame_benchmark_iter", iter + 1);
            benchExtras.put("single_frame_benchmark_iters_total", singleFrameBenchmark.inspectionRepeats());
            svc.pipelineTelemetry().logInspectionCycle(
                    in.pipelineStagesLog(),
                    benchExtras,
                    cameraId,
                    in.productType(),
                    in.detectorId(),
                    referenceMsFinal,
                    YamlScalars.nanosToMs(tAfterFanout - tCameraStartNanos),
                    afterPy,
                    decision,
                    tDecision0,
                    tAfterDecision,
                    tAfterFanout
            );
        }
        long totalBenchMs = YamlScalars.nanosToMs(System.nanoTime() - tBench0);
        svc.log().info("single_frame_benchmark_summary cam={} reference_repeats={} inspection_repeats={} capture_ms={} benchmark_wall_ms={} all_in_since_camera_thread_ms={}",
                cameraId,
                singleFrameBenchmark.referenceRepeats(),
                singleFrameBenchmark.inspectionRepeats(),
                captureState.captureMs(),
                totalBenchMs,
                YamlScalars.nanosToMs(System.nanoTime() - tCameraStartNanos));
    }
}

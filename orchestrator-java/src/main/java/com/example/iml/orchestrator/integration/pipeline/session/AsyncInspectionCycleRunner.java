package com.example.iml.orchestrator.integration.pipeline.session;

import com.example.iml.orchestrator.integration.config.YamlScalars;
import com.example.iml.orchestrator.integration.pipeline.InspectionDecision;
import com.example.iml.orchestrator.integration.pipeline.InspectionPipelineServices;
import com.example.iml.orchestrator.integration.pipeline.PipelineState;

import java.util.Map;
import java.util.concurrent.CompletableFuture;

/**
 * Один полный асинхронный цикл инспекции (capture → geometry → python → решение → fan-out → логи).
 */
public final class AsyncInspectionCycleRunner {

    private AsyncInspectionCycleRunner() {
    }

    public static void run(
            InspectionPipelineServices svc,
            AsyncInspectionCycleInput in,
            Map<String, Object> timingExtras
    ) {
        CompletableFuture<PipelineState> captureFuture = svc.captureStage().scheduleCapture(
                in.projectRoot(),
                in.saveCaptures(),
                in.cameraId(),
                in.activeReference(),
                in.flashLeadMs(),
                in.worker(),
                in.lightClient(),
                in.captureStageExecutor(),
                "current capture"
        );

        CompletableFuture<PipelineState> geometryFuture = captureFuture.thenApplyAsync(
                state -> svc.geometryStage().apply(
                        state,
                        in.cameraId(),
                        in.activeReference(),
                        in.geometryCfg(),
                        in.geometryPool(),
                        in.geometrySlots(),
                        in.geometryRoundRobin()
                ),
                in.geometryStageExecutor()
        );

        CompletableFuture<PipelineState> pythonFuture = geometryFuture.thenApplyAsync(
                state -> svc.pythonStage().apply(
                        state,
                        in.cameraId(),
                        in.productType(),
                        in.detectorId(),
                        in.pythonCfg(),
                        in.pythonPool(),
                        in.pythonSlots(),
                        in.pythonRoundRobin()
                ),
                in.pythonStageExecutor()
        );

        CompletableFuture<Void> decisionFuture = pythonFuture.thenAcceptAsync(state -> {
            long tDecision0 = System.nanoTime();
            InspectionDecision decision = svc.decisionPolicy().decide(
                    in.cameraId(), state.capture(), state.py(), state.geom());
            long tDecisionDone = System.nanoTime();
            svc.afterInspectionSidecar().scheduleAfterInspection(
                    in.uiServer(),
                    in.uiCfg(),
                    in.uiVisualsPython(),
                    in.uiArtifactsExecutor(),
                    in.cameraId(),
                    in.productType(),
                    in.detectorId(),
                    state.capture(),
                    state.geom()
            );
            in.fanOut().publish(svc.fanOutEventFactory().toFanOut(decision));
            long tFanoutDone = System.nanoTime();
            long totalMs = YamlScalars.nanosToMs(tFanoutDone - in.tCameraStartNanos());
            InspectionStageTimingLogger.logStageTiming(
                    svc.log(),
                    in.cameraId(),
                    decision,
                    in.tCameraStartNanos(),
                    in.referenceMsFinal(),
                    state,
                    tDecision0,
                    tDecisionDone,
                    tFanoutDone
            );
            svc.pipelineTelemetry().logInspectionCycle(
                    in.pipelineStagesLog(),
                    timingExtras,
                    in.cameraId(),
                    in.productType(),
                    in.detectorId(),
                    in.referenceMsFinal(),
                    totalMs,
                    state,
                    decision,
                    tDecision0,
                    tDecisionDone,
                    tFanoutDone
            );
        }, in.decisionStageExecutor());

        decisionFuture.join();
    }
}

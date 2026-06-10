package com.example.iml.orchestrator.integration.pipeline.session;

import com.example.iml.orchestrator.integration.config.YamlScalars;
import com.example.iml.orchestrator.integration.pipeline.InspectionDecision;
import com.example.iml.orchestrator.integration.pipeline.InspectionPipelineServices;
import com.example.iml.orchestrator.integration.pipeline.PipelineState;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

/**
 * Один полный асинхронный цикл инспекции (capture → geometry → python → решение → fan-out → логи).
 */
public final class AsyncInspectionCycleRunner {

    private AsyncInspectionCycleRunner() {
    }

    public static void run(
            InspectionPipelineServices svc,
            AsyncInspectionCycleInput in,
            Map<String, Object> timingExtras,
            long timeoutMs
    ) throws TimeoutException {
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
                        in.productType(),
                        in.activeReference(),
                        in.geometryCfg(),
                        in.pythonCfg(),
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
                        in.activeReference(),
                        in.pythonCfg(),
                        in.pythonPool(),
                        in.pythonSlots(),
                        in.pythonRoundRobin()
                ),
                in.pythonStageExecutor()
        );

        CompletableFuture<Void> decisionFuture = pythonFuture.thenAcceptAsync(state -> {
            long tDecision0 = System.nanoTime();
            long captureFrameTimestampNs = YamlScalars.toLong(
                    state.capture() == null || state.capture().header() == null
                            ? null
                            : state.capture().header().get("timestamp_ns"),
                    -1L
            );
            long captureToGeometryDoneMs = YamlScalars.nanosToMs(state.captureMs() + state.geometryMs());
            long captureToPythonDoneMs = YamlScalars.nanosToMs(state.captureMs() + state.geometryMs() + state.pythonMs());
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
                    in.activeReference(),
                    decision,
                    state.capture(),
                    state.geom()
            );
            in.fanOut().publish(svc.fanOutEventFactory().toFanOut(decision));
            long tFanoutDone = System.nanoTime();
            long totalMs = YamlScalars.nanosToMs(tFanoutDone - in.tCameraStartNanos());
            long captureFrameToInspectionEndMs = captureFrameTimestampNs > 0
                    ? YamlScalars.nanosToMs(System.nanoTime() - captureFrameTimestampNs)
                    : -1L;
            svc.log().info(
                    "inspection_timing cam={} frame={} capture_to_geometry_done_ms={} capture_to_python_done_ms={} capture_frame_to_inspection_end_ms={}",
                    in.cameraId(),
                    decision.frameId(),
                    captureToGeometryDoneMs,
                    captureToPythonDoneMs,
                    captureFrameToInspectionEndMs >= 0 ? captureFrameToInspectionEndMs : "unknown"
            );
            Map<String, Object> telemetryExtras = new LinkedHashMap<>();
            if (timingExtras != null && !timingExtras.isEmpty()) {
                telemetryExtras.putAll(timingExtras);
            }
            telemetryExtras.put("capture_to_geometry_done_ms", captureToGeometryDoneMs);
            telemetryExtras.put("capture_to_python_done_ms", captureToPythonDoneMs);
            if (captureFrameToInspectionEndMs >= 0) {
                telemetryExtras.put("capture_frame_to_inspection_end_ms", captureFrameToInspectionEndMs);
                telemetryExtras.put("capture_frame_to_inspection_end_s", captureFrameToInspectionEndMs / 1000.0);
            }
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
                    telemetryExtras,
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

        if (timeoutMs > 0) {
            try {
                // SLA timeout applies only until python stage completion.
                pythonFuture.get(timeoutMs, TimeUnit.MILLISECONDS);
                // Decision/fan-out stays outside timeout window.
                decisionFuture.join();
            } catch (TimeoutException e) {
                pythonFuture.cancel(true);
                decisionFuture.cancel(true);
                throw e;
            } catch (ExecutionException e) {
                Throwable cause = e.getCause() == null ? e : e.getCause();
                if (cause instanceof RuntimeException runtime) {
                    throw runtime;
                }
                throw new RuntimeException(cause);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new RuntimeException(e);
            }
        } else {
            decisionFuture.join();
        }
    }
}

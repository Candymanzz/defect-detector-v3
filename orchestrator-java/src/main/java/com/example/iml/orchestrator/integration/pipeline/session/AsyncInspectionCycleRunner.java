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

    private static final long CANCEL_POLL_INTERVAL_MS = 50L;

    private AsyncInspectionCycleRunner() {
    }

    public static void run(
            InspectionPipelineServices svc,
            AsyncInspectionCycleInput in,
            Map<String, Object> timingExtras,
            long timeoutMs,
            PerCameraInspectionGate inspectionGate
    ) throws TimeoutException {
        if (inspectionGate != null && inspectionGate.isCancelRequested(in.cameraId())) {
            svc.log().info("integration cam={}: inspection cycle cancelled before start", in.cameraId());
            return;
        }
        CompletableFuture<PipelineState> captureFuture = svc.captureStage().scheduleCapture(
                in.projectRoot(),
                in.saveCaptures(),
                in.cameraId(),
                in.activeReference(),
                in.flashLeadMs(),
                in.worker(),
                in.lightClient(),
                in.captureStageExecutor(),
                in.triggerSequence(),
                "current capture"
        );
        if (inspectionGate != null && inspectionGate.isCancelRequested(in.cameraId())) {
            captureFuture.cancel(true);
            svc.log().info("integration cam={}: inspection cycle cancelled during capture stage", in.cameraId());
            return;
        }

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
        if (inspectionGate != null && inspectionGate.isCancelRequested(in.cameraId())) {
            geometryFuture.cancel(true);
            svc.log().info("integration cam={}: inspection cycle cancelled before geometry stage", in.cameraId());
            return;
        }

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
        if (inspectionGate != null && inspectionGate.isCancelRequested(in.cameraId())) {
            pythonFuture.cancel(true);
            svc.log().info("integration cam={}: inspection cycle cancelled before python stage", in.cameraId());
            return;
        }

        CompletableFuture<Void> decisionFuture = pythonFuture.thenAcceptAsync(state -> {
            if (inspectionGate != null && inspectionGate.isCancelRequested(in.cameraId())) {
                svc.log().info("integration cam={}: inspection cycle cancelled before decision stage", in.cameraId());
                return;
            }
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
            boolean resultPublished = inspectionGate == null || inspectionGate.runIfInspectionActive(in.cameraId(), () -> {
                if (in.bucketAggregator() != null) {
                    in.bucketAggregator().recordFrameResult(
                            in.triggerSequence(),
                            in.cameraId(),
                            decision,
                            in.fanOut()
                    );
                }
                try {
                    svc.afterInspectionSidecar().scheduleAfterInspection(
                            in.uiServer(),
                            in.uiCfg(),
                            in.uiVisualsPython(),
                            in.uiArtifactsExecutor(),
                            in.cameraId(),
                            in.productType(),
                            in.detectorId(),
                            in.inspectionId(),
                            in.activeReference(),
                            decision,
                            state.capture(),
                            state.py(),
                            state.geom()
                    );
                } catch (RuntimeException e) {
                    svc.afterInspectionSidecar().discardInspectionArtifacts(state.py());
                    svc.log().warn(
                            "ui artifact scheduling failed camera_id={} frame_id={}: {}",
                            in.cameraId(),
                            decision.frameId(),
                            e.getMessage()
                    );
                }
            });
            if (!resultPublished) {
                svc.afterInspectionSidecar().discardInspectionArtifacts(state.py());
                svc.log().info("integration cam={}: inspection result suppressed by client stop", in.cameraId());
                return;
            }
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
                boolean pythonCompleted = awaitPythonOrCancel(
                        captureFuture,
                        geometryFuture,
                        pythonFuture,
                        decisionFuture,
                        timeoutMs,
                        inspectionGate,
                        in.cameraId(),
                        svc
                );
                if (!pythonCompleted) {
                    return;
                }
                // Decision/fan-out stays outside timeout window.
                decisionFuture.join();
            } catch (TimeoutException e) {
                cancelInspectionFutures(captureFuture, geometryFuture, pythonFuture, decisionFuture);
                throw e;
            } catch (ExecutionException e) {
                if (inspectionGate != null && inspectionGate.isCancelRequested(in.cameraId())) {
                    cancelInspectionFutures(captureFuture, geometryFuture, pythonFuture, decisionFuture);
                    svc.log().info("integration cam={}: inspection cycle cancelled", in.cameraId());
                    return;
                }
                Throwable cause = e.getCause() == null ? e : e.getCause();
                if (cause instanceof RuntimeException runtime) {
                    throw runtime;
                }
                throw new RuntimeException(cause);
            } catch (InterruptedException e) {
                if (inspectionGate != null && inspectionGate.isCancelRequested(in.cameraId())) {
                    cancelInspectionFutures(captureFuture, geometryFuture, pythonFuture, decisionFuture);
                    svc.log().info("integration cam={}: inspection cycle interrupted by cancel", in.cameraId());
                    return;
                }
                Thread.currentThread().interrupt();
                throw new RuntimeException(e);
            }
        } else {
            awaitDecisionOrCancel(
                    decisionFuture,
                    inspectionGate,
                    in.cameraId(),
                    svc,
                    captureFuture,
                    geometryFuture,
                    pythonFuture,
                    decisionFuture
            );
        }
    }

    private static boolean awaitPythonOrCancel(
            CompletableFuture<PipelineState> captureFuture,
            CompletableFuture<PipelineState> geometryFuture,
            CompletableFuture<PipelineState> pythonFuture,
            CompletableFuture<Void> decisionFuture,
            long timeoutMs,
            PerCameraInspectionGate inspectionGate,
            int cameraId,
            InspectionPipelineServices svc
    ) throws TimeoutException, ExecutionException, InterruptedException {
        long deadlineNanos = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(timeoutMs);
        while (true) {
            if (cancelIfRequested(
                    inspectionGate,
                    cameraId,
                    svc,
                    captureFuture,
                    geometryFuture,
                    pythonFuture,
                    decisionFuture
            )) {
                return false;
            }
            long remainingNanos = deadlineNanos - System.nanoTime();
            if (remainingNanos <= 0) {
                throw new TimeoutException();
            }
            long waitMs = Math.min(
                    CANCEL_POLL_INTERVAL_MS,
                    Math.max(1L, TimeUnit.NANOSECONDS.toMillis(remainingNanos))
            );
            try {
                pythonFuture.get(waitMs, TimeUnit.MILLISECONDS);
                return true;
            } catch (TimeoutException ignored) {
                // Poll the cancellation flag until the stage completes or the SLA deadline expires.
            }
        }
    }

    private static void awaitDecisionOrCancel(
            CompletableFuture<Void> decisionFuture,
            PerCameraInspectionGate inspectionGate,
            int cameraId,
            InspectionPipelineServices svc,
            CompletableFuture<?>... futures
    ) {
        while (!decisionFuture.isDone()) {
            if (cancelIfRequested(inspectionGate, cameraId, svc, futures)) {
                return;
            }
            try {
                decisionFuture.get(CANCEL_POLL_INTERVAL_MS, TimeUnit.MILLISECONDS);
                return;
            } catch (TimeoutException ignored) {
                // Continue polling cancellation.
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
        }
        decisionFuture.join();
    }

    private static boolean cancelIfRequested(
            PerCameraInspectionGate inspectionGate,
            int cameraId,
            InspectionPipelineServices svc,
            CompletableFuture<?>... futures
    ) {
        if (inspectionGate == null || !inspectionGate.isCancelRequested(cameraId)) {
            return false;
        }
        for (CompletableFuture<?> future : futures) {
            future.cancel(true);
        }
        svc.log().info("integration cam={}: inspection cycle cancelled by client", cameraId);
        return true;
    }

    private static void cancelInspectionFutures(CompletableFuture<?>... futures) {
        for (CompletableFuture<?> future : futures) {
            future.cancel(true);
        }
    }
}

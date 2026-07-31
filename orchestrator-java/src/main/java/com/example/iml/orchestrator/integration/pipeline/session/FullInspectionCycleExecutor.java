package com.example.iml.orchestrator.integration.pipeline.session;

import com.example.iml.orchestrator.integration.pipeline.InspectionPipelineServices;
import com.example.iml.orchestrator.integration.pipeline.PipelineException;
import com.example.iml.orchestrator.integration.pipeline.PipelineState;

import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeoutException;

/**
 * Полный асинхронный цикл: capture → geometry → python → решение → sidecar / логи.
 */
final class FullInspectionCycleExecutor {

    private FullInspectionCycleExecutor() {
    }

    static void run(
            InspectionPipelineServices svc,
            AsyncInspectionCycleInput in,
            Map<String, Object> timingExtras,
            long timeoutMs,
            PerCameraInspectionGate inspectionGate
    ) throws TimeoutException {
        CompletableFuture<PipelineState> captureFuture = svc.captureStage().scheduleCapture(
                in.projectRoot(),
                in.saveCaptures(),
                in.cameraId(),
                in.activeReference(),
                in.flashLeadMs(),
                in.worker(),
                in.lighting(),
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

        CompletableFuture<Void> decisionFuture = pythonFuture.thenAcceptAsync(
                state -> FullInspectionDecisionStage.accept(svc, in, timingExtras, inspectionGate, state),
                in.decisionStageExecutor()
        );

        if (timeoutMs > 0) {
            try {
                // SLA timeout applies only until python stage completion.
                boolean pythonCompleted = CycleCancelSupport.awaitPythonOrCancel(
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
                CycleCancelSupport.cancelInspectionFutures(captureFuture, geometryFuture, pythonFuture, decisionFuture);
                throw e;
            } catch (ExecutionException e) {
                if (inspectionGate != null && inspectionGate.isCancelRequested(in.cameraId())) {
                    CycleCancelSupport.cancelInspectionFutures(captureFuture, geometryFuture, pythonFuture, decisionFuture);
                    svc.log().info("integration cam={}: inspection cycle cancelled", in.cameraId());
                    return;
                }
                Throwable cause = e.getCause() == null ? e : e.getCause();
                if (cause instanceof RuntimeException runtime) {
                    throw runtime;
                }
                throw new PipelineException(cause);
            } catch (InterruptedException e) {
                if (inspectionGate != null && inspectionGate.isCancelRequested(in.cameraId())) {
                    CycleCancelSupport.cancelInspectionFutures(captureFuture, geometryFuture, pythonFuture, decisionFuture);
                    svc.log().info("integration cam={}: inspection cycle interrupted by cancel", in.cameraId());
                    return;
                }
                Thread.currentThread().interrupt();
                throw new PipelineException(e);
            }
        } else {
            CycleCancelSupport.awaitDecisionOrCancel(
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
}

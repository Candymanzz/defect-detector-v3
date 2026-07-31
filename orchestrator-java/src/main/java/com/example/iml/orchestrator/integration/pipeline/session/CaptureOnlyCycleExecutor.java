package com.example.iml.orchestrator.integration.pipeline.session;

import com.example.iml.orchestrator.integration.pipeline.PipelineException;

import com.example.iml.orchestrator.integration.config.YamlScalars;
import com.example.iml.orchestrator.integration.pipeline.InspectionDecision;
import com.example.iml.orchestrator.integration.pipeline.InspectionPipelineServices;
import com.example.iml.orchestrator.integration.pipeline.PipelineState;
import com.example.iml.orchestrator.integration.pipeline.ReferenceSnapshot;

import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

/** Capture-only path when no usable reference is available. */
final class CaptureOnlyCycleExecutor {

    private CaptureOnlyCycleExecutor() {
    }

    static boolean isCaptureOnly(AsyncInspectionCycleInput in) {
        ReferenceSnapshot reference = in.activeReference();
        return reference == null || !reference.isUsable();
    }

    static void run(
            InspectionPipelineServices svc,
            AsyncInspectionCycleInput in,
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
                "capture without reference"
        );
        PipelineState state;
        try {
            if (timeoutMs > 0) {
                state = captureFuture.get(timeoutMs, TimeUnit.MILLISECONDS);
            } else {
                state = captureFuture.join();
            }
        } catch (TimeoutException e) {
            captureFuture.cancel(true);
            throw e;
        } catch (ExecutionException e) {
            if (inspectionGate != null && inspectionGate.isCancelRequested(in.cameraId())) {
                captureFuture.cancel(true);
                svc.log().info("integration cam={}: capture-only cycle cancelled", in.cameraId());
                return;
            }
            Throwable cause = e.getCause() == null ? e : e.getCause();
            if (cause instanceof RuntimeException runtime) {
                throw runtime;
            }
            throw new PipelineException(cause);
        } catch (InterruptedException e) {
            if (inspectionGate != null && inspectionGate.isCancelRequested(in.cameraId())) {
                captureFuture.cancel(true);
                Thread.currentThread().interrupt();
                svc.log().info("integration cam={}: capture-only cycle interrupted by cancel", in.cameraId());
                return;
            }
            Thread.currentThread().interrupt();
            throw new PipelineException(e);
        }
        if (state == null || state.capture() == null || state.capture().header() == null) {
            svc.log().warn("integration cam={}: capture-only skipped — empty capture response", in.cameraId());
            return;
        }
        long frameId = YamlScalars.toLong(state.capture().header().get("frame_id"), -1L);
        InspectionDecision decision = InspectionDecision.captureOnly(in.cameraId(), frameId);
        boolean published = inspectionGate == null || inspectionGate.runIfInspectionActive(in.cameraId(), () -> {
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
                        in.cameraId(),
                        in.productType(),
                        in.detectorId(),
                        in.inspectionId(),
                        in.activeReference(),
                        decision,
                        state.capture(),
                        null,
                        null
                );
            } catch (RuntimeException e) {
                svc.log().warn(
                        "capture-only ui publish failed camera_id={} frame_id={}: {}",
                        in.cameraId(),
                        frameId,
                        e.getMessage()
                );
            } finally {
                CycleShmRelease.releaseCycleShm(state.capture());
            }
        });
        if (!published) {
            CycleShmRelease.releaseCycleShm(state.capture());
            svc.log().info("integration cam={}: capture-only frame suppressed by client stop", in.cameraId());
            return;
        }
        long captureMs = state.captureMs();
        svc.pipelineTelemetry().logInspectionCycle(
                in.pipelineStagesLog(),
                Map.of("capture_only", true),
                in.cameraId(),
                in.productType(),
                in.detectorId(),
                0L,
                captureMs,
                state,
                decision,
                System.nanoTime(),
                System.nanoTime(),
                System.nanoTime()
        );
        svc.log().info(
                "integration cam={}: capture-only frame={} delivered to ui (geometry/python skipped — no reference)",
                in.cameraId(),
                frameId
        );
    }
}

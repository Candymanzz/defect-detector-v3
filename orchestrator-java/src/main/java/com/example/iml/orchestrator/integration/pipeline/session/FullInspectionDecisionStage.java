package com.example.iml.orchestrator.integration.pipeline.session;

import com.example.iml.orchestrator.integration.config.YamlScalars;
import com.example.iml.orchestrator.integration.pipeline.InspectionDecision;
import com.example.iml.orchestrator.integration.pipeline.InspectionPipelineServices;
import com.example.iml.orchestrator.integration.pipeline.PipelineState;

import java.util.LinkedHashMap;
import java.util.Map;

/** Decision / sidecar / telemetry portion of the full async inspection cycle. */
final class FullInspectionDecisionStage {

    private FullInspectionDecisionStage() {
    }

    static void accept(
            InspectionPipelineServices svc,
            AsyncInspectionCycleInput in,
            Map<String, Object> timingExtras,
            PerCameraInspectionGate inspectionGate,
            PipelineState state
    ) {
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
        long positioningMs = YamlScalars.toLong(
                state.capture() == null || state.capture().header() == null
                        ? null
                        : state.capture().header().get("positioning_ms"),
                0L
        );
        long captureToGeometryDoneMs = YamlScalars.nanosToMs(
                state.captureMs() + positioningMs + state.geometryMs());
        long captureToPythonDoneMs = YamlScalars.nanosToMs(
                state.captureMs() + positioningMs + state.geometryMs() + state.pythonMs());
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
            } finally {
                CycleShmRelease.releaseCycleShm(state.capture());
            }
        });
        if (!resultPublished) {
            svc.afterInspectionSidecar().discardInspectionArtifacts(state.py());
            CycleShmRelease.releaseCycleShm(state.capture());
            svc.log().info("integration cam={}: inspection result suppressed by client stop", in.cameraId());
            return;
        }
        long tFanoutDone = System.nanoTime();
        long totalMs = YamlScalars.nanosToMs(tFanoutDone - in.tCameraStartNanos());
        long captureFrameToInspectionEndMs = captureFrameTimestampNs > 0
                ? YamlScalars.nanosToMs(System.nanoTime() - captureFrameTimestampNs)
                : -1L;
        svc.log().info(
                "inspection_timing cam={} frame={} positioning_ms={} capture_to_geometry_done_ms={} capture_to_python_done_ms={} capture_frame_to_inspection_end_ms={}",
                in.cameraId(),
                decision.frameId(),
                positioningMs,
                captureToGeometryDoneMs,
                captureToPythonDoneMs,
                captureFrameToInspectionEndMs >= 0 ? captureFrameToInspectionEndMs : "unknown"
        );
        Map<String, Object> telemetryExtras = new LinkedHashMap<>();
        if (timingExtras != null && !timingExtras.isEmpty()) {
            telemetryExtras.putAll(timingExtras);
        }
        telemetryExtras.put("positioning_ms", positioningMs);
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
    }
}

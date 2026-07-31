package com.example.iml.orchestrator.integration.pipeline.session;

import com.example.iml.orchestrator.integration.pipeline.InspectionPipelineServices;
import com.example.iml.orchestrator.integration.pipeline.ReferenceSnapshot;
import com.example.iml.orchestrator.integration.trigger.InspectionTriggerEvent;

import java.util.Map;
import java.util.concurrent.TimeoutException;

/** Single trigger-driven inspection cycle for production mode. */
final class ProductionTriggerCycleRunner {

    private ProductionTriggerCycleRunner() {
    }

    static void runCycle(
            InspectionPipelineServices svc,
            AsyncInspectionCycleInput in,
            boolean referenceFromClient,
            Map<Integer, ReferenceSnapshot> referenceByCamera,
            PerCameraInspectionGate inspectionGate,
            long inspectionCycleTimeoutMs,
            boolean captureWithoutReference,
            InspectionTriggerEvent event
    ) {
        PerCameraInspectionGate.BeginResult begin = inspectionGate.tryBeginInspection(in.cameraId());
        if (begin == PerCameraInspectionGate.BeginResult.DISABLED) {
            svc.log().debug(
                    "integration cam={}: trigger skipped — inspection disabled (source={})",
                    in.cameraId(),
                    event.source()
            );
            return;
        }
        if (begin == PerCameraInspectionGate.BeginResult.IN_FLIGHT) {
            svc.log().warn(
                    "integration cam={}: unexpected in-flight gate (capture already at DI3 prefire); retrying (source={})",
                    in.cameraId(),
                    event.source()
            );
            begin = inspectionGate.tryBeginInspection(in.cameraId());
            if (begin != PerCameraInspectionGate.BeginResult.STARTED) {
                return;
            }
        }
        long inspectionId = 0L;
        try {
            AsyncInspectionCycleInput cycleIn = ProductionInspectionOrchestrator.resolveCycleInput(
                    in, referenceFromClient, referenceByCamera, captureWithoutReference);
            if (cycleIn == null) {
                if (referenceFromClient) {
                    svc.log().debug(
                            "integration cam={}: trigger skipped — no client.reference_bundle (capture_without_reference=false)",
                            in.cameraId()
                    );
                }
                return;
            }
            cycleIn = cycleIn.withTriggerSequence(event.sequence());
            inspectionId = inspectionGate.nextInspectionId(in.cameraId());
            cycleIn = cycleIn.withInspectionId(inspectionId);
            boolean captureOnly = cycleIn.activeReference() == null || !cycleIn.activeReference().isUsable();
            if (captureOnly) {
                svc.log().info(
                        "integration cam={}: capture started capture_id={} source={} (no reference — frame only)",
                        in.cameraId(),
                        inspectionId,
                        event.source()
                );
            } else {
                svc.log().info(
                        "integration cam={}: inspection started inspection_id={} source={}",
                        in.cameraId(),
                        inspectionId,
                        event.source()
                );
            }
            AsyncInspectionCycleRunner.run(svc, cycleIn, null, inspectionCycleTimeoutMs, inspectionGate);
        } catch (TimeoutException e) {
            svc.log().warn(
                    "integration cam={}: inspection cycle timeout inspection_id={} after {} ms (source={})",
                    in.cameraId(),
                    inspectionId,
                    inspectionCycleTimeoutMs,
                    event.source()
            );
        } catch (Exception e) {
            svc.log().warn(
                    "integration cam={}: inspection cycle failed inspection_id={} (next tick continues): {}",
                    in.cameraId(),
                    inspectionId,
                    e.getMessage()
            );
            svc.log().debug("inspection cycle error", e);
        } finally {
            inspectionGate.endInspection(in.cameraId());
        }
    }
}

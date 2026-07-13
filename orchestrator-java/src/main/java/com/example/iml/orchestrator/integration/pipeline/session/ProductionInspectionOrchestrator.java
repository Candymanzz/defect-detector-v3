package com.example.iml.orchestrator.integration.pipeline.session;

import com.example.iml.orchestrator.integration.config.IntegrationFeatureConfig;
import com.example.iml.orchestrator.integration.config.ReferenceSource;
import com.example.iml.orchestrator.integration.pipeline.InspectionPipelineServices;
import com.example.iml.orchestrator.integration.pipeline.ReferenceSnapshot;
import com.example.iml.orchestrator.integration.trigger.InspectionTriggerEvent;
import com.example.iml.orchestrator.integration.trigger.InspectionTriggerStrategy;

import java.util.Map;
import java.util.concurrent.TimeoutException;

/**
 * Обычный режим: стратегия ожидания триггера (таймер, UDP-шина, непрерывный цикл).
 */
public final class ProductionInspectionOrchestrator {

    private ProductionInspectionOrchestrator() {
    }

    public static void run(
            InspectionPipelineServices svc,
            AsyncInspectionCycleInput in,
            InspectionTriggerStrategy triggerStrategy,
            IntegrationFeatureConfig.InspectionTriggerMode triggerMode,
            ReferenceSource referenceSource,
            Map<Integer, ReferenceSnapshot> referenceByCamera,
            PerCameraInspectionGate inspectionGate,
            long inspectionCycleTimeoutMs,
            boolean captureWithoutReference
    ) throws Exception {
        boolean referenceFromClient = referenceSource == ReferenceSource.CLIENT;
        logTriggerMode(svc, in, triggerMode, triggerStrategy, referenceFromClient, captureWithoutReference);
        runTriggerDrivenLoop(
                svc,
                in,
                triggerStrategy,
                referenceFromClient,
                referenceByCamera,
                inspectionGate,
                inspectionCycleTimeoutMs,
                captureWithoutReference
        );
    }

    private static void logTriggerMode(
            InspectionPipelineServices svc,
            AsyncInspectionCycleInput in,
            IntegrationFeatureConfig.InspectionTriggerMode mode,
            InspectionTriggerStrategy strategy,
            boolean referenceFromClient,
            boolean captureWithoutReference
    ) {
        int cameraId = in.cameraId();
        switch (mode) {
            case TIMER -> {
                if (referenceFromClient && !captureWithoutReference) {
                    svc.log().info(
                            "integration cam={}: timer trigger — inspection only after client.reference_bundle",
                            cameraId
                    );
                } else if (referenceFromClient) {
                    svc.log().info(
                            "integration cam={}: timer trigger — capture without reference enabled",
                            cameraId
                    );
                } else {
                    svc.log().warn(
                            "integration cam={}: dev_auto_trigger_stub (timer) — temporary stub instead of external trigger",
                            cameraId
                    );
                }
            }
            case CONTINUOUS -> svc.log().info("integration cam={}: continuous_inspection enabled", cameraId);
            case EXTERNAL -> {
                if (referenceFromClient && !captureWithoutReference) {
                    svc.log().info(
                            "integration cam={}: waiting for external trigger (e.g. UDP) after client.reference_bundle",
                            cameraId
                    );
                } else if (referenceFromClient) {
                    svc.log().info(
                            "integration cam={}: waiting for external trigger — capture without reference, full inspection after reference_bundle",
                            cameraId
                    );
                } else {
                    svc.log().info("integration cam={}: waiting for external trigger (e.g. UDP)", cameraId);
                }
            }
            default -> { }
        }
        if (strategy.postCycleDelayMs() > 0 && mode == IntegrationFeatureConfig.InspectionTriggerMode.CONTINUOUS) {
            svc.log().debug("integration cam={}: post_cycle_delay_ms={}", cameraId, strategy.postCycleDelayMs());
        }
    }

    private static void runTriggerDrivenLoop(
            InspectionPipelineServices svc,
            AsyncInspectionCycleInput in,
            InspectionTriggerStrategy triggerStrategy,
            boolean referenceFromClient,
            Map<Integer, ReferenceSnapshot> referenceByCamera,
            PerCameraInspectionGate inspectionGate,
            long inspectionCycleTimeoutMs,
            boolean captureWithoutReference
    ) throws Exception {
        while (!Thread.currentThread().isInterrupted()) {
            InspectionTriggerEvent event = triggerStrategy.awaitNext(in.cameraId());
            runCycle(
                    svc,
                    in,
                    referenceFromClient,
                    referenceByCamera,
                    inspectionGate,
                    inspectionCycleTimeoutMs,
                    captureWithoutReference,
                    event
            );
            int delay = triggerStrategy.postCycleDelayMs();
            if (delay > 0) {
                sleepInterruptibly(delay);
            }
        }
    }

    private static void runCycle(
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
            AsyncInspectionCycleInput cycleIn = resolveCycleInput(
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

    static AsyncInspectionCycleInput resolveCycleInput(
            AsyncInspectionCycleInput in,
            boolean referenceFromClient,
            Map<Integer, ReferenceSnapshot> referenceByCamera,
            boolean captureWithoutReference
    ) {
        if (!referenceFromClient) {
            return in;
        }
        ReferenceSnapshot ref = referenceByCamera.get(in.cameraId());
        if (ref != null && ref.isUsable()) {
            String productType = ref.productType() != null && !ref.productType().isBlank()
                    ? ref.productType()
                    : in.productType();
            return in.withPerCycleIdentity(productType, ref, in.referenceMsFinal());
        }
        if (!captureWithoutReference) {
            return null;
        }
        // Эталон ещё не задан: снимаем кадр по триггеру, geometry/python — после reference_bundle.
        return in.withPerCycleIdentity(in.productType(), null, 0L);
    }

    private static void sleepInterruptibly(int delayMs) throws InterruptedException {
        if (delayMs <= 0) {
            return;
        }
        Thread.sleep(delayMs);
    }
}

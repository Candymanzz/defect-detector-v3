package com.example.iml.orchestrator.integration.pipeline.session;

import com.example.iml.orchestrator.integration.config.IntegrationFeatureConfig;
import com.example.iml.orchestrator.integration.config.ReferenceSource;
import com.example.iml.orchestrator.integration.pipeline.InspectionPipelineServices;
import com.example.iml.orchestrator.integration.pipeline.ReferenceSnapshot;
import com.example.iml.orchestrator.integration.pipeline.reference.PipelineReferenceRegistry;
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
            PipelineReferenceRegistry referenceRegistry,
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
                referenceRegistry,
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
            PipelineReferenceRegistry referenceRegistry,
            PerCameraInspectionGate inspectionGate,
            long inspectionCycleTimeoutMs,
            boolean captureWithoutReference
    ) throws Exception {
        while (!Thread.currentThread().isInterrupted()) {
            InspectionTriggerEvent event = triggerStrategy.awaitNext(in.cameraId());
            PerCameraInspectionGate.BeginResult begin = inspectionGate.tryBeginInspection(
                    in.cameraId(), event.parentCycleId(), event.phaseId(), event.sequence());
            try {
                in.cycleExecutor().execute(() -> runCycle(
                        svc, in, referenceFromClient, referenceRegistry, inspectionGate,
                        inspectionCycleTimeoutMs, captureWithoutReference, event, begin));
            } catch (RuntimeException schedulingFailure) {
                if (begin == PerCameraInspectionGate.BeginResult.STARTED) {
                    inspectionGate.endInspection(in.cameraId(), event.parentCycleId(), event.phaseId());
                }
                throw schedulingFailure;
            }
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
            PipelineReferenceRegistry referenceRegistry,
            PerCameraInspectionGate inspectionGate,
            long inspectionCycleTimeoutMs,
            boolean captureWithoutReference,
            InspectionTriggerEvent event,
            PerCameraInspectionGate.BeginResult begin
    ) {
        if (begin == PerCameraInspectionGate.BeginResult.DISABLED) {
            if (!inspectionGate.tryBeginPreviewCapture(in.cameraId())) {
                svc.log().debug(
                        "integration cam={}: stopped preview skipped — capture already in flight or inspection re-enabled",
                        in.cameraId()
                );
                return;
            }
            svc.log().info(
                    "integration cam={}: preview-only capture while inspection disabled seq={} source={}",
                    in.cameraId(),
                    event.sequence(),
                    event.source()
            );
            try {
                runDisabledPreviewCapture(svc, in, inspectionCycleTimeoutMs, event);
            } catch (TimeoutException e) {
                svc.log().warn(
                        "integration cam={}: stopped preview capture timeout seq={} after {} ms",
                        in.cameraId(),
                        Math.max(0L, event.sequence()),
                        inspectionCycleTimeoutMs
                );
            } catch (Exception e) {
                // Soft-stop must keep the trigger loop alive: one bad capture must not kill the camera thread.
                svc.log().warn(
                        "integration cam={}: stopped preview capture failed seq={} (next trigger continues): {}",
                        in.cameraId(),
                        event.sequence(),
                        e.getMessage()
                );
                svc.log().debug("stopped preview capture error", e);
            } finally {
                inspectionGate.endPreviewCapture(in.cameraId());
            }
            return;
        }
        if (begin == PerCameraInspectionGate.BeginResult.IN_FLIGHT) {
            svc.log().warn(
                    "integration cam={}: duplicate in-flight phase skipped source={} phase={} parent_cycle={}",
                    in.cameraId(),
                    event.source(),
                    event.phaseId(),
                    event.parentCycleId()
            );
            return;
        }
        long inspectionId = 0L;
        try {
            AsyncInspectionCycleInput cycleIn = resolveCycleInput(
                    in, referenceFromClient, referenceRegistry, captureWithoutReference, event.phaseId());
            if (cycleIn == null) {
                if (referenceFromClient) {
                    svc.log().debug(
                            "integration cam={}: trigger skipped — no client.reference_bundle (capture_without_reference=false)",
                            in.cameraId()
                    );
                }
                return;
            }
            cycleIn = cycleIn.withTriggerIdentity(
                    event.sequence(),
                    event.phaseId(),
                    event.parentCycleId(),
                    event.rawTriggerSequence()
            );
            // Один DI3/line seq = один inspection_id на всех камерах (и на bucket UI).
            // Иначе Stop→Start rejoin получает свой счётчик и на фронте «идёт параллельно».
            inspectionId = event.sequence() > 0L
                    ? event.sequence()
                    : inspectionGate.nextInspectionId(in.cameraId());
            cycleIn = cycleIn.withInspectionId(inspectionId);
            boolean captureOnly = cycleIn.activeReference() == null || !cycleIn.activeReference().isUsable();
            if (captureOnly) {
                svc.log().info(
                        "integration cam={}: capture started capture_id={} source={} phase={} parent_cycle={} raw_seq={} (no reference — frame only)",
                        in.cameraId(),
                        inspectionId,
                        event.source(),
                        event.phaseId(),
                        event.parentCycleId(),
                        event.rawTriggerSequence()
                );
            } else {
                svc.log().info(
                        "integration cam={}: inspection started inspection_id={} source={} phase={} parent_cycle={} raw_seq={}",
                        in.cameraId(),
                        inspectionId,
                        event.source(),
                        event.phaseId(),
                        event.parentCycleId(),
                        event.rawTriggerSequence()
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
            inspectionGate.endInspection(in.cameraId(), event.parentCycleId(), event.phaseId());
        }
    }

    private static void runDisabledPreviewCapture(
            InspectionPipelineServices svc,
            AsyncInspectionCycleInput in,
            long inspectionCycleTimeoutMs,
            InspectionTriggerEvent event
    ) throws TimeoutException {
        long frameSequence = Math.max(0L, event.sequence());
        AsyncInspectionCycleInput previewIn = in
                .withPerCycleIdentity(in.productType(), null, 0L)
                .withTriggerIdentity(
                        frameSequence,
                        event.phaseId(),
                        event.parentCycleId(),
                        event.rawTriggerSequence()
                )
                .withInspectionId(frameSequence);
        // null gate marks this as preview-only: publish the frame, but do not add a bucket result.
        AsyncInspectionCycleRunner.run(svc, previewIn, null, inspectionCycleTimeoutMs, null);
    }

    static AsyncInspectionCycleInput resolveCycleInput(
            AsyncInspectionCycleInput in,
            boolean referenceFromClient,
            PipelineReferenceRegistry referenceRegistry,
            boolean captureWithoutReference,
            int phaseId
    ) {
        if (!referenceFromClient) {
            return in;
        }
        ReferenceSnapshot ref = referenceRegistry == null ? null : referenceRegistry.get(phaseId, in.cameraId());
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

    static AsyncInspectionCycleInput resolveCycleInput(
            AsyncInspectionCycleInput in,
            boolean referenceFromClient,
            Map<Integer, ReferenceSnapshot> referenceByCamera,
            boolean captureWithoutReference
    ) {
        PipelineReferenceRegistry compatibility = new PipelineReferenceRegistry();
        compatibility.byCamera().putAll(referenceByCamera);
        return resolveCycleInput(in, referenceFromClient, compatibility, captureWithoutReference, 0);
    }

    private static void sleepInterruptibly(int delayMs) throws InterruptedException {
        if (delayMs <= 0) {
            return;
        }
        Thread.sleep(delayMs);
    }
}

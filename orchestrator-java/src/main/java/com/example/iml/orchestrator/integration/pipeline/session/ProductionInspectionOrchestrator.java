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
            long inspectionCycleTimeoutMs
    ) throws Exception {
        boolean referenceFromClient = referenceSource == ReferenceSource.CLIENT;
        logTriggerMode(svc, in, triggerMode, triggerStrategy, referenceFromClient);
        runTriggerDrivenLoop(
                svc,
                in,
                triggerStrategy,
                referenceFromClient,
                referenceByCamera,
                inspectionGate,
                inspectionCycleTimeoutMs
        );
    }

    private static void logTriggerMode(
            InspectionPipelineServices svc,
            AsyncInspectionCycleInput in,
            IntegrationFeatureConfig.InspectionTriggerMode mode,
            InspectionTriggerStrategy strategy,
            boolean referenceFromClient
    ) {
        int cameraId = in.cameraId();
        switch (mode) {
            case TIMER -> {
                if (referenceFromClient) {
                    svc.log().info(
                            "integration cam={}: timer trigger — inspection only after client.reference_bundle",
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
                if (referenceFromClient) {
                    svc.log().info(
                            "integration cam={}: waiting for external trigger (e.g. UDP) after client.reference_bundle",
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
            long inspectionCycleTimeoutMs
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
                    "integration cam={}: trigger skipped — previous inspection still in flight (source={})",
                    in.cameraId(),
                    event.source()
            );
            return;
        }
        try {
            AsyncInspectionCycleInput cycleIn = resolveCycleInput(in, referenceFromClient, referenceByCamera);
            if (cycleIn == null) {
                if (referenceFromClient) {
                    svc.log().debug(
                            "integration cam={}: trigger skipped — no client.reference_bundle yet",
                            in.cameraId()
                    );
                }
                return;
            }
            cycleIn = cycleIn.withTriggerSequence(event.sequence());
            AsyncInspectionCycleRunner.run(svc, cycleIn, null, inspectionCycleTimeoutMs, inspectionGate);
        } catch (TimeoutException e) {
            svc.log().warn(
                    "integration cam={}: inspection cycle timeout after {} ms (source={})",
                    in.cameraId(),
                    inspectionCycleTimeoutMs,
                    event.source()
            );
        } catch (Exception e) {
            svc.log().warn(
                    "integration cam={}: inspection cycle failed (next tick continues): {}",
                    in.cameraId(),
                    e.getMessage()
            );
            svc.log().debug("inspection cycle error", e);
        } finally {
            inspectionGate.endInspection(in.cameraId());
        }
    }

    private static AsyncInspectionCycleInput resolveCycleInput(
            AsyncInspectionCycleInput in,
            boolean referenceFromClient,
            Map<Integer, ReferenceSnapshot> referenceByCamera
    ) {
        if (!referenceFromClient) {
            return in;
        }
        ReferenceSnapshot ref = referenceByCamera.get(in.cameraId());
        if (ref == null) {
            return null;
        }
        String productType = ref.productType() != null && !ref.productType().isBlank()
                ? ref.productType()
                : in.productType();
        return in.withPerCycleIdentity(productType, ref, in.referenceMsFinal());
    }

    private static void sleepInterruptibly(int delayMs) throws InterruptedException {
        if (delayMs <= 0) {
            return;
        }
        Thread.sleep(delayMs);
    }
}

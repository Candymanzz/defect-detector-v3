package com.example.iml.orchestrator.integration.pipeline.session;

import com.example.iml.orchestrator.integration.config.IntegrationFeatureConfig;
import com.example.iml.orchestrator.integration.config.ReferenceSource;
import com.example.iml.orchestrator.integration.pipeline.InspectionPipelineServices;
import com.example.iml.orchestrator.integration.pipeline.ReferenceSnapshot;
import com.example.iml.orchestrator.integration.trigger.InspectionTriggerEvent;
import com.example.iml.orchestrator.integration.trigger.InspectionTriggerStrategy;

import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;

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
            GlobalInspectionCycleCoordinator cycleCoordinator
    ) throws Exception {
        AtomicBoolean cycleInProgress = new AtomicBoolean(false);
        boolean referenceFromClient = referenceSource == ReferenceSource.CLIENT;
        logTriggerMode(svc, in, triggerMode, triggerStrategy, referenceFromClient);
        runTriggerDrivenLoop(
                svc,
                in,
                triggerStrategy,
                cycleInProgress,
                referenceFromClient,
                referenceByCamera,
                cycleCoordinator
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
            AtomicBoolean cycleInProgress,
            boolean referenceFromClient,
            Map<Integer, ReferenceSnapshot> referenceByCamera,
            GlobalInspectionCycleCoordinator cycleCoordinator
    ) throws Exception {
        while (!Thread.currentThread().isInterrupted()) {
            InspectionTriggerEvent event = triggerStrategy.awaitNext(in.cameraId());
            if (!cycleInProgress.get()) {
                runCycle(svc, in, cycleInProgress, referenceFromClient, referenceByCamera, cycleCoordinator);
                int delay = triggerStrategy.postCycleDelayMs();
                if (delay > 0) {
                    sleepInterruptibly(delay);
                }
            } else {
                svc.log().warn(
                        "integration cam={}: trigger ignored (source={}), inspection in progress",
                        in.cameraId(),
                        event.source()
                );
            }
        }
    }

    private static void runCycle(
            InspectionPipelineServices svc,
            AsyncInspectionCycleInput in,
            AtomicBoolean cycleInProgress,
            boolean referenceFromClient,
            Map<Integer, ReferenceSnapshot> referenceByCamera,
            GlobalInspectionCycleCoordinator cycleCoordinator
    ) {
        if (!cycleInProgress.compareAndSet(false, true)) {
            return;
        }
        try {
            if (cycleCoordinator != null) {
                cycleCoordinator.awaitCycleStart();
            }
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
            AsyncInspectionCycleRunner.run(svc, cycleIn, null);
        } catch (Exception e) {
            svc.log().warn(
                    "integration cam={}: inspection cycle failed (next tick continues): {}",
                    in.cameraId(),
                    e.getMessage()
            );
            svc.log().debug("inspection cycle error", e);
        } finally {
            if (cycleCoordinator != null) {
                cycleCoordinator.awaitCycleFinish();
            }
            cycleInProgress.set(false);
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

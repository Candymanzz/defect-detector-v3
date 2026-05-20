package com.example.iml.orchestrator.integration.pipeline.session;

import com.example.iml.orchestrator.integration.config.IntegrationFeatureConfig;
import com.example.iml.orchestrator.integration.config.ReferenceSource;
import com.example.iml.orchestrator.integration.pipeline.InspectionPipelineServices;
import com.example.iml.orchestrator.integration.pipeline.ReferenceSnapshot;

import java.util.Map;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Обычный режим: ожидание внешнего триггера, непрерывный цикл или dev-заглушка с фиксированным интервалом.
 */
public final class ProductionInspectionOrchestrator {

    private ProductionInspectionOrchestrator() {
    }

    public static void run(
            InspectionPipelineServices svc,
            AsyncInspectionCycleInput in,
            IntegrationFeatureConfig.ContinuousInspectionConfig continuousInspection,
            IntegrationFeatureConfig.DevAutoTriggerStubConfig devAutoTriggerStub,
            ReferenceSource referenceSource,
            Map<Integer, ReferenceSnapshot> referenceByCamera
    ) throws Exception {
        AtomicBoolean cycleInProgress = new AtomicBoolean(false);
        boolean referenceFromClient = referenceSource == ReferenceSource.CLIENT;
        if (devAutoTriggerStub.enabled()) {
            if (continuousInspection.enabled()) {
                svc.log().warn(
                        "integration cam={}: dev_auto_trigger_stub takes precedence over continuous_inspection",
                        in.cameraId()
                );
            }
            if (referenceFromClient) {
                svc.log().info(
                        "integration cam={}: dev_auto_trigger_stub interval_ms={} after client.reference_bundle "
                                + "(ticks without reference are skipped)",
                        in.cameraId(),
                        devAutoTriggerStub.intervalMs()
                );
            } else {
                svc.log().warn(
                        "integration cam={}: dev_auto_trigger_stub enabled interval_ms={} "
                                + "(временная заглушка вместо ожидания внешнего триггера)",
                        in.cameraId(),
                        devAutoTriggerStub.intervalMs()
                );
            }
            runDevAutoTriggerStubLoop(svc, in, devAutoTriggerStub, cycleInProgress, referenceFromClient, referenceByCamera);
            return;
        }
        if (continuousInspection.enabled()) {
            runContinuousLoop(svc, in, continuousInspection, cycleInProgress, referenceFromClient, referenceByCamera);
            return;
        }
        runExternalTriggerLoop(svc, in, cycleInProgress, referenceFromClient, referenceByCamera);
    }

    private static void runDevAutoTriggerStubLoop(
            InspectionPipelineServices svc,
            AsyncInspectionCycleInput in,
            IntegrationFeatureConfig.DevAutoTriggerStubConfig stub,
            AtomicBoolean cycleInProgress,
            boolean referenceFromClient,
            Map<Integer, ReferenceSnapshot> referenceByCamera
    ) throws Exception {
        while (!Thread.currentThread().isInterrupted()) {
            if (!cycleInProgress.get()) {
                runCycle(svc, in, cycleInProgress, referenceFromClient, referenceByCamera);
            } else {
                svc.log().debug("dev_auto_trigger_stub cam={}: skip tick, inspection still in progress", in.cameraId());
            }
            sleepInterruptibly(stub.intervalMs());
        }
    }

    private static void runContinuousLoop(
            InspectionPipelineServices svc,
            AsyncInspectionCycleInput in,
            IntegrationFeatureConfig.ContinuousInspectionConfig continuousInspection,
            AtomicBoolean cycleInProgress,
            boolean referenceFromClient,
            Map<Integer, ReferenceSnapshot> referenceByCamera
    ) throws Exception {
        do {
            runCycle(svc, in, cycleInProgress, referenceFromClient, referenceByCamera);
            if (continuousInspection.cycleDelayMs() > 0) {
                sleepInterruptibly(continuousInspection.cycleDelayMs());
            }
        } while (!Thread.currentThread().isInterrupted());
    }

    private static void runExternalTriggerLoop(
            InspectionPipelineServices svc,
            AsyncInspectionCycleInput in,
            AtomicBoolean cycleInProgress,
            boolean referenceFromClient,
            Map<Integer, ReferenceSnapshot> referenceByCamera
    ) throws Exception {
        BlockingQueue<Object> externalTriggers = new LinkedBlockingQueue<>();
        if (referenceFromClient) {
            svc.log().info(
                    "integration cam={}: reference_source=client — waiting for client.reference_bundle, "
                            + "then external inspection trigger",
                    in.cameraId()
            );
        } else {
            svc.log().info("integration cam={}: waiting for external inspection trigger", in.cameraId());
        }
        while (!Thread.currentThread().isInterrupted()) {
            try {
                externalTriggers.take();
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                break;
            }
            if (!cycleInProgress.get()) {
                runCycle(svc, in, cycleInProgress, referenceFromClient, referenceByCamera);
            } else {
                svc.log().warn("integration cam={}: external trigger ignored, inspection in progress", in.cameraId());
            }
        }
    }

    private static void runCycle(
            InspectionPipelineServices svc,
            AsyncInspectionCycleInput in,
            AtomicBoolean cycleInProgress,
            boolean referenceFromClient,
            Map<Integer, ReferenceSnapshot> referenceByCamera
    ) {
        if (!cycleInProgress.compareAndSet(false, true)) {
            return;
        }
        try {
            AsyncInspectionCycleInput cycleIn = resolveCycleInput(in, referenceFromClient, referenceByCamera);
            if (cycleIn == null) {
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

package com.example.iml.orchestrator.integration.pipeline.session;

import com.example.iml.orchestrator.integration.config.IntegrationFeatureConfig;
import com.example.iml.orchestrator.integration.pipeline.InspectionPipelineServices;

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
            IntegrationFeatureConfig.DevAutoTriggerStubConfig devAutoTriggerStub
    ) throws Exception {
        AtomicBoolean cycleInProgress = new AtomicBoolean(false);
        if (devAutoTriggerStub.enabled()) {
            if (continuousInspection.enabled()) {
                svc.log().warn(
                        "integration cam={}: dev_auto_trigger_stub takes precedence over continuous_inspection",
                        in.cameraId()
                );
            }
            svc.log().warn(
                    "integration cam={}: dev_auto_trigger_stub enabled interval_ms={} "
                            + "(временная заглушка вместо ожидания внешнего триггера)",
                    in.cameraId(),
                    devAutoTriggerStub.intervalMs()
            );
            runDevAutoTriggerStubLoop(svc, in, devAutoTriggerStub, cycleInProgress);
            return;
        }
        if (continuousInspection.enabled()) {
            runContinuousLoop(svc, in, continuousInspection, cycleInProgress);
            return;
        }
        runExternalTriggerLoop(svc, in, cycleInProgress);
    }

    private static void runDevAutoTriggerStubLoop(
            InspectionPipelineServices svc,
            AsyncInspectionCycleInput in,
            IntegrationFeatureConfig.DevAutoTriggerStubConfig stub,
            AtomicBoolean cycleInProgress
    ) throws Exception {
        while (!Thread.currentThread().isInterrupted()) {
            if (!cycleInProgress.get()) {
                runCycle(svc, in, cycleInProgress);
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
            AtomicBoolean cycleInProgress
    ) throws Exception {
        do {
            runCycle(svc, in, cycleInProgress);
            if (continuousInspection.cycleDelayMs() > 0) {
                sleepInterruptibly(continuousInspection.cycleDelayMs());
            }
        } while (!Thread.currentThread().isInterrupted());
    }

    private static void runExternalTriggerLoop(
            InspectionPipelineServices svc,
            AsyncInspectionCycleInput in,
            AtomicBoolean cycleInProgress
    ) throws Exception {
        BlockingQueue<Object> externalTriggers = new LinkedBlockingQueue<>();
        svc.log().info("integration cam={}: waiting for external inspection trigger", in.cameraId());
        while (!Thread.currentThread().isInterrupted()) {
            try {
                externalTriggers.take();
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                break;
            }
            if (!cycleInProgress.get()) {
                runCycle(svc, in, cycleInProgress);
            } else {
                svc.log().warn("integration cam={}: external trigger ignored, inspection in progress", in.cameraId());
            }
        }
    }

    private static void runCycle(
            InspectionPipelineServices svc,
            AsyncInspectionCycleInput in,
            AtomicBoolean cycleInProgress
    ) {
        if (!cycleInProgress.compareAndSet(false, true)) {
            return;
        }
        try {
            AsyncInspectionCycleRunner.run(svc, in, null);
        } finally {
            cycleInProgress.set(false);
        }
    }

    private static void sleepInterruptibly(int delayMs) throws InterruptedException {
        if (delayMs <= 0) {
            return;
        }
        Thread.sleep(delayMs);
    }
}

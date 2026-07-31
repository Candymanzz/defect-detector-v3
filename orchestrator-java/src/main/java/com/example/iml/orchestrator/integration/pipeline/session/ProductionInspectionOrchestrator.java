package com.example.iml.orchestrator.integration.pipeline.session;

import com.example.iml.orchestrator.integration.pipeline.PipelineException;

import com.example.iml.orchestrator.integration.config.IntegrationFeatureConfig;
import com.example.iml.orchestrator.integration.config.ReferenceSource;
import com.example.iml.orchestrator.integration.pipeline.InspectionPipelineServices;
import com.example.iml.orchestrator.integration.pipeline.ReferenceSnapshot;
import com.example.iml.orchestrator.integration.trigger.InspectionTriggerEvent;
import com.example.iml.orchestrator.integration.trigger.api.InspectionTriggerStrategy;

import java.util.Map;

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
    ) throws PipelineException {
        boolean referenceFromClient = referenceSource == ReferenceSource.CLIENT;
        ProductionTriggerModeLogger.log(svc, in, triggerMode, triggerStrategy, referenceFromClient, captureWithoutReference);
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

    private static void runTriggerDrivenLoop(
            InspectionPipelineServices svc,
            AsyncInspectionCycleInput in,
            InspectionTriggerStrategy triggerStrategy,
            boolean referenceFromClient,
            Map<Integer, ReferenceSnapshot> referenceByCamera,
            PerCameraInspectionGate inspectionGate,
            long inspectionCycleTimeoutMs,
            boolean captureWithoutReference
    ) throws PipelineException {
        while (!Thread.currentThread().isInterrupted()) {
            InspectionTriggerEvent event;
            try {
                event = triggerStrategy.awaitNext(in.cameraId());
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new PipelineException(e);
            }
            ProductionTriggerCycleRunner.runCycle(
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

    private static void sleepInterruptibly(int delayMs) {
        if (delayMs <= 0) {
            return;
        }
        try {
            Thread.sleep(delayMs);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new PipelineException(e);
        }
    }
}

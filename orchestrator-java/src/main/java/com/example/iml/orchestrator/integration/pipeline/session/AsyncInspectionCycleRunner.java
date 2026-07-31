package com.example.iml.orchestrator.integration.pipeline.session;

import com.example.iml.orchestrator.integration.pipeline.InspectionPipelineServices;

import java.util.Map;
import java.util.concurrent.TimeoutException;

/**
 * Один полный асинхронный цикл инспекции (capture → geometry → python → решение → fan-out → логи).
 */
public final class AsyncInspectionCycleRunner {

    private AsyncInspectionCycleRunner() {
    }

    public static void run(
            InspectionPipelineServices svc,
            AsyncInspectionCycleInput in,
            Map<String, Object> timingExtras,
            long timeoutMs,
            PerCameraInspectionGate inspectionGate
    ) throws TimeoutException {
        if (inspectionGate != null && inspectionGate.isCancelRequested(in.cameraId())) {
            svc.log().info("integration cam={}: inspection cycle cancelled before start", in.cameraId());
            return;
        }
        if (CaptureOnlyCycleExecutor.isCaptureOnly(in)) {
            CaptureOnlyCycleExecutor.run(svc, in, timeoutMs, inspectionGate);
            return;
        }
        FullInspectionCycleExecutor.run(svc, in, timingExtras, timeoutMs, inspectionGate);
    }
}

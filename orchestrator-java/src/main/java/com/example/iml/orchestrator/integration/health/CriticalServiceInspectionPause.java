package com.example.iml.orchestrator.integration.health;

import com.example.iml.orchestrator.integration.bootstrap.context.IntegrationRuntimeContext;
import com.example.iml.orchestrator.integration.fanout.FanOutCoordinator;
import com.example.iml.orchestrator.integration.trigger.InspectionTriggerRuntime;
import org.apache.logging.log4j.Logger;

import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Set;

/**
 * Пауза инспекции при vision_fault (analis_surface, geometry, …).
 * IoInputMonitor не блокирует пайплайн — см. {@link ServiceHealthGate#IO_INPUT_MONITOR}.
 */
public final class CriticalServiceInspectionPause {

    private volatile Map<Integer, Boolean> inspectionSnapshotBeforePause;

    private CriticalServiceInspectionPause() {
    }

    public static CriticalServiceInspectionPause wire(
            Logger log,
            IntegrationRuntimeContext ctx,
            ServiceHealthGate healthGate,
            FanOutCoordinator fanOut
    ) {
        CriticalServiceInspectionPause pause = new CriticalServiceInspectionPause();
        InspectionTriggerRuntime triggerRuntime = ctx.triggerRuntime();
        if (triggerRuntime == null || healthGate == null) {
            return pause;
        }
        triggerRuntime.bus().setDispatchAllowed(healthGate::healthyForVision);
        healthGate.setOnChanged(() -> pause.onHealthChanged(log, ctx, healthGate, fanOut));
        return pause;
    }

    private void onHealthChanged(
            Logger log,
            IntegrationRuntimeContext ctx,
            ServiceHealthGate healthGate,
            FanOutCoordinator fanOut
    ) {
        if (!healthGate.healthyForVision()) {
            pauseInspection(log, ctx, healthGate);
        } else {
            resumeInspection(log, ctx);
        }
        if (fanOut != null) {
            fanOut.refreshPlcLevels();
        }
    }

    private void pauseInspection(Logger log, IntegrationRuntimeContext ctx, ServiceHealthGate healthGate) {
        if (inspectionSnapshotBeforePause == null) {
            inspectionSnapshotBeforePause = ctx.inspectionGate().snapshotInspectionEnabled();
        }
        int cleared = ctx.triggerRuntime().bus().clearAllPending();
        ctx.inspectionGate().setSystemBlocked(true);
        Set<Integer> cancelled = ctx.inspectionGate().disableAllAndRequestCancel();
        log.warn(
                "critical services unhealthy {} — inspection paused cleared_triggers={} cancelled_cameras={}",
                healthGate.visionBlockingReasons(),
                cleared,
                cancelled
        );
    }

    private void resumeInspection(Logger log, IntegrationRuntimeContext ctx) {
        ctx.inspectionGate().setSystemBlocked(false);
        Map<Integer, Boolean> snapshot = inspectionSnapshotBeforePause;
        inspectionSnapshotBeforePause = null;
        if (snapshot == null || snapshot.isEmpty()) {
            log.info("critical services healthy — pipeline unblocked (inspection was idle before fault)");
            return;
        }
        ctx.inspectionGate().restoreInspectionEnabled(snapshot);
        Set<Integer> rearmed = new LinkedHashSet<>();
        for (Map.Entry<Integer, Boolean> entry : snapshot.entrySet()) {
            if (Boolean.TRUE.equals(entry.getValue())) {
                rearmed.add(entry.getKey());
            }
        }
        log.info(
                "critical services healthy — pipeline restored rearmed_cameras={} total={}",
                rearmed,
                snapshot.size()
        );
    }
}

package com.example.iml.orchestrator.integration.diagnostics;

import org.apache.logging.log4j.Logger;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Диагностика двухфазной съёмки по DI3: {@code since_di3_ms}, in-flight {@code wait_frame},
 * предупреждение если DI3 phase 1 пришёл пока камера ещё в capture phase 0.
 * Искать в логах: {@code sync_diag channel=inspect event=di3_trigger} / {@code capture_ok} / {@code phase1_trigger_while_phase0_capture}.
 */
public final class TwoPhaseCaptureDiagnostics {

    private record TriggerMark(int phaseId, long parentCycleId, long rawTriggerSequence, long epochMs) {
    }

    private record WaitFrameFlight(
            int cameraId,
            int phaseId,
            long parentCycleId,
            long rawTriggerSequence,
            long waitFrameStartMs
    ) {
    }

    private final Logger log;
    private final ConcurrentHashMap<Long, TriggerMark> triggerByRawSequence = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<Integer, WaitFrameFlight> waitFrameInFlightByCamera = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<Long, TriggerMark> phaseZeroByParentCycle = new ConcurrentHashMap<>();

    public TwoPhaseCaptureDiagnostics(Logger log) {
        this.log = log;
    }

    /**
     * DI3 принят, события разосланы на камеры (до {@code wait_frame}).
     */
    public void onTriggerDispatched(int phaseId, long parentCycleId, long rawTriggerSequence, int cameraCount) {
        long now = System.currentTimeMillis();
        TriggerMark mark = new TriggerMark(phaseId, parentCycleId, rawTriggerSequence, now);
        triggerByRawSequence.put(rawTriggerSequence, mark);
        pruneOldTriggers();

        Long sincePhase0Di3Ms = null;
        if (phaseId == 0) {
            phaseZeroByParentCycle.put(parentCycleId, mark);
        } else if (phaseId == 1) {
            TriggerMark phase0 = phaseZeroByParentCycle.get(parentCycleId);
            if (phase0 != null) {
                sincePhase0Di3Ms = now - phase0.epochMs();
            }
            logPhase1WhilePhase0CaptureBusy(parentCycleId, now, sincePhase0Di3Ms);
        }

        if (sincePhase0Di3Ms != null) {
            log.info(
                    "sync_diag channel=inspect event=di3_trigger phase={} parent_cycle={} raw_seq={} cameras={} since_phase0_di3_ms={}",
                    phaseId,
                    parentCycleId,
                    rawTriggerSequence,
                    cameraCount,
                    sincePhase0Di3Ms
            );
        } else {
            log.info(
                    "sync_diag channel=inspect event=di3_trigger phase={} parent_cycle={} raw_seq={} cameras={}",
                    phaseId,
                    parentCycleId,
                    rawTriggerSequence,
                    cameraCount
            );
        }
    }

    public void onWaitFrameStart(int cameraId, int phaseId, long parentCycleId, long rawTriggerSequence) {
        long now = System.currentTimeMillis();
        if (phaseId == 1) {
            WaitFrameFlight prior = waitFrameInFlightByCamera.get(cameraId);
            if (prior != null && prior.phaseId() == 0 && prior.parentCycleId() == parentCycleId) {
                long phase0WaitMs = now - prior.waitFrameStartMs();
                log.warn(
                        "sync_diag channel=inspect event=phase1_wait_frame_while_phase0 cam={} parent_cycle={} "
                                + "phase0_raw_seq={} phase1_raw_seq={} phase0_wait_frame_ms={} "
                                + "hint=DI3#2 arm wait_frame, но worker ещё в wait_frame phase 0 — риск пропуска кадра phase 1",
                        cameraId,
                        parentCycleId,
                        prior.rawTriggerSequence(),
                        rawTriggerSequence,
                        phase0WaitMs
                );
            }
        }
        waitFrameInFlightByCamera.put(
                cameraId,
                new WaitFrameFlight(cameraId, phaseId, parentCycleId, rawTriggerSequence, now)
        );
        log.debug(
                "sync_diag channel=inspect event=wait_frame_start phase={} cam={} parent_cycle={} raw_seq={}",
                phaseId,
                cameraId,
                parentCycleId,
                rawTriggerSequence
        );
    }

    public void onCaptureOk(
            int cameraId,
            int phaseId,
            long parentCycleId,
            long rawTriggerSequence,
            long frameId,
            long orchestratorElapsedMs,
            long workerLatencyMs
    ) {
        clearWaitFrame(cameraId, rawTriggerSequence);
        long sinceDi3Ms = sinceDi3Ms(rawTriggerSequence);
        log.info(
                "sync_diag channel=inspect event=capture_ok phase={} cam={} parent_cycle={} raw_seq={} frame_id={} "
                        + "since_di3_ms={} orch_ms={} worker_latency_ms={}",
                phaseId,
                cameraId,
                parentCycleId,
                rawTriggerSequence,
                frameId,
                sinceDi3Ms >= 0 ? sinceDi3Ms : null,
                orchestratorElapsedMs,
                workerLatencyMs
        );
    }

    public void onCaptureFail(
            int cameraId,
            int phaseId,
            long parentCycleId,
            long rawTriggerSequence,
            String reason,
            long orchestratorElapsedMs
    ) {
        clearWaitFrame(cameraId, rawTriggerSequence);
        long sinceDi3Ms = sinceDi3Ms(rawTriggerSequence);
        log.warn(
                "sync_diag channel=inspect event=capture_fail phase={} cam={} parent_cycle={} raw_seq={} "
                        + "since_di3_ms={} orch_ms={} reason={}",
                phaseId,
                cameraId,
                parentCycleId,
                rawTriggerSequence,
                sinceDi3Ms >= 0 ? sinceDi3Ms : null,
                orchestratorElapsedMs,
                reason == null ? "unknown" : reason
        );
    }

    private void logPhase1WhilePhase0CaptureBusy(long parentCycleId, long now, Long sincePhase0Di3Ms) {
        List<String> busy = new ArrayList<>();
        for (WaitFrameFlight flight : waitFrameInFlightByCamera.values()) {
            if (flight.phaseId() == 0 && flight.parentCycleId() == parentCycleId) {
                busy.add("cam=" + flight.cameraId() + " phase0_wait_frame_ms=" + (now - flight.waitFrameStartMs()));
            }
        }
        if (busy.isEmpty()) {
            return;
        }
        log.warn(
                "sync_diag channel=inspect event=phase1_trigger_while_phase0_capture parent_cycle={} since_phase0_di3_ms={} busy={} "
                        + "hint=DI3#2 пришёл, часть камер ещё в wait_frame phase 0 (съёмка по тригgerу, без барьера)",
                parentCycleId,
                sincePhase0Di3Ms,
                busy
        );
    }

    private void clearWaitFrame(int cameraId, long rawTriggerSequence) {
        waitFrameInFlightByCamera.computeIfPresent(cameraId, (id, flight) ->
                flight.rawTriggerSequence() == rawTriggerSequence ? null : flight);
    }

    private long sinceDi3Ms(long rawTriggerSequence) {
        TriggerMark mark = triggerByRawSequence.get(rawTriggerSequence);
        if (mark == null) {
            return -1L;
        }
        return System.currentTimeMillis() - mark.epochMs();
    }

    private void pruneOldTriggers() {
        if (triggerByRawSequence.size() <= 64) {
            return;
        }
        long cutoff = System.currentTimeMillis() - 60_000L;
        for (Map.Entry<Long, TriggerMark> entry : triggerByRawSequence.entrySet()) {
            if (entry.getValue().epochMs() < cutoff) {
                triggerByRawSequence.remove(entry.getKey(), entry.getValue());
            }
        }
        for (Map.Entry<Long, TriggerMark> entry : phaseZeroByParentCycle.entrySet()) {
            if (entry.getValue().epochMs() < cutoff) {
                phaseZeroByParentCycle.remove(entry.getKey(), entry.getValue());
            }
        }
    }
}

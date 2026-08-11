package com.example.iml.orchestrator.integration.pipeline.session;

import com.example.iml.orchestrator.integration.capture.LineSynchronizedCaptureCoordinator;
import com.example.iml.orchestrator.integration.pipeline.bucket.BucketInspectionAggregator;
import com.example.iml.orchestrator.integration.trigger.InspectionTriggerBus;
import org.apache.logging.log4j.Logger;

import java.util.List;

/**
 * Stop→Start: вклинить камеру в текущий открытый triggerSequence, а не ждать следующий DI3.
 */
public final class InspectionCycleResumeService {

    private final Logger log;
    private final InspectionTriggerBus bus;
    private final PerCameraInspectionGate gate;
    private final BucketInspectionAggregator bucketAggregator;
    private final LineSynchronizedCaptureCoordinator lineCapture;

    public InspectionCycleResumeService(
            Logger log,
            InspectionTriggerBus bus,
            PerCameraInspectionGate gate,
            BucketInspectionAggregator bucketAggregator,
            LineSynchronizedCaptureCoordinator lineCapture
    ) {
        this.log = log;
        this.bus = bus;
        this.gate = gate;
        this.bucketAggregator = bucketAggregator;
        this.lineCapture = lineCapture;
    }

    public void resumeCamera(int cameraId) {
        if (bus == null || gate == null || !gate.isKnownCamera(cameraId)) {
            return;
        }
        Long seq = resolveOpenSequence(cameraId);
        if (seq == null || seq <= 0L) {
            log.info("inspection resume cam={}: no open cycle — wait next trigger", cameraId);
            return;
        }
        if (lineCapture != null) {
            lineCapture.markLateJoin(seq, cameraId);
        }
        boolean injected = bus.injectSequence(cameraId, seq, "rejoin");
        log.info("inspection resume cam={} seq={} injected={}", cameraId, seq, injected);
    }

    public long currentTriggerSequence() {
        return bus == null ? 0L : bus.currentSequence();
    }

    private Long resolveOpenSequence(int cameraId) {
        if (bucketAggregator != null && bucketAggregator.isBucketCamera(cameraId)) {
            Long open = bucketAggregator.findOpenSequenceMissingCamera(cameraId);
            if (open != null) {
                return open;
            }
            List<Integer> peers = bucketAggregator.peerCameraIds(cameraId);
            Long peerSeq = gate.findActivePeerTriggerSequence(cameraId, peers);
            if (peerSeq != null) {
                return peerSeq;
            }
        } else if (gate.hasAnyInspectionInFlight()) {
            long last = bus.lastDispatchedSequence();
            if (last > 0L) {
                return last;
            }
        }
        return null;
    }
}

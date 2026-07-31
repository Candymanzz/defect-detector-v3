package com.example.iml.orchestrator.integration.trigger;

import org.apache.logging.log4j.Logger;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

/** Per-camera offer + staggered/simultaneous line dispatch for {@link InspectionTriggerBus}. */
final class InspectionTriggerDispatch {

    private final Map<Integer, BlockingQueue<InspectionTriggerEvent>> perCamera;
    private final int captureTriggerStaggerMs;
    private final ScheduledExecutorService staggerScheduler;
    private final Logger log;

    InspectionTriggerDispatch(
            Map<Integer, BlockingQueue<InspectionTriggerEvent>> perCamera,
            int captureTriggerStaggerMs,
            ScheduledExecutorService staggerScheduler,
            Logger log
    ) {
        this.perCamera = perCamera;
        this.captureTriggerStaggerMs = captureTriggerStaggerMs;
        this.staggerScheduler = staggerScheduler;
        this.log = log;
    }

    int dispatchLineBroadcast(String source, long seq, Instant receivedAt, List<Integer> cameraIds) {
        List<Integer> targets = resolveTargetCameras(cameraIds);
        if (captureTriggerStaggerMs <= 0 || staggerScheduler == null) {
            log.info(
                    "sync_diag channel=inspect event=line_dispatch trigger_sequence={} cameras={} stagger_ms=0 mode=simultaneous",
                    seq,
                    targets.size()
            );
            int published = 0;
            for (Integer cameraId : targets) {
                if (offerToCamera(cameraId, receivedAt, source, seq)) {
                    published++;
                }
            }
            return published;
        }
        log.info(
                "sync_diag channel=inspect event=line_dispatch trigger_sequence={} cameras={} stagger_ms={} mode=staggered",
                seq,
                targets.size(),
                captureTriggerStaggerMs
        );
        for (int i = 0; i < targets.size(); i++) {
            int cameraId = targets.get(i);
            long delayMs = (long) i * captureTriggerStaggerMs;
            staggerScheduler.schedule(
                    () -> offerToCamera(cameraId, receivedAt, source, seq),
                    delayMs,
                    TimeUnit.MILLISECONDS
            );
        }
        return targets.size();
    }

    boolean offerToCamera(int cameraId, Instant receivedAt, String source, long seq) {
        BlockingQueue<InspectionTriggerEvent> queue = perCamera.get(cameraId);
        if (queue == null) {
            return false;
        }
        InspectionTriggerEvent event = new InspectionTriggerEvent(cameraId, seq, receivedAt, source, false);
        return queue.offer(event);
    }

    private List<Integer> resolveTargetCameras(List<Integer> cameraIds) {
        if (cameraIds == null || cameraIds.isEmpty()) {
            List<Integer> all = new ArrayList<>(perCamera.keySet());
            Collections.sort(all);
            return all;
        }
        List<Integer> filtered = new ArrayList<>();
        for (Integer cameraId : cameraIds) {
            if (cameraId != null && perCamera.containsKey(cameraId)) {
                filtered.add(cameraId);
            }
        }
        filtered.sort(Integer::compareTo);
        return filtered;
    }
}

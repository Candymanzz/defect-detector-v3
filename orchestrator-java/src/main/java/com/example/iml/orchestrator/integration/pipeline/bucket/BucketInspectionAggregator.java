package com.example.iml.orchestrator.integration.pipeline.bucket;

import com.example.iml.orchestrator.integration.fanout.BucketFanOutResult;
import com.example.iml.orchestrator.integration.fanout.BucketFanOutSink;
import com.example.iml.orchestrator.integration.pipeline.InspectionDecision;
import org.apache.logging.log4j.Logger;

import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;

/**
 * Собирает per-frame решения по trigger sequence и группе камер;
 * каждое ведро публикует вердикт на ПЛК и UI независимо от других.
 */
public final class BucketInspectionAggregator implements AutoCloseable {

    private record BucketKey(long triggerSequence, int groupId) {
    }

    private final Logger log;
    private final List<BucketGroup> groups;
    private final Map<Integer, BucketGroup> groupById;
    private final Map<Integer, Integer> groupIdByCamera;
    private final long timeoutMs;
    private final ScheduledExecutorService timeoutExecutor;
    private final ConcurrentHashMap<BucketKey, BucketState> buckets = new ConcurrentHashMap<>();

    public BucketInspectionAggregator(Logger log, BucketInspectionConfig config) {
        this.log = log;
        this.groups = List.copyOf(config.groups());
        this.groupById = new HashMap<>();
        this.groupIdByCamera = new HashMap<>();
        for (BucketGroup group : groups) {
            groupById.put(group.id(), group);
            for (Integer cameraId : group.cameraIds()) {
                groupIdByCamera.put(cameraId, group.id());
            }
        }
        this.timeoutMs = config.timeoutMs();
        this.timeoutExecutor = Executors.newSingleThreadScheduledExecutor(r -> {
            Thread t = new Thread(r, "bucket-inspection-timeout");
            t.setDaemon(true);
            return t;
        });
    }

    public List<BucketGroup> groups() {
        return groups;
    }

    public List<Integer> allCameraIds() {
        return groups.stream()
                .flatMap(group -> group.cameraIds().stream())
                .distinct()
                .sorted()
                .toList();
    }

    public boolean isBucketCamera(int cameraId) {
        return groupIdByCamera.containsKey(cameraId);
    }

    public void recordFrameResult(
            long triggerSequence,
            int cameraId,
            InspectionDecision decision,
            BucketFanOutSink fanOut
    ) {
        Integer groupId = groupIdByCamera.get(cameraId);
        if (groupId == null) {
            return;
        }
        if (triggerSequence <= 0L) {
            log.warn(
                    "bucket frame ignored cam={} group={} frame={}: trigger sequence is missing (need line broadcast)",
                    cameraId,
                    groupId,
                    decision.frameId()
            );
            return;
        }
        BucketGroup group = groupById.get(groupId);
        if (group == null) {
            return;
        }
        BucketKey key = new BucketKey(triggerSequence, groupId);
        BucketState state = buckets.computeIfAbsent(key, ignored -> new BucketState(triggerSequence, groupId, group));
        synchronized (state) {
            if (state.published) {
                return;
            }
            state.frameDecisions.put(cameraId, decision);
            scheduleTimeoutIfNeeded(state, fanOut);
            if (state.frameDecisions.size() >= group.cameraIds().size()) {
                publishBucket(state, fanOut, false);
            }
        }
    }

    private void scheduleTimeoutIfNeeded(BucketState state, BucketFanOutSink fanOut) {
        if (state.timeoutFuture != null) {
            return;
        }
        state.timeoutFuture = timeoutExecutor.schedule(
                () -> onTimeout(state.key(), fanOut),
                timeoutMs,
                TimeUnit.MILLISECONDS
        );
    }

    private void onTimeout(BucketKey key, BucketFanOutSink fanOut) {
        BucketState state = buckets.get(key);
        if (state == null) {
            return;
        }
        synchronized (state) {
            if (state.published) {
                return;
            }
            log.warn(
                    "inspection bucket timeout seq={} group={} received={}/{} cameras={}",
                    key.triggerSequence(),
                    key.groupId(),
                    state.frameDecisions.size(),
                    state.group.cameraIds().size(),
                    state.frameDecisions.keySet()
            );
            publishBucket(state, fanOut, true);
        }
    }

    private void publishBucket(BucketState state, BucketFanOutSink fanOut, boolean timedOut) {
        if (state.published) {
            return;
        }
        state.published = true;
        if (state.timeoutFuture != null) {
            state.timeoutFuture.cancel(false);
        }
        buckets.remove(state.key(), state);

        List<Integer> expectedCameraIds = state.group.cameraIds();
        boolean anyReject = timedOut || state.frameDecisions.size() < expectedCameraIds.size();
        if (!anyReject) {
            boolean captureOnly = state.frameDecisions.values().stream()
                    .allMatch(decision -> decision != null && "CAPTURE".equals(decision.action()));
            if (captureOnly) {
                anyReject = false;
            } else {
                for (Integer cameraId : expectedCameraIds) {
                    InspectionDecision frameDecision = state.frameDecisions.get(cameraId);
                    if (frameDecision == null || !frameDecision.overallPass()) {
                        anyReject = true;
                        break;
                    }
                }
            }
        }
        boolean bucketPass = !anyReject;
        Map<Integer, InspectionDecision> snapshot = Map.copyOf(state.frameDecisions);

        log.info(
                "inspection bucket complete seq={} group={} pass={} frames={}/{} reject_cameras={}",
                state.triggerSequence,
                state.groupId,
                bucketPass,
                snapshot.size(),
                expectedCameraIds.size(),
                rejectCameraIds(snapshot)
        );

        if (fanOut != null) {
            fanOut.publishBucket(new BucketFanOutResult(
                    state.groupId,
                    state.triggerSequence,
                    bucketPass,
                    expectedCameraIds,
                    snapshot
            ));
        }
    }

    private static List<Integer> rejectCameraIds(Map<Integer, InspectionDecision> decisions) {
        return decisions.entrySet().stream()
                .filter(e -> e.getValue() != null && !e.getValue().overallPass())
                .map(Map.Entry::getKey)
                .sorted()
                .toList();
    }

    @Override
    public void close() {
        timeoutExecutor.shutdownNow();
    }

    private static final class BucketState {
        private final long triggerSequence;
        private final int groupId;
        private final BucketGroup group;
        private final Map<Integer, InspectionDecision> frameDecisions = new LinkedHashMap<>();
        private volatile boolean published;
        private volatile ScheduledFuture<?> timeoutFuture;

        private BucketState(long triggerSequence, int groupId, BucketGroup group) {
            this.triggerSequence = triggerSequence;
            this.groupId = groupId;
            this.group = group;
        }

        private BucketKey key() {
            return new BucketKey(triggerSequence, groupId);
        }
    }
}

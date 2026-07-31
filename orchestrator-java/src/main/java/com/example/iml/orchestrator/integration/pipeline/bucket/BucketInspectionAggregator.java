package com.example.iml.orchestrator.integration.pipeline.bucket;

import com.example.iml.orchestrator.integration.fanout.BucketFanOutSink;
import com.example.iml.orchestrator.integration.pipeline.InspectionDecision;
import org.apache.logging.log4j.Logger;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;

/**
 * Собирает per-frame решения по trigger sequence и группе камер.
 * При нескольких вёдрах (две линии) вердикты на ПЛК/UI уходят только когда
 * готовы все вёдра одного {@code triggerSequence} — одним пакетом.
 * При низкой видимости шва на соседних камерах — ужесточённый гейт метрик шва.
 */
public final class BucketInspectionAggregator implements AutoCloseable {

    private final Logger log;
    private final List<BucketGroup> groups;
    private final Map<Integer, BucketGroup> groupById;
    private final Map<Integer, Integer> groupIdByCamera;
    private final ScheduledExecutorService timeoutExecutor;
    private final ConcurrentHashMap<BucketKey, BucketState> buckets = new ConcurrentHashMap<>();
    private final BucketSequenceFanOut sequenceFanOut;
    private final BucketPublishHelper publishHelper;
    private final BucketTimeoutScheduler timeoutScheduler;

    public BucketInspectionAggregator(Logger log, BucketInspectionConfig config) {
        this(log, config, JointSeamPolicy.defaults());
    }

    public BucketInspectionAggregator(Logger log, BucketInspectionConfig config, JointSeamPolicy jointSeamPolicy) {
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
        this.timeoutExecutor = Executors.newSingleThreadScheduledExecutor(r -> {
            Thread t = new Thread(r, "bucket-inspection-timeout");
            t.setDaemon(true);
            return t;
        });
        JointSeamPolicy policy = jointSeamPolicy == null ? JointSeamPolicy.defaults() : jointSeamPolicy;
        this.sequenceFanOut = new BucketSequenceFanOut(log, groups, config.timeoutMs(), timeoutExecutor);
        this.publishHelper = new BucketPublishHelper(log, policy, buckets, sequenceFanOut::enqueueSyncedFanOut);
        this.timeoutScheduler = new BucketTimeoutScheduler(
                log, config.timeoutMs(), timeoutExecutor, buckets, publishHelper);
    }

    public List<BucketGroup> groups() {
        return groups;
    }

    public List<Integer> allCameraIds() {
        return BucketInspectionConfig.collectCameraIds(groups);
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
            timeoutScheduler.scheduleTimeoutIfNeeded(state, fanOut);
            if (state.frameDecisions.size() >= group.cameraIds().size()) {
                publishHelper.publishBucket(state, fanOut, false);
            }
        }
    }

    @Override
    public void close() {
        timeoutExecutor.shutdownNow();
    }
}

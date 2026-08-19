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
 * Собирает per-frame решения по trigger sequence и группе камер.
 * При нескольких вёдрах (две линии) вердикты на ПЛК/UI уходят только когда
 * готовы все вёдра одного {@code triggerSequence} — одним пакетом.
 * При низкой видимости шва на соседних камерах — ужесточённый гейт метрик шва.
 */
public final class BucketInspectionAggregator implements AutoCloseable {

    private record BucketKey(long parentCycleId, int phaseId, int groupId) {
    }

    private record PhaseGroupKey(int phaseId, int groupId) {
    }

    private record PhaseCameraKey(int phaseId, int cameraId) {
    }

    private record BarrierKey(long parentCycleId, int phaseId) {
    }

    private final Logger log;
    private final List<BucketGroup> groups;
    private final Map<PhaseGroupKey, BucketGroup> groupByPhaseAndId;
    private final Map<PhaseCameraKey, Integer> groupIdByPhaseAndCamera;
    private final Map<Integer, List<BucketGroup>> groupsByPhase;
    private final long timeoutMs;
    private final JointSeamPolicy jointSeamPolicy;
    private final ScheduledExecutorService timeoutExecutor;
    private final ConcurrentHashMap<BucketKey, BucketState> buckets = new ConcurrentHashMap<>();
    /** Отдельный барьер пары групп каждой фазы одного parent cycle. */
    private final ConcurrentHashMap<BarrierKey, SequenceBarrier> sequenceBarriers = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<Long, ParentCycleState> parentCycles = new ConcurrentHashMap<>();

    public BucketInspectionAggregator(Logger log, BucketInspectionConfig config) {
        this(log, config, JointSeamPolicy.defaults());
    }

    public BucketInspectionAggregator(Logger log, BucketInspectionConfig config, JointSeamPolicy jointSeamPolicy) {
        this.log = log;
        this.groups = List.copyOf(config.groups());
        this.groupByPhaseAndId = new HashMap<>();
        this.groupIdByPhaseAndCamera = new HashMap<>();
        this.groupsByPhase = new HashMap<>();
        for (BucketGroup group : groups) {
            groupByPhaseAndId.put(new PhaseGroupKey(group.phaseId(), group.id()), group);
            groupsByPhase.computeIfAbsent(group.phaseId(), ignored -> new java.util.ArrayList<>()).add(group);
            for (Integer cameraId : group.cameraIds()) {
                groupIdByPhaseAndCamera.put(new PhaseCameraKey(group.phaseId(), cameraId), group.id());
            }
        }
        this.timeoutMs = config.timeoutMs();
        this.jointSeamPolicy = jointSeamPolicy == null ? JointSeamPolicy.defaults() : jointSeamPolicy;
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
        return groupIdByPhaseAndCamera.keySet().stream().anyMatch(key -> key.cameraId() == cameraId);
    }

    /** Пиры той же bucket-группы (включая саму камеру). */
    public List<Integer> peerCameraIds(int cameraId) {
        Integer groupId = groupIdByPhaseAndCamera.get(new PhaseCameraKey(0, cameraId));
        if (groupId == null) {
            return List.of();
        }
        BucketGroup group = groupByPhaseAndId.get(new PhaseGroupKey(0, groupId));
        return group == null ? List.of() : List.copyOf(group.cameraIds());
    }

    /**
     * Неопубликованное ведро группы, которому ещё не хватает кадра этой камеры.
     * Берём максимальный triggerSequence (самый свежий открытый цикл).
     */
    public Long findOpenSequenceMissingCamera(int cameraId) {
        Integer groupId = groupIdByPhaseAndCamera.get(new PhaseCameraKey(0, cameraId));
        if (groupId == null) {
            return null;
        }
        Long best = null;
        for (Map.Entry<BucketKey, BucketState> entry : buckets.entrySet()) {
            if (entry.getKey().phaseId() != 0 || entry.getKey().groupId() != groupId) {
                continue;
            }
            BucketState state = entry.getValue();
            synchronized (state) {
                if (state.published || state.frameDecisions.containsKey(cameraId)) {
                    continue;
                }
                long seq = state.rawTriggerSequence;
                if (best == null || seq > best) {
                    best = seq;
                }
            }
        }
        return best;
    }

    public void recordFrameResult(
            long triggerSequence,
            int cameraId,
            InspectionDecision decision,
            BucketFanOutSink fanOut
    ) {
        recordFrameResult(
                triggerSequence, triggerSequence, 0, triggerSequence,
                cameraId, decision, fanOut
        );
    }

    /**
     * Phase-aware contract for the two-phase pipeline. Aggregation remains keyed by the
     * raw trigger sequence until the full parent-cycle barrier is implemented.
     */
    public void recordFrameResult(
            long triggerSequence,
            long parentCycleId,
            int phaseId,
            long rawTriggerSequence,
            int cameraId,
            InspectionDecision decision,
            BucketFanOutSink fanOut
    ) {
        log.debug(
                "bucket frame input cam={} trigger_sequence={} parent_cycle={} phase={} raw_seq={}",
                cameraId, triggerSequence, parentCycleId, phaseId, rawTriggerSequence
        );
        Integer groupId = groupIdByPhaseAndCamera.get(new PhaseCameraKey(phaseId, cameraId));
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
        BucketGroup group = groupByPhaseAndId.get(new PhaseGroupKey(phaseId, groupId));
        if (group == null) {
            return;
        }
        BucketKey key = new BucketKey(parentCycleId, phaseId, groupId);
        BucketState state = buckets.computeIfAbsent(
                key,
                ignored -> new BucketState(triggerSequence, parentCycleId, phaseId, rawTriggerSequence, groupId, group)
        );
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
                    state.rawTriggerSequence,
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
        boolean seamStrict = false;
        SeamStrictGate seamGate = evaluateSeamStrictGate(snapshot);
        seamStrict = seamGate.strictActive();
        // Доп. проверка только когда joint уже брак: sibling-strict может добить ведро.
        // Если jointPass=true — условие sibling_vis игнорируется (не forceReject).
        if (bucketPass && seamGate.forceReject()) {
            bucketPass = false;
        }
        if (log != null && (seamGate.jointDecision() != null || seamStrict)) {
            log.info(
                    "inspection bucket seam_gate seq={} group={} seam_strict={} sibling_vis={} "
                            + "joint_cam={} joint_pass={} par={} width={} strict_pass={} force_reject={}",
                    state.triggerSequence,
                    state.groupId,
                    seamStrict,
                    seamGate.siblingVisibility(),
                    seamGate.jointDecision() == null ? null : seamGate.jointDecision().cameraId(),
                    seamGate.jointDecision() == null ? null : seamGate.jointDecision().jointPass(),
                    seamGate.jointDecision() == null ? null : seamGate.jointDecision().jointParallelismDeg(),
                    seamGate.jointDecision() == null ? null : seamGate.jointDecision().jointWidthMm(),
                    seamGate.strictActive() && !seamGate.forceReject(),
                    seamGate.forceReject()
            );
        }

        log.info(
                "inspection bucket complete seq={} group={} pass={} frames={}/{} reject_cameras={} seam_strict={}",
                state.triggerSequence,
                state.groupId,
                bucketPass,
                snapshot.size(),
                expectedCameraIds.size(),
                rejectCameraIds(snapshot),
                seamStrict
        );

        enqueueSyncedFanOut(
                new BucketFanOutResult(
                        state.groupId,
                        state.triggerSequence,
                        bucketPass,
                        expectedCameraIds,
                        snapshot,
                        state.parentCycleId,
                        state.phaseId,
                        state.rawTriggerSequence
                ),
                fanOut
        );
    }

    /** Ждёт только группы своей фазы; phase1 дополнительно ждёт завершения phase0 parent cycle. */
    private void enqueueSyncedFanOut(BucketFanOutResult result, BucketFanOutSink fanOut) {
        if (fanOut == null) {
            return;
        }
        ParentCycleState parent = parentCycles.computeIfAbsent(result.parentCycleId(), ignored -> new ParentCycleState());
        List<BucketGroup> phaseGroups = groupsByPhase.getOrDefault(result.phaseId(), List.of());
        BarrierKey key = new BarrierKey(result.parentCycleId(), result.phaseId());
        SequenceBarrier barrier = sequenceBarriers.computeIfAbsent(
                key,
                ignored -> new SequenceBarrier(key, result.rawTriggerSequence(), phaseGroups)
        );
        List<BucketFanOutResult> toPublish = null;
        synchronized (barrier) {
            if (barrier.flushed) {
                return;
            }
            barrier.readyByGroup.put(result.groupId(), result);
            scheduleSequenceSyncTimeout(barrier, fanOut);
            if (barrier.readyByGroup.size() >= phaseGroups.size()) {
                barrier.pairReady = true;
                if (result.phaseId() == 0 || parent.phase0Done) {
                    toPublish = takeBarrierResults(barrier);
                }
            }
        }
        if (toPublish != null) {
            publishSyncedResults(toPublish, fanOut);
            onPhasePublished(result.parentCycleId(), result.phaseId(), parent, fanOut);
        } else if (result.phaseId() == 1) {
            ensurePhaseZeroBarrier(result.parentCycleId(), fanOut);
        }
    }

    private void scheduleSequenceSyncTimeout(SequenceBarrier barrier, BucketFanOutSink fanOut) {
        if (barrier.syncTimeoutFuture != null) {
            return;
        }
        barrier.syncTimeoutFuture = timeoutExecutor.schedule(
                () -> onSequenceSyncTimeout(barrier.key, fanOut),
                timeoutMs,
                TimeUnit.MILLISECONDS
        );
    }

    private void ensurePhaseZeroBarrier(long parentCycleId, BucketFanOutSink fanOut) {
        if (!groupsByPhase.containsKey(0)) {
            ParentCycleState parent = parentCycles.computeIfAbsent(parentCycleId, ignored -> new ParentCycleState());
            parent.phase0Done = true;
            releaseHeldPhaseOne(parentCycleId, parent, fanOut);
            return;
        }
        BarrierKey key = new BarrierKey(parentCycleId, 0);
        SequenceBarrier phaseZero = sequenceBarriers.computeIfAbsent(
                key,
                ignored -> new SequenceBarrier(key, parentCycleId, groupsByPhase.get(0))
        );
        synchronized (phaseZero) {
            scheduleSequenceSyncTimeout(phaseZero, fanOut);
        }
    }

    private void onSequenceSyncTimeout(BarrierKey key, BucketFanOutSink fanOut) {
        SequenceBarrier barrier = sequenceBarriers.get(key);
        if (barrier == null) {
            return;
        }
        ParentCycleState parent = parentCycles.computeIfAbsent(key.parentCycleId(), ignored -> new ParentCycleState());
        List<BucketFanOutResult> toPublish;
        synchronized (barrier) {
            if (barrier.flushed) {
                return;
            }
            for (BucketGroup group : barrier.expectedGroups) {
                if (barrier.readyByGroup.containsKey(group.id())) {
                    continue;
                }
                log.warn(
                        "inspection phase sync timeout parent={} phase={} missing_group={} — synthetic reject",
                        key.parentCycleId(),
                        key.phaseId(),
                        group.id()
                );
                barrier.readyByGroup.put(
                        group.id(),
                        new BucketFanOutResult(
                                group.id(),
                                barrier.rawTriggerSequence,
                                false,
                                group.cameraIds(),
                                Map.of(),
                                key.parentCycleId(),
                                key.phaseId(),
                                barrier.rawTriggerSequence
                        )
                );
            }
            barrier.pairReady = true;
            toPublish = key.phaseId() == 0 || parent.phase0Done ? takeBarrierResults(barrier) : null;
        }
        if (toPublish != null) {
            publishSyncedResults(toPublish, fanOut);
            onPhasePublished(key.parentCycleId(), key.phaseId(), parent, fanOut);
        }
    }

    private List<BucketFanOutResult> takeBarrierResults(SequenceBarrier barrier) {
        barrier.flushed = true;
        if (barrier.syncTimeoutFuture != null) {
            barrier.syncTimeoutFuture.cancel(false);
        }
        sequenceBarriers.remove(barrier.key, barrier);
        return barrier.expectedGroups.stream()
                .map(group -> barrier.readyByGroup.get(group.id()))
                .filter(result -> result != null)
                .sorted(java.util.Comparator.comparingInt(BucketFanOutResult::groupId))
                .toList();
    }

    private void onPhasePublished(
            long parentCycleId,
            int phaseId,
            ParentCycleState parent,
            BucketFanOutSink fanOut
    ) {
        if (phaseId == 0) {
            parent.phase0Done = true;
            releaseHeldPhaseOne(parentCycleId, parent, fanOut);
            if (!parent.phase1Done) {
                ensurePhaseOneBarrier(parentCycleId, fanOut);
            }
        } else {
            parent.phase1Done = true;
        }
        if (parent.phase0Done && (parent.phase1Done || !groupsByPhase.containsKey(1))) {
            parentCycles.remove(parentCycleId, parent);
        }
    }

    private void ensurePhaseOneBarrier(long parentCycleId, BucketFanOutSink fanOut) {
        List<BucketGroup> phaseOneGroups = groupsByPhase.get(1);
        if (phaseOneGroups == null || phaseOneGroups.isEmpty()) {
            return;
        }
        BarrierKey key = new BarrierKey(parentCycleId, 1);
        SequenceBarrier phaseOne = sequenceBarriers.computeIfAbsent(
                key,
                ignored -> new SequenceBarrier(key, parentCycleId + 1L, phaseOneGroups)
        );
        synchronized (phaseOne) {
            if (!phaseOne.flushed) {
                scheduleSequenceSyncTimeout(phaseOne, fanOut);
            }
        }
    }

    private void releaseHeldPhaseOne(long parentCycleId, ParentCycleState parent, BucketFanOutSink fanOut) {
        SequenceBarrier phaseOne = sequenceBarriers.get(new BarrierKey(parentCycleId, 1));
        if (phaseOne == null) {
            return;
        }
        List<BucketFanOutResult> results = null;
        synchronized (phaseOne) {
            if (!phaseOne.flushed && phaseOne.pairReady) {
                results = takeBarrierResults(phaseOne);
            }
        }
        if (results != null) {
            publishSyncedResults(results, fanOut);
            onPhasePublished(parentCycleId, 1, parent, fanOut);
        }
    }

    private void publishSyncedResults(List<BucketFanOutResult> results, BucketFanOutSink fanOut) {
        if (fanOut == null || results == null || results.isEmpty()) {
            return;
        }
        log.info(
                "inspection phase fanout parent={} phase={} groups={} passes={}",
                results.get(0).parentCycleId(),
                results.get(0).phaseId(),
                results.stream().map(BucketFanOutResult::groupId).toList(),
                results.stream().map(BucketFanOutResult::overallPass).toList()
        );
        for (BucketFanOutResult result : results) {
            fanOut.publishBucket(result);
        }
    }

    private SeamStrictGate evaluateSeamStrictGate(Map<Integer, InspectionDecision> decisions) {
        InspectionDecision joint = null;
        double siblingSum = 0.0;
        int siblingCount = 0;
        for (InspectionDecision decision : decisions.values()) {
            if (decision == null) {
                continue;
            }
            if (decision.jointCamera()) {
                joint = decision;
            } else if (decision.jointVisibility() > 0.0 || !"CAPTURE".equals(decision.action())) {
                siblingSum += decision.jointVisibility();
                siblingCount++;
            }
        }
        if (joint == null) {
            return SeamStrictGate.inactive();
        }
        double siblingVisibility = siblingCount == 0 ? 1.0 : siblingSum / siblingCount;
        // Шов на joint-камере прошёл обычные пороги — sibling strict не валит ведро.
        if (joint.jointPass()) {
            return new SeamStrictGate(false, false, siblingVisibility, joint);
        }
        // Шов дал брак — доп. проверка: при низкой видимости у соседей ужесточённые пороги.
        boolean strictActive = siblingVisibility < jointSeamPolicy.siblingMinVisibility();
        if (!strictActive) {
            return new SeamStrictGate(false, false, siblingVisibility, joint);
        }
        boolean strictPass = jointSeamPolicy.passesStrict(joint.jointParallelismDeg(), joint.jointWidthMm());
        return new SeamStrictGate(true, !strictPass, siblingVisibility, joint);
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

    private record SeamStrictGate(
            boolean strictActive,
            boolean forceReject,
            double siblingVisibility,
            InspectionDecision jointDecision
    ) {
        static SeamStrictGate inactive() {
            return new SeamStrictGate(false, false, 1.0, null);
        }
    }

    private static final class BucketState {
        private final long triggerSequence;
        private final long parentCycleId;
        private final int phaseId;
        private final long rawTriggerSequence;
        private final int groupId;
        private final BucketGroup group;
        private final Map<Integer, InspectionDecision> frameDecisions = new LinkedHashMap<>();
        private volatile boolean published;
        private volatile ScheduledFuture<?> timeoutFuture;

        private BucketState(
                long triggerSequence,
                long parentCycleId,
                int phaseId,
                long rawTriggerSequence,
                int groupId,
                BucketGroup group
        ) {
            this.triggerSequence = triggerSequence;
            this.parentCycleId = parentCycleId;
            this.phaseId = phaseId;
            this.rawTriggerSequence = rawTriggerSequence;
            this.groupId = groupId;
            this.group = group;
        }

        private BucketKey key() {
            return new BucketKey(parentCycleId, phaseId, groupId);
        }
    }

    private static final class SequenceBarrier {
        private final BarrierKey key;
        private final long rawTriggerSequence;
        private final List<BucketGroup> expectedGroups;
        private final Map<Integer, BucketFanOutResult> readyByGroup = new LinkedHashMap<>();
        private volatile boolean pairReady;
        private volatile boolean flushed;
        private volatile ScheduledFuture<?> syncTimeoutFuture;

        private SequenceBarrier(BarrierKey key, long rawTriggerSequence, List<BucketGroup> expectedGroups) {
            this.key = key;
            this.rawTriggerSequence = rawTriggerSequence;
            this.expectedGroups = List.copyOf(expectedGroups);
        }
    }

    private static final class ParentCycleState {
        private volatile boolean phase0Done;
        private volatile boolean phase1Done;
    }
}

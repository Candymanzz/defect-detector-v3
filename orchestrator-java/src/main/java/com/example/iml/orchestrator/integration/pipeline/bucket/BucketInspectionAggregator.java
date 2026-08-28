package com.example.iml.orchestrator.integration.pipeline.bucket;

import com.example.iml.orchestrator.integration.fanout.BucketFanOutResult;
import com.example.iml.orchestrator.integration.fanout.BucketFanOutSink;
import com.example.iml.orchestrator.integration.pipeline.InspectionDecision;
import com.example.iml.orchestrator.integration.pipeline.session.PerCameraInspectionGate;
import org.apache.logging.log4j.Logger;

import java.util.ArrayList;
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
 *
 * Soft-stop: в вердикт ведра входят только камеры с включённой инспекцией.
 * Остановленная камера не обязана прислать кадр и не валит ведро таймаутом.
 */
public final class BucketInspectionAggregator implements AutoCloseable {

    private record BucketKey(long triggerSequence, int groupId) {
    }

    private final Logger log;
    private final List<BucketGroup> groups;
    private final Map<Integer, BucketGroup> groupById;
    private final Map<Integer, Integer> groupIdByCamera;
    private final long timeoutMs;
    private final JointSeamPolicy jointSeamPolicy;
    private final PerCameraInspectionGate inspectionGate;
    private final ScheduledExecutorService timeoutExecutor;
    private final ConcurrentHashMap<BucketKey, BucketState> buckets = new ConcurrentHashMap<>();
    /** Барьер: ждать все groupId одного triggerSequence перед fanOut. */
    private final ConcurrentHashMap<Long, SequenceBarrier> sequenceBarriers = new ConcurrentHashMap<>();
    private volatile BucketFanOutSink lastFanOut;

    public BucketInspectionAggregator(Logger log, BucketInspectionConfig config) {
        this(log, config, JointSeamPolicy.defaults(), null);
    }

    public BucketInspectionAggregator(Logger log, BucketInspectionConfig config, JointSeamPolicy jointSeamPolicy) {
        this(log, config, jointSeamPolicy, null);
    }

    public BucketInspectionAggregator(
            Logger log,
            BucketInspectionConfig config,
            JointSeamPolicy jointSeamPolicy,
            PerCameraInspectionGate inspectionGate
    ) {
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
        this.jointSeamPolicy = jointSeamPolicy == null ? JointSeamPolicy.defaults() : jointSeamPolicy;
        this.inspectionGate = inspectionGate;
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

    /** Пиры той же bucket-группы (включая саму камеру). */
    public List<Integer> peerCameraIds(int cameraId) {
        Integer groupId = groupIdByCamera.get(cameraId);
        if (groupId == null) {
            return List.of();
        }
        BucketGroup group = groupById.get(groupId);
        return group == null ? List.of() : List.copyOf(group.cameraIds());
    }

    /**
     * Неопубликованное ведро группы, которому ещё не хватает кадра этой камеры.
     * Берём максимальный triggerSequence (самый свежий открытый цикл).
     */
    public Long findOpenSequenceMissingCamera(int cameraId) {
        Integer groupId = groupIdByCamera.get(cameraId);
        if (groupId == null) {
            return null;
        }
        Long best = null;
        for (Map.Entry<BucketKey, BucketState> entry : buckets.entrySet()) {
            if (entry.getKey().groupId() != groupId) {
                continue;
            }
            BucketState state = entry.getValue();
            synchronized (state) {
                if (state.published || state.frameDecisions.containsKey(cameraId)) {
                    continue;
                }
                if (!isCameraRequired(cameraId)) {
                    continue;
                }
                long seq = entry.getKey().triggerSequence();
                if (best == null || seq > best) {
                    best = seq;
                }
            }
        }
        return best;
    }

    /**
     * Soft-stop / Start: пересчитать открытые вёдра — возможно, хватает кадров
     * среди оставшихся включённых камер.
     */
    public void reevaluateOpenBucketsAfterGateChange() {
        BucketFanOutSink fanOut = lastFanOut;
        if (fanOut == null) {
            return;
        }
        List<BucketState> ready = new ArrayList<>();
        for (BucketState state : buckets.values()) {
            synchronized (state) {
                if (state.published) {
                    continue;
                }
                if (isBucketComplete(state)) {
                    ready.add(state);
                }
            }
        }
        for (BucketState state : ready) {
            synchronized (state) {
                if (!state.published && isBucketComplete(state)) {
                    publishBucket(state, fanOut, false);
                }
            }
        }
    }

    public void recordFrameResult(
            long triggerSequence,
            int cameraId,
            InspectionDecision decision,
            BucketFanOutSink fanOut
    ) {
        recordFrameResult(triggerSequence, cameraId, decision, fanOut, null);
    }

    /**
     * @param afterPlcUi UI/артефакты камеры — только после FINS по seq (приоритет ПЛК над UI).
     */
    public void recordFrameResult(
            long triggerSequence,
            int cameraId,
            InspectionDecision decision,
            BucketFanOutSink fanOut,
            Runnable afterPlcUi
    ) {
        Integer groupId = groupIdByCamera.get(cameraId);
        if (groupId == null) {
            if (afterPlcUi != null) {
                afterPlcUi.run();
            }
            return;
        }
        if (triggerSequence <= 0L) {
            log.warn(
                    "bucket frame ignored cam={} group={} frame={}: trigger sequence is missing (need line broadcast)",
                    cameraId,
                    groupId,
                    decision.frameId()
            );
            if (afterPlcUi != null) {
                afterPlcUi.run();
            }
            return;
        }
        BucketGroup group = groupById.get(groupId);
        if (group == null) {
            if (afterPlcUi != null) {
                afterPlcUi.run();
            }
            return;
        }
        if (fanOut != null) {
            lastFanOut = fanOut;
        }
        BucketKey key = new BucketKey(triggerSequence, groupId);
        BucketState state = buckets.computeIfAbsent(key, ignored -> new BucketState(triggerSequence, groupId, group));
        synchronized (state) {
            if (state.published) {
                if (afterPlcUi != null) {
                    afterPlcUi.run();
                }
                return;
            }
            state.frameDecisions.put(cameraId, decision);
            if (afterPlcUi != null) {
                state.pendingUiByCamera.put(cameraId, afterPlcUi);
            }
            scheduleTimeoutIfNeeded(state, fanOut);
            if (isBucketComplete(state)) {
                publishBucket(state, fanOut, false);
            }
        }
    }

    private boolean isCameraRequired(int cameraId) {
        // Without a gate every configured camera is required (legacy / unit tests).
        return inspectionGate == null || inspectionGate.isInspectionEnabled(cameraId);
    }

    private List<Integer> requiredCameraIds(BucketGroup group) {
        List<Integer> required = new ArrayList<>();
        for (Integer cameraId : group.cameraIds()) {
            if (isCameraRequired(cameraId)) {
                required.add(cameraId);
            }
        }
        return required;
    }

    private boolean isBucketComplete(BucketState state) {
        List<Integer> required = requiredCameraIds(state.group);
        if (required.isEmpty()) {
            // Все камеры группы выключены — закрываем ведро без ожидания кадров.
            return true;
        }
        for (Integer cameraId : required) {
            if (!state.frameDecisions.containsKey(cameraId)) {
                return false;
            }
        }
        return true;
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
            List<Integer> required = requiredCameraIds(state.group);
            log.warn(
                    "inspection bucket timeout seq={} group={} received={}/{} required={} cameras={}",
                    key.triggerSequence(),
                    key.groupId(),
                    state.frameDecisions.size(),
                    state.group.cameraIds().size(),
                    required,
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
        List<Integer> requiredCameraIds = requiredCameraIds(state.group);
        boolean anyReject = false;
        if (requiredCameraIds.isEmpty()) {
            // Soft-stop всей группы: не шлём брак из-за отсутствия кадров.
            anyReject = false;
        } else {
            boolean missingRequired = false;
            for (Integer cameraId : requiredCameraIds) {
                if (!state.frameDecisions.containsKey(cameraId)) {
                    missingRequired = true;
                    break;
                }
            }
            // Incomplete set among enabled cameras (timeout) → reject.
            // Disabled cameras are excluded from requiredCameraIds and do not force reject.
            anyReject = missingRequired;
            if (!anyReject) {
                boolean captureOnly = requiredCameraIds.stream()
                        .map(state.frameDecisions::get)
                        .allMatch(decision -> decision != null && "CAPTURE".equals(decision.action()));
                if (!captureOnly) {
                    for (Integer cameraId : requiredCameraIds) {
                        InspectionDecision frameDecision = state.frameDecisions.get(cameraId);
                        if (frameDecision == null || !frameDecision.overallPass()) {
                            anyReject = true;
                            break;
                        }
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
                "inspection bucket complete seq={} group={} pass={} frames={}/{} required={} timed_out={} reject_cameras={} seam_strict={}",
                state.triggerSequence,
                state.groupId,
                bucketPass,
                snapshot.size(),
                expectedCameraIds.size(),
                requiredCameraIds,
                timedOut,
                rejectCameraIds(snapshot),
                seamStrict
        );

        List<Runnable> pendingUi = new ArrayList<>(state.pendingUiByCamera.values());
        state.pendingUiByCamera.clear();

        enqueueSyncedFanOut(
                new BucketFanOutResult(
                        state.groupId,
                        state.triggerSequence,
                        bucketPass,
                        expectedCameraIds,
                        snapshot
                ),
                pendingUi,
                fanOut
        );
    }

    /**
     * Одно ведро → сразу в fanOut. Два+ ведра → ждать все groupId одного seq, потом слать пакетом
     * (reject_line_1 и reject_line_2 синхронно). UI камер — только после FINS.
     */
    private void enqueueSyncedFanOut(
            BucketFanOutResult result,
            List<Runnable> pendingUi,
            BucketFanOutSink fanOut
    ) {
        if (fanOut == null) {
            runPendingUi(pendingUi);
            return;
        }
        if (groups.size() <= 1) {
            fanOut.publishBucket(result);
            runPendingUi(pendingUi);
            return;
        }
        SequenceBarrier barrier = sequenceBarriers.computeIfAbsent(
                result.triggerSequence(),
                seq -> new SequenceBarrier(seq)
        );
        List<BucketFanOutResult> toPublish = null;
        List<Runnable> uiToFlush = null;
        synchronized (barrier) {
            if (barrier.flushed) {
                runPendingUi(pendingUi);
                return;
            }
            barrier.readyByGroup.put(result.groupId(), result);
            if (pendingUi != null) {
                barrier.pendingUi.addAll(pendingUi);
            }
            scheduleSequenceSyncTimeout(barrier, fanOut);
            if (barrier.readyByGroup.size() >= groups.size()) {
                toPublish = takeBarrierResults(barrier);
                uiToFlush = new ArrayList<>(barrier.pendingUi);
                barrier.pendingUi.clear();
            }
        }
        if (toPublish != null) {
            publishSyncedResults(toPublish, uiToFlush, fanOut);
        }
    }

    private void scheduleSequenceSyncTimeout(SequenceBarrier barrier, BucketFanOutSink fanOut) {
        if (barrier.syncTimeoutFuture != null) {
            return;
        }
        barrier.syncTimeoutFuture = timeoutExecutor.schedule(
                () -> onSequenceSyncTimeout(barrier.triggerSequence, fanOut),
                timeoutMs,
                TimeUnit.MILLISECONDS
        );
    }

    private void onSequenceSyncTimeout(long triggerSequence, BucketFanOutSink fanOut) {
        SequenceBarrier barrier = sequenceBarriers.get(triggerSequence);
        if (barrier == null) {
            return;
        }
        List<BucketFanOutResult> toPublish;
        List<Runnable> uiToFlush;
        synchronized (barrier) {
            if (barrier.flushed) {
                return;
            }
            for (BucketGroup group : groups) {
                if (barrier.readyByGroup.containsKey(group.id())) {
                    continue;
                }
                log.warn(
                        "inspection sequence sync timeout seq={} missing_group={} — synthetic reject for line",
                        triggerSequence,
                        group.id()
                );
                barrier.readyByGroup.put(
                        group.id(),
                        new BucketFanOutResult(
                                group.id(),
                                triggerSequence,
                                false,
                                group.cameraIds(),
                                Map.of()
                        )
                );
            }
            toPublish = takeBarrierResults(barrier);
            uiToFlush = new ArrayList<>(barrier.pendingUi);
            barrier.pendingUi.clear();
        }
        publishSyncedResults(toPublish, uiToFlush, fanOut);
    }

    private List<BucketFanOutResult> takeBarrierResults(SequenceBarrier barrier) {
        barrier.flushed = true;
        if (barrier.syncTimeoutFuture != null) {
            barrier.syncTimeoutFuture.cancel(false);
        }
        sequenceBarriers.remove(barrier.triggerSequence, barrier);
        return groups.stream()
                .map(group -> barrier.readyByGroup.get(group.id()))
                .filter(result -> result != null)
                .toList();
    }

    private void publishSyncedResults(
            List<BucketFanOutResult> results,
            List<Runnable> pendingUi,
            BucketFanOutSink fanOut
    ) {
        if (fanOut == null || results == null || results.isEmpty()) {
            runPendingUi(pendingUi);
            return;
        }
        log.info(
                "inspection sequence fanout seq={} groups={} passes={}",
                results.get(0).triggerSequence(),
                results.stream().map(BucketFanOutResult::groupId).toList(),
                results.stream().map(BucketFanOutResult::overallPass).toList()
        );
        // Сначала все линии на ПЛК (publishBucket ждёт фронт), потом UI.
        for (BucketFanOutResult result : results) {
            fanOut.publishBucket(result);
        }
        runPendingUi(pendingUi);
    }

    private void runPendingUi(List<Runnable> pendingUi) {
        if (pendingUi == null || pendingUi.isEmpty()) {
            return;
        }
        for (Runnable ui : pendingUi) {
            if (ui == null) {
                continue;
            }
            try {
                ui.run();
            } catch (RuntimeException e) {
                if (log != null) {
                    log.warn("deferred ui after plc failed: {}", e.getMessage());
                }
            }
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
        private final int groupId;
        private final BucketGroup group;
        private final Map<Integer, InspectionDecision> frameDecisions = new LinkedHashMap<>();
        private final Map<Integer, Runnable> pendingUiByCamera = new LinkedHashMap<>();
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

    /** Ожидание всех вёдер одного triggerSequence перед отправкой на ПЛК/UI. */
    private static final class SequenceBarrier {
        private final long triggerSequence;
        private final Map<Integer, BucketFanOutResult> readyByGroup = new LinkedHashMap<>();
        private final List<Runnable> pendingUi = new ArrayList<>();
        private volatile boolean flushed;
        private volatile ScheduledFuture<?> syncTimeoutFuture;

        private SequenceBarrier(long triggerSequence) {
            this.triggerSequence = triggerSequence;
        }
    }
}

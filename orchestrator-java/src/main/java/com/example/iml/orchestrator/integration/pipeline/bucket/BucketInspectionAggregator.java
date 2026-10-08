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
 * Собирает per-frame решения по циклу, фазе и группе камер.
 * <p>
 * Как готовые вёдра уходят на ПЛК/UI, задаёт {@link BucketFanOutMode} (DECISIONS.md, D-001):
 * <ul>
 *   <li>{@link BucketFanOutMode#SEQUENCE_BARRIER} — станок на 2 изделия: вердикты всех вёдер одной фазы
 *       одного {@code triggerSequence} уходят пакетом (reject_line_1 и reject_line_2 синхронно);</li>
 *   <li>{@link BucketFanOutMode#IMMEDIATE} — станок на 4 изделия: каждое ведро публикуется сразу,
 *       независимо от соседней группы и второй фазы.</li>
 * </ul>
 * Спорные места вынесены в отдельные переключатели (по умолчанию берутся из режима):
 * {@code early_reject} — ранний брак ручки; {@code suppress_capture_only} — не публиковать ведро,
 * где все кадры сняты без эталона.
 * <p>
 * Soft-stop: в вердикт ведра входят только камеры с включённой инспекцией.
 * Остановленная камера не обязана прислать кадр и не валит ведро таймаутом.
 * При низкой видимости шва на соседних камерах — ужесточённый гейт метрик шва.
 */
public final class BucketInspectionAggregator implements AutoCloseable {

    private record BucketKey(long parentCycleId, int phaseId, int groupId) {
    }

    private record PhaseGroupKey(int phaseId, int groupId) {
    }

    private record PhaseCameraKey(int phaseId, int cameraId) {
    }

    /** Ключ барьера: цикл + фаза (в одной фазе ждём все её ведра). */
    private record BarrierKey(long parentCycleId, int phaseId) {
    }

    private final Logger log;
    private final List<BucketGroup> groups;
    private final Map<PhaseGroupKey, BucketGroup> groupByPhaseAndId;
    private final Map<PhaseCameraKey, Integer> groupIdByPhaseAndCamera;
    private final long timeoutMs;
    private final BucketFanOutMode fanOutMode;
    private final boolean earlyReject;
    private final boolean suppressCaptureOnly;
    private final JointSeamPolicy jointSeamPolicy;
    private final PerCameraInspectionGate inspectionGate;
    private final ScheduledExecutorService timeoutExecutor;
    private final ConcurrentHashMap<BucketKey, BucketState> buckets = new ConcurrentHashMap<>();
    /** Только SEQUENCE_BARRIER: ждать все группы одной фазы одного цикла перед fanOut. */
    private final ConcurrentHashMap<BarrierKey, SequenceBarrier> sequenceBarriers = new ConcurrentHashMap<>();
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
        this.groupByPhaseAndId = new HashMap<>();
        this.groupIdByPhaseAndCamera = new HashMap<>();
        for (BucketGroup group : groups) {
            groupByPhaseAndId.put(new PhaseGroupKey(group.phaseId(), group.id()), group);
            for (Integer cameraId : group.cameraIds()) {
                groupIdByPhaseAndCamera.put(new PhaseCameraKey(group.phaseId(), cameraId), group.id());
            }
        }
        this.timeoutMs = config.timeoutMs();
        this.fanOutMode = config.fanOutMode();
        this.earlyReject = config.earlyReject();
        this.suppressCaptureOnly = config.suppressCaptureOnly();
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

    public BucketFanOutMode fanOutMode() {
        return fanOutMode;
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

    public Integer groupIdFor(int phaseId, int cameraId) {
        return groupIdByPhaseAndCamera.get(new PhaseCameraKey(phaseId, cameraId));
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
                if (!isCameraRequired(cameraId)) {
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
        recordFrameResult(
                triggerSequence, triggerSequence, 0, triggerSequence,
                cameraId, decision, fanOut, afterPlcUi
        );
    }

    /**
     * Phase-aware contract for the two-phase pipeline. Aggregation is keyed by
     * {@code (parentCycleId, phaseId, groupId)}.
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
        recordFrameResult(
                triggerSequence, parentCycleId, phaseId, rawTriggerSequence,
                cameraId, decision, fanOut, null
        );
    }

    /**
     * @param afterPlcUi UI/артефакты камеры — только после FINS по seq (приоритет ПЛК над UI).
     */
    public void recordFrameResult(
            long triggerSequence,
            long parentCycleId,
            int phaseId,
            long rawTriggerSequence,
            int cameraId,
            InspectionDecision decision,
            BucketFanOutSink fanOut,
            Runnable afterPlcUi
    ) {
        log.debug(
                "bucket frame input cam={} trigger_sequence={} parent_cycle={} phase={} raw_seq={}",
                cameraId, triggerSequence, parentCycleId, phaseId, rawTriggerSequence
        );
        Integer groupId = groupIdByPhaseAndCamera.get(new PhaseCameraKey(phaseId, cameraId));
        if (groupId == null) {
            runUi(afterPlcUi);
            return;
        }
        if (triggerSequence <= 0L) {
            log.warn(
                    "bucket frame ignored cam={} group={} frame={}: trigger sequence is missing (need line broadcast)",
                    cameraId,
                    groupId,
                    decision.frameId()
            );
            runUi(afterPlcUi);
            return;
        }
        BucketGroup group = groupByPhaseAndId.get(new PhaseGroupKey(phaseId, groupId));
        if (group == null) {
            runUi(afterPlcUi);
            return;
        }
        if (fanOut != null) {
            lastFanOut = fanOut;
        }
        BucketKey key = new BucketKey(parentCycleId, phaseId, groupId);
        BucketState state = buckets.computeIfAbsent(
                key,
                ignored -> new BucketState(triggerSequence, parentCycleId, phaseId, rawTriggerSequence, groupId, group)
        );
        synchronized (state) {
            if (state.published) {
                runUi(afterPlcUi);
                return;
            }
            state.frameDecisions.put(cameraId, decision);
            if (afterPlcUi != null) {
                state.pendingUiByCamera.put(cameraId, afterPlcUi);
            }
            if (earlyReject && !decision.overallPass() && fanOut != null) {
                fanOut.publishEarlyPlasticHandleReject(triggerSequence, cameraId);
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
                    state.rawTriggerSequence,
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
        Map<Integer, InspectionDecision> snapshot = Map.copyOf(state.frameDecisions);
        List<Runnable> pendingUi = new ArrayList<>(state.pendingUiByCamera.values());
        state.pendingUiByCamera.clear();

        if (suppressCaptureOnly) {
            boolean captureOnly = !snapshot.isEmpty()
                    && snapshot.values().stream()
                    .allMatch(decision -> decision != null && "CAPTURE".equals(decision.action()));
            if (captureOnly) {
                log.info(
                        "inspection bucket capture-only suppressed parent={} phase={} group={} frames={}/{} timeout={}",
                        state.parentCycleId,
                        state.phaseId,
                        state.groupId,
                        snapshot.size(),
                        expectedCameraIds.size(),
                        timedOut
                );
                runPendingUi(pendingUi);
                return;
            }
        }

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
            anyReject = timedOut || missingRequired;
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

        enqueueFanOut(
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
                pendingUi,
                fanOut
        );
    }

    private void enqueueFanOut(BucketFanOutResult result, List<Runnable> pendingUi, BucketFanOutSink fanOut) {
        if (fanOut == null) {
            runPendingUi(pendingUi);
            return;
        }
        if (fanOutMode == BucketFanOutMode.IMMEDIATE) {
            // Каждая группа независима: публикуется сразу и не создаёт вердикты за соседние группы.
            log.info(
                    "inspection group immediate fanout parent={} phase={} group={} pass={}",
                    result.parentCycleId(), result.phaseId(), result.groupId(), result.overallPass()
            );
            fanOut.publishBucket(result);
            runPendingUi(pendingUi);
            return;
        }
        enqueueBarrierFanOut(result, pendingUi, fanOut);
    }

    /**
     * Одно ведро в фазе → сразу в fanOut. Два+ ведра → ждать все groupId одного seq, потом слать пакетом
     * (reject_line_1 и reject_line_2 синхронно). UI камер — только после FINS.
     */
    private void enqueueBarrierFanOut(
            BucketFanOutResult result,
            List<Runnable> pendingUi,
            BucketFanOutSink fanOut
    ) {
        if (groupsInPhase(result.phaseId()).size() <= 1) {
            fanOut.publishBucket(result);
            runPendingUi(pendingUi);
            return;
        }
        BarrierKey barrierKey = new BarrierKey(result.parentCycleId(), result.phaseId());
        SequenceBarrier barrier = sequenceBarriers.computeIfAbsent(
                barrierKey,
                key -> new SequenceBarrier(key, result.triggerSequence(), result.rawTriggerSequence())
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
            if (barrier.readyByGroup.size() >= groupsInPhase(barrier.key.phaseId()).size()) {
                toPublish = takeBarrierResults(barrier);
                uiToFlush = new ArrayList<>(barrier.pendingUi);
                barrier.pendingUi.clear();
            }
        }
        if (toPublish != null) {
            publishSyncedResults(toPublish, uiToFlush, fanOut);
        }
    }

    private List<BucketGroup> groupsInPhase(int phaseId) {
        return groups.stream().filter(group -> group.phaseId() == phaseId).toList();
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

    private void onSequenceSyncTimeout(BarrierKey barrierKey, BucketFanOutSink fanOut) {
        SequenceBarrier barrier = sequenceBarriers.get(barrierKey);
        if (barrier == null) {
            return;
        }
        List<BucketFanOutResult> toPublish;
        List<Runnable> uiToFlush;
        synchronized (barrier) {
            if (barrier.flushed) {
                return;
            }
            for (BucketGroup group : groupsInPhase(barrierKey.phaseId())) {
                if (barrier.readyByGroup.containsKey(group.id())) {
                    continue;
                }
                log.warn(
                        "inspection sequence sync timeout seq={} missing_group={} — synthetic reject for line",
                        barrier.triggerSequence,
                        group.id()
                );
                barrier.readyByGroup.put(
                        group.id(),
                        new BucketFanOutResult(
                                group.id(),
                                barrier.triggerSequence,
                                false,
                                group.cameraIds(),
                                Map.of(),
                                barrierKey.parentCycleId(),
                                barrierKey.phaseId(),
                                barrier.rawTriggerSequence
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
        sequenceBarriers.remove(barrier.key, barrier);
        return groupsInPhase(barrier.key.phaseId()).stream()
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
        fanOut.finishSequence(results.get(0).triggerSequence());
        runPendingUi(pendingUi);
    }

    private void runUi(Runnable ui) {
        if (ui == null) {
            return;
        }
        try {
            ui.run();
        } catch (RuntimeException e) {
            if (log != null) {
                log.warn("deferred ui after plc failed: {}", e.getMessage());
            }
        }
    }

    private void runPendingUi(List<Runnable> pendingUi) {
        if (pendingUi == null || pendingUi.isEmpty()) {
            return;
        }
        for (Runnable ui : pendingUi) {
            runUi(ui);
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
        private final Map<Integer, Runnable> pendingUiByCamera = new LinkedHashMap<>();
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

    /** Ожидание всех вёдер одной фазы одного цикла перед отправкой на ПЛК/UI (SEQUENCE_BARRIER). */
    private static final class SequenceBarrier {
        private final BarrierKey key;
        private final long triggerSequence;
        private final long rawTriggerSequence;
        private final Map<Integer, BucketFanOutResult> readyByGroup = new LinkedHashMap<>();
        private final List<Runnable> pendingUi = new ArrayList<>();
        private volatile boolean flushed;
        private volatile ScheduledFuture<?> syncTimeoutFuture;

        private SequenceBarrier(BarrierKey key, long triggerSequence, long rawTriggerSequence) {
            this.key = key;
            this.triggerSequence = triggerSequence;
            this.rawTriggerSequence = rawTriggerSequence;
        }
    }
}

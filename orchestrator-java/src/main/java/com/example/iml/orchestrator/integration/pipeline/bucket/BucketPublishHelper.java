package com.example.iml.orchestrator.integration.pipeline.bucket;

import com.example.iml.orchestrator.integration.fanout.BucketFanOutResult;
import com.example.iml.orchestrator.integration.fanout.BucketFanOutSink;
import com.example.iml.orchestrator.integration.pipeline.InspectionDecision;
import org.apache.logging.log4j.Logger;

import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.BiConsumer;

/**
 * Completes a bucket (pass/reject + seam gate) and hands result to sequence fan-out.
 */
final class BucketPublishHelper {

    private final Logger log;
    private final JointSeamPolicy jointSeamPolicy;
    private final ConcurrentHashMap<BucketKey, BucketState> buckets;
    private final BiConsumer<BucketFanOutResult, BucketFanOutSink> enqueueSyncedFanOut;

    BucketPublishHelper(
            Logger log,
            JointSeamPolicy jointSeamPolicy,
            ConcurrentHashMap<BucketKey, BucketState> buckets,
            BiConsumer<BucketFanOutResult, BucketFanOutSink> enqueueSyncedFanOut
    ) {
        this.log = log;
        this.jointSeamPolicy = jointSeamPolicy;
        this.buckets = buckets;
        this.enqueueSyncedFanOut = enqueueSyncedFanOut;
    }

    void publishBucket(BucketState state, BucketFanOutSink fanOut, boolean timedOut) {
        if (state.published) {
            return;
        }
        state.published = true;
        if (state.timeoutFuture != null) {
            state.timeoutFuture.cancel(false);
        }
        buckets.remove(state.key(), state);

        List<Integer> expectedCameraIds = state.group.cameraIds();
        boolean bucketPass = !computeAnyReject(state, expectedCameraIds, timedOut);
        Map<Integer, InspectionDecision> snapshot = Map.copyOf(state.frameDecisions);
        boolean seamStrict = false;
        if (bucketPass) {
            BucketSeamStrictGate seamGate = BucketSeamStrictGate.evaluate(snapshot, jointSeamPolicy);
            seamStrict = seamGate.strictActive();
            if (seamGate.forceReject()) {
                bucketPass = false;
            }
            if (log != null && (seamGate.jointDecision() != null || seamStrict)) {
                log.info(
                        "inspection bucket seam_gate seq={} group={} seam_strict={} sibling_vis={} "
                                + "joint_cam={} par={} width={} strict_pass={}",
                        state.triggerSequence,
                        state.groupId,
                        seamStrict,
                        seamGate.siblingVisibility(),
                        seamGate.jointDecision() == null ? null : seamGate.jointDecision().cameraId(),
                        seamGate.jointDecision() == null ? null : seamGate.jointDecision().jointParallelismDeg(),
                        seamGate.jointDecision() == null ? null : seamGate.jointDecision().jointWidthMm(),
                        !seamGate.forceReject()
                );
            }
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

        enqueueSyncedFanOut.accept(
                new BucketFanOutResult(
                        state.groupId,
                        state.triggerSequence,
                        bucketPass,
                        expectedCameraIds,
                        snapshot
                ),
                fanOut
        );
    }

    private static boolean computeAnyReject(
            BucketState state,
            List<Integer> expectedCameraIds,
            boolean timedOut
    ) {
        boolean anyReject = timedOut || state.frameDecisions.size() < expectedCameraIds.size();
        if (anyReject) {
            return true;
        }
        boolean captureOnly = state.frameDecisions.values().stream()
                .allMatch(decision -> decision != null && "CAPTURE".equals(decision.action()));
        if (captureOnly) {
            return false;
        }
        for (Integer cameraId : expectedCameraIds) {
            InspectionDecision frameDecision = state.frameDecisions.get(cameraId);
            if (frameDecision == null || !frameDecision.overallPass()) {
                return true;
            }
        }
        return false;
    }

    static List<Integer> rejectCameraIds(Map<Integer, InspectionDecision> decisions) {
        return decisions.entrySet().stream()
                .filter(e -> e.getValue() != null && !e.getValue().overallPass())
                .map(Map.Entry::getKey)
                .sorted()
                .toList();
    }
}

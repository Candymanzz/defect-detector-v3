package com.example.iml.orchestrator.integration.pipeline.bucket;

import com.example.iml.orchestrator.integration.fanout.BucketFanOutResult;
import com.example.iml.orchestrator.integration.fanout.BucketFanOutSink;
import org.apache.logging.log4j.Logger;

import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

/**
 * Synchronizes multi-bucket fan-out for the same trigger sequence.
 */
final class BucketSequenceFanOut {

    private final Logger log;
    private final List<BucketGroup> groups;
    private final long timeoutMs;
    private final ScheduledExecutorService timeoutExecutor;
    private final ConcurrentHashMap<Long, SequenceBarrier> sequenceBarriers = new ConcurrentHashMap<>();

    BucketSequenceFanOut(
            Logger log,
            List<BucketGroup> groups,
            long timeoutMs,
            ScheduledExecutorService timeoutExecutor
    ) {
        this.log = log;
        this.groups = groups;
        this.timeoutMs = timeoutMs;
        this.timeoutExecutor = timeoutExecutor;
    }

    /**
     * Одно ведро → сразу в fanOut. Два+ ведра → ждать все groupId одного seq, потом слать пакетом
     * (reject_line_1 и reject_line_2 синхронно).
     */
    void enqueueSyncedFanOut(BucketFanOutResult result, BucketFanOutSink fanOut) {
        if (fanOut == null) {
            return;
        }
        if (groups.size() <= 1) {
            fanOut.publishBucket(result);
            return;
        }
        SequenceBarrier barrier = sequenceBarriers.computeIfAbsent(
                result.triggerSequence(),
                SequenceBarrier::new
        );
        List<BucketFanOutResult> toPublish = null;
        synchronized (barrier) {
            if (barrier.flushed) {
                return;
            }
            barrier.readyByGroup.put(result.groupId(), result);
            scheduleSequenceSyncTimeout(barrier, fanOut);
            if (barrier.readyByGroup.size() >= groups.size()) {
                toPublish = takeBarrierResults(barrier);
            }
        }
        if (toPublish != null) {
            publishSyncedResults(toPublish, fanOut);
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
        }
        publishSyncedResults(toPublish, fanOut);
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

    private void publishSyncedResults(List<BucketFanOutResult> results, BucketFanOutSink fanOut) {
        if (fanOut == null || results == null || results.isEmpty()) {
            return;
        }
        log.info(
                "inspection sequence fanout seq={} groups={} passes={}",
                results.get(0).triggerSequence(),
                results.stream().map(BucketFanOutResult::groupId).toList(),
                results.stream().map(BucketFanOutResult::overallPass).toList()
        );
        for (BucketFanOutResult result : results) {
            fanOut.publishBucket(result);
        }
    }
}

package com.example.iml.orchestrator.integration.pipeline.bucket;

import com.example.iml.orchestrator.integration.fanout.BucketFanOutSink;
import org.apache.logging.log4j.Logger;

import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

/**
 * Per-bucket incomplete-frame timeout scheduling.
 */
final class BucketTimeoutScheduler {

    private final Logger log;
    private final long timeoutMs;
    private final ScheduledExecutorService timeoutExecutor;
    private final ConcurrentHashMap<BucketKey, BucketState> buckets;
    private final BucketPublishHelper publishHelper;

    BucketTimeoutScheduler(
            Logger log,
            long timeoutMs,
            ScheduledExecutorService timeoutExecutor,
            ConcurrentHashMap<BucketKey, BucketState> buckets,
            BucketPublishHelper publishHelper
    ) {
        this.log = log;
        this.timeoutMs = timeoutMs;
        this.timeoutExecutor = timeoutExecutor;
        this.buckets = buckets;
        this.publishHelper = publishHelper;
    }

    void scheduleTimeoutIfNeeded(BucketState state, BucketFanOutSink fanOut) {
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
            publishHelper.publishBucket(state, fanOut, true);
        }
    }
}

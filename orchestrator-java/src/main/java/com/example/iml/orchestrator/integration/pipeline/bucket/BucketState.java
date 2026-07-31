package com.example.iml.orchestrator.integration.pipeline.bucket;

import com.example.iml.orchestrator.integration.pipeline.InspectionDecision;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.ScheduledFuture;

final class BucketState {
    final long triggerSequence;
    final int groupId;
    final BucketGroup group;
    final Map<Integer, InspectionDecision> frameDecisions = new LinkedHashMap<>();
    volatile boolean published;
    volatile ScheduledFuture<?> timeoutFuture;

    BucketState(long triggerSequence, int groupId, BucketGroup group) {
        this.triggerSequence = triggerSequence;
        this.groupId = groupId;
        this.group = group;
    }

    BucketKey key() {
        return new BucketKey(triggerSequence, groupId);
    }
}

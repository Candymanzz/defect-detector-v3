package com.example.iml.orchestrator.integration.fanout;

import com.example.iml.orchestrator.integration.pipeline.InspectionDecision;

import java.util.List;
import java.util.Map;

/** Итог инспекции по одному независимому ведру. */
public record BucketFanOutResult(
        int groupId,
        long triggerSequence,
        boolean overallPass,
        List<Integer> bucketCameraIds,
        Map<Integer, InspectionDecision> frameDecisions,
        long parentCycleId,
        int phaseId,
        long rawTriggerSequence
) {
    public BucketFanOutResult(
            int groupId,
            long triggerSequence,
            boolean overallPass,
            List<Integer> bucketCameraIds,
            Map<Integer, InspectionDecision> frameDecisions
    ) {
        this(
                groupId,
                triggerSequence,
                overallPass,
                bucketCameraIds,
                frameDecisions,
                triggerSequence,
                0,
                triggerSequence
        );
    }

    public BucketFanOutResult {
        bucketCameraIds = bucketCameraIds.stream().sorted().toList();
        frameDecisions = Map.copyOf(frameDecisions);
    }
}

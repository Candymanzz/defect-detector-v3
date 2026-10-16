package com.example.iml.orchestrator.integration.fanout;

/**
 * Приёмник итога инспекции по ведру (линия / группа камер).
 */
public interface BucketFanOutSink {

    void publishBucket(BucketFanOutResult result);

    default void publishBuckets(java.util.List<BucketFanOutResult> results) {
        results.forEach(this::publishBucket);
    }

    default boolean publishEarlyPlasticHandleReject(long triggerSequence, int cameraId) {
        return false;
    }

    default boolean publishEarlyBucketReject(long triggerSequence, int groupId, int cameraId) {
        return publishEarlyPlasticHandleReject(triggerSequence, cameraId);
    }

    default void finishSequence(long triggerSequence) {
    }
}

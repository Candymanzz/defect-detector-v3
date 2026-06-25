package com.example.iml.orchestrator.integration.fanout;

/**
 * Приёмник итога инспекции по ведру (линия / группа камер).
 */
public interface BucketFanOutSink {

    void publishBucket(BucketFanOutResult result);
}

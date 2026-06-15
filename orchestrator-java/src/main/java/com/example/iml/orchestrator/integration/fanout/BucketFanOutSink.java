package com.example.iml.orchestrator.integration.fanout;

/**
 * Приёмник fan-out: per-frame события и итог по ведру.
 */
public interface BucketFanOutSink {

    void publishPerFrame(FanOutEvent event);

    void publishBucket(BucketFanOutResult result);
}

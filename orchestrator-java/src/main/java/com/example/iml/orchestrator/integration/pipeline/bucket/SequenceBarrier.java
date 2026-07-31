package com.example.iml.orchestrator.integration.pipeline.bucket;

import com.example.iml.orchestrator.integration.fanout.BucketFanOutResult;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.ScheduledFuture;

/** Ожидание всех вёдер одного triggerSequence перед отправкой на ПЛК/UI. */
final class SequenceBarrier {
    final long triggerSequence;
    final Map<Integer, BucketFanOutResult> readyByGroup = new LinkedHashMap<>();
    volatile boolean flushed;
    volatile ScheduledFuture<?> syncTimeoutFuture;

    SequenceBarrier(long triggerSequence) {
        this.triggerSequence = triggerSequence;
    }
}

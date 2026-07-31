package com.example.iml.orchestrator.integration.plc;

import com.example.iml.orchestrator.integration.fanout.BucketFanOutResult;
import org.apache.logging.log4j.Logger;

import java.io.IOException;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

/**
 * Bucket publish / level / pulse write helpers for {@link PlcFinsPublisher}.
 */
final class PlcFinsPublishOps {

    private final Logger log;
    private final PlcFinsConfig config;
    private final PlcRegisterMap registerMap;
    private final ConcurrentHashMap<String, Boolean> lastSignalValues;
    private final PlcFinsQueueSupport queue;

    PlcFinsPublishOps(
            Logger log,
            PlcFinsConfig config,
            PlcRegisterMap registerMap,
            ConcurrentHashMap<String, Boolean> lastSignalValues,
            PlcFinsQueueSupport queue
    ) {
        this.log = log;
        this.config = config;
        this.registerMap = registerMap;
        this.lastSignalValues = lastSignalValues;
        this.queue = queue;
    }

    void publishBucket(BucketFanOutResult result) {
        // ready держится отдельно (sticky HIGH); здесь только вердикт reject.
        Optional<PlcSignalDefinition> signalOpt = registerMap.rejectSignalForGroup(result.groupId());
        if (signalOpt.isEmpty()) {
            log.warn("plc fins: no reject signal for bucket group={}", result.groupId());
            return;
        }
        if (result.overallPass()) {
            queue.enqueue(new PlcFinsJobs.WriteBitJob(signalOpt.get(), false));
            return;
        }
        if (config.pulseMs() > 0) {
            queue.enqueue(new PlcFinsJobs.PulseBitJob(
                    signalOpt.get(),
                    true,
                    System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(config.pulseMs())
            ));
        } else {
            queue.enqueue(new PlcFinsJobs.WriteBitJob(signalOpt.get(), true));
        }
    }

    void setVisionReady(boolean ready) {
        writeLevelIfChanged(config.visionReadySignal(), ready);
    }

    void setVisionFault(boolean fault) {
        writeLevelIfChanged(config.visionFaultSignal(), fault);
    }

    void flushVisionLevels(boolean ready, boolean fault)
            throws IOException, InterruptedException, TimeoutException {
        writeSignal(config.visionReadySignal(), ready, false);
        writeSignal(config.visionFaultSignal(), fault, false);
    }

    void writeLevelIfChanged(String signalName, boolean value) {
        PlcSignalDefinition signal = registerMap.require(signalName);
        Boolean last = lastSignalValues.get(signalName);
        if (last != null && last == value) {
            return;
        }
        queue.enqueue(new PlcFinsJobs.WriteBitJob(signal, value));
    }

    void writeSignal(String name, boolean value, boolean pulse)
            throws IOException, InterruptedException, TimeoutException {
        PlcSignalDefinition signal = registerMap.require(name);
        CompletableFuture<Void> future = new CompletableFuture<>();
        boolean enqueued;
        if (pulse && value && config.pulseMs() > 0) {
            enqueued = queue.enqueue(new PlcFinsJobs.PulseBitJob(
                    signal,
                    true,
                    System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(config.pulseMs()),
                    future
            ));
        } else {
            enqueued = queue.enqueue(new PlcFinsJobs.WriteBitJob(signal, value, future));
        }
        if (!enqueued) {
            throw new IOException("plc fins queue full");
        }
        queue.await(future);
    }

    int[] readWords(PlcMemoryArea area, int startWord, int count, String signal)
            throws IOException, InterruptedException, TimeoutException {
        CompletableFuture<int[]> future = new CompletableFuture<>();
        if (!queue.enqueue(new PlcFinsJobs.ReadWordsJob(area, startWord, count, signal, future))) {
            throw new IOException("plc fins queue full");
        }
        return queue.await(future);
    }

    void writeWords(PlcMemoryArea area, int startWord, int[] words, String signal)
            throws IOException, InterruptedException, TimeoutException {
        CompletableFuture<Void> future = new CompletableFuture<>();
        if (!queue.enqueue(new PlcFinsJobs.WriteWordsJob(area, startWord, words, signal, future))) {
            throw new IOException("plc fins queue full");
        }
        queue.await(future);
    }
}

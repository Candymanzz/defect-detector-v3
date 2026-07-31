package com.example.iml.orchestrator.integration.plc;

import org.apache.logging.log4j.Logger;

import java.io.IOException;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Enqueue / await helpers for the FINS publisher worker queue.
 */
final class PlcFinsQueueSupport {

    private final Logger log;
    private final BlockingQueue<PlcFinsJobs.PlcJob> queue;
    private final AtomicLong droppedJobs;
    private final long awaitTimeoutMs;

    PlcFinsQueueSupport(
            Logger log,
            BlockingQueue<PlcFinsJobs.PlcJob> queue,
            AtomicLong droppedJobs,
            long responseTimeoutMs
    ) {
        this.log = log;
        this.queue = queue;
        this.droppedJobs = droppedJobs;
        this.awaitTimeoutMs = Math.max(1000L, responseTimeoutMs * 3L);
    }

    boolean enqueue(PlcFinsJobs.PlcJob job) {
        if (!queue.offer(job)) {
            droppedJobs.incrementAndGet();
            log.warn("plc fins queue full, job dropped total={}", droppedJobs.get());
            if (job instanceof PlcFinsJobs.ReadWordsJob read) {
                read.future().completeExceptionally(new IOException("plc fins queue full"));
            } else if (job instanceof PlcFinsJobs.WriteWordsJob write) {
                write.future().completeExceptionally(new IOException("plc fins queue full"));
            } else if (job instanceof PlcFinsJobs.WriteBitJob writeBit && writeBit.future() != null) {
                writeBit.future().completeExceptionally(new IOException("plc fins queue full"));
            } else if (job instanceof PlcFinsJobs.PulseBitJob pulse && pulse.future() != null) {
                pulse.future().completeExceptionally(new IOException("plc fins queue full"));
            }
            return false;
        }
        return true;
    }

    <T> T await(CompletableFuture<T> future) throws IOException, InterruptedException, TimeoutException {
        try {
            return future.get(awaitTimeoutMs, TimeUnit.MILLISECONDS);
        } catch (java.util.concurrent.ExecutionException e) {
            Throwable cause = e.getCause() == null ? e : e.getCause();
            if (cause instanceof IOException io) {
                throw io;
            }
            if (cause instanceof RuntimeException runtime) {
                throw runtime;
            }
            throw new IOException(cause.getMessage(), cause);
        }
    }

    long droppedTotal() {
        return droppedJobs.get();
    }
}

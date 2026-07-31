package com.example.iml.orchestrator.integration.plc;

import java.util.concurrent.CompletableFuture;

/**
 * Internal queue job types for {@link PlcFinsPublisher}.
 */
final class PlcFinsJobs {

    private PlcFinsJobs() {
    }

    sealed interface PlcJob permits WriteBitJob, PulseBitJob, ReadWordsJob, WriteWordsJob {
    }

    record WriteBitJob(
            PlcSignalDefinition signal,
            boolean value,
            CompletableFuture<Void> future
    ) implements PlcJob {
        WriteBitJob(PlcSignalDefinition signal, boolean value) {
            this(signal, value, null);
        }
    }

    record PulseBitJob(
            PlcSignalDefinition signal,
            boolean activeValue,
            long resetAtNanos,
            CompletableFuture<Void> future
    ) implements PlcJob {
        PulseBitJob(PlcSignalDefinition signal, boolean activeValue, long resetAtNanos) {
            this(signal, activeValue, resetAtNanos, null);
        }
    }

    record ReadWordsJob(
            PlcMemoryArea area,
            int startWord,
            int count,
            String signal,
            CompletableFuture<int[]> future
    ) implements PlcJob {
    }

    record WriteWordsJob(
            PlcMemoryArea area,
            int startWord,
            int[] words,
            String signal,
            CompletableFuture<Void> future
    ) implements PlcJob {
    }
}

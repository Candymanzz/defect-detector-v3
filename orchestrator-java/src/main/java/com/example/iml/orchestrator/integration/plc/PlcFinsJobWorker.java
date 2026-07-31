package com.example.iml.orchestrator.integration.plc;

import com.example.iml.orchestrator.integration.plc.fins.OmronFinsClient;
import org.apache.logging.log4j.Logger;

import java.io.IOException;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Background loop that executes queued FINS write/pulse/read/write-words jobs.
 */
final class PlcFinsJobWorker implements Runnable {

    private final Logger log;
    private final OmronFinsClient client;
    private final BlockingQueue<PlcFinsJobs.PlcJob> queue;
    private final AtomicBoolean running;
    private final ConcurrentHashMap<String, Boolean> lastSignalValues;

    PlcFinsJobWorker(
            Logger log,
            OmronFinsClient client,
            BlockingQueue<PlcFinsJobs.PlcJob> queue,
            AtomicBoolean running,
            ConcurrentHashMap<String, Boolean> lastSignalValues
    ) {
        this.log = log;
        this.client = client;
        this.queue = queue;
        this.running = running;
        this.lastSignalValues = lastSignalValues;
    }

    @Override
    public void run() {
        while (running.get() && !Thread.currentThread().isInterrupted()) {
            try {
                PlcFinsJobs.PlcJob job = queue.poll(200, TimeUnit.MILLISECONDS);
                if (job == null) {
                    continue;
                }
                processJob(job);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            } catch (Exception e) {
                log.warn("plc fins worker error: {}", e.getMessage());
            }
        }
    }

    void processJob(PlcFinsJobs.PlcJob job) throws IOException {
        if (job instanceof PlcFinsJobs.WriteBitJob write) {
            processWriteBit(write);
            return;
        }
        if (job instanceof PlcFinsJobs.PulseBitJob pulse) {
            processPulse(pulse);
            return;
        }
        if (job instanceof PlcFinsJobs.ReadWordsJob read) {
            try {
                int[] words = client.timings().readWords(read.area(), read.startWord(), read.count(), read.signal());
                read.future().complete(words);
            } catch (Exception e) {
                read.future().completeExceptionally(e);
            }
            return;
        }
        PlcFinsJobs.WriteWordsJob writeWords = (PlcFinsJobs.WriteWordsJob) job;
        try {
            client.timings().writeWords(
                    writeWords.area(), writeWords.startWord(), writeWords.words(), writeWords.signal());
            writeWords.future().complete(null);
        } catch (Exception e) {
            writeWords.future().completeExceptionally(e);
        }
    }

    private void processWriteBit(PlcFinsJobs.WriteBitJob write) throws IOException {
        try {
            writeBit(write.signal(), write.value());
            if (write.future() != null) {
                write.future().complete(null);
            }
        } catch (Exception e) {
            if (write.future() != null) {
                write.future().completeExceptionally(e);
            } else {
                throw e instanceof IOException io ? io : new IOException(e);
            }
        }
    }

    private void processPulse(PlcFinsJobs.PulseBitJob pulse) throws IOException {
        try {
            writeBit(pulse.signal(), pulse.activeValue());
            long waitMs = TimeUnit.NANOSECONDS.toMillis(pulse.resetAtNanos() - System.nanoTime());
            if (waitMs > 0) {
                try {
                    Thread.sleep(waitMs);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    if (pulse.future() != null) {
                        pulse.future().completeExceptionally(e);
                    }
                    return;
                }
            }
            writeBit(pulse.signal(), false);
            if (pulse.future() != null) {
                pulse.future().complete(null);
            }
        } catch (Exception e) {
            if (pulse.future() != null) {
                pulse.future().completeExceptionally(e);
            } else if (e instanceof IOException io) {
                throw io;
            } else {
                throw new IOException(e);
            }
        }
    }

    void writeBit(PlcSignalDefinition signal, boolean value) throws IOException {
        client.signals().writeBit(signal.area(), signal.address(), value, signal.name());
        lastSignalValues.put(signal.name(), value);
        log.info(
                "plc fins write signal={} area={} address={}.{} value={}",
                signal.name(),
                signal.area(),
                signal.address().word(),
                signal.address().bit(),
                value
        );
    }
}

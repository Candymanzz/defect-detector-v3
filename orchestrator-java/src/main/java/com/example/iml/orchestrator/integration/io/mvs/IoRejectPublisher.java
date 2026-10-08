package com.example.iml.orchestrator.integration.io.mvs;

import org.apache.logging.log4j.LogManager;
import java.util.*;
import java.util.concurrent.*;

/** Separate FIFO per reject output: two lines can pulse independently. */
public final class IoRejectPublisher implements AutoCloseable {
    @FunctionalInterface public interface Writer { void write(int port, boolean high); }
    @FunctionalInterface public interface TimerWriter { void trigger(String timer, String line); }
    private volatile TimerWriter timerWriter;
    private final IoRejectConfig config;
    private volatile Writer writer;
    private volatile boolean closed;
    private final Map<Integer, ExecutorService> workers = new TreeMap<>();
    private final Map<Integer, Long> earlyTimerSequences = new ConcurrentHashMap<>();
    private final Map<Integer, Boolean> active = new ConcurrentHashMap<>();
    private final org.apache.logging.log4j.Logger log = LogManager.getLogger(IoRejectPublisher.class);
    public IoRejectPublisher(IoRejectConfig config) {
        this.config = config;
        config.groups().forEach((group, output) -> workers.put(group, new ThreadPoolExecutor(1, 1, 0, TimeUnit.MILLISECONDS,
                new ArrayBlockingQueue<>(config.queueSize()), task -> {
                    Thread thread = new Thread(task, "io-reject-DO" + output.port()); thread.setDaemon(true); return thread;
                }, new ThreadPoolExecutor.AbortPolicy())));
    }
    public void bind(Writer writer) { this.writer = Objects.requireNonNull(writer); }
    public void bindTimer(TimerWriter writer) { this.timerWriter = Objects.requireNonNull(writer); }
    public void publish(int group, long sequence, boolean pass, boolean hold) {
        IoRejectConfig.Output output = config.groups().get(group);
        if (output == null) throw new IllegalArgumentException("No DO reject mapping for group " + group);
        if (closed || (config.mode().equals("timer") ? timerWriter == null : writer == null)) throw new IllegalStateException("DO reject output is not ready");
        CompletableFuture<Void> edge = new CompletableFuture<>();
        try {
            workers.get(group).execute(() -> {
                if (config.mode().equals("timer")) {
                    try {
                        if (!pass && !(hold && Long.valueOf(sequence).equals(earlyTimerSequences.get(group)))) {
                            timerWriter.trigger(output.timer(), output.line());
                            if (hold) earlyTimerSequences.put(group, sequence);
                            log.info("io event=reject_timer group={} sequence={} timer={} line={} early={}", group, sequence, output.timer(), output.line(), hold);
                        }
                        edge.complete(null);
                    } catch (RuntimeException e) {
                        edge.completeExceptionally(e);
                        log.error("io event=reject_timer_failed group={} sequence={} timer={}", group, sequence, output.timer(), e);
                    }
                    return;
                }
                boolean pulse = !pass && !hold && config.pulseMs() > 0;
                try {
                    if (!Boolean.valueOf(!pass).equals(active.get(group)) || pulse) set(group, output, !pass, sequence);
                    edge.complete(null);
                    if (pulse) Thread.sleep(config.pulseMs());
                } catch (Exception e) {
                    edge.completeExceptionally(e);
                    log.error("io event=reject_failed group={} sequence={} do={}", group, sequence, output.port(), e);
                    if (e instanceof InterruptedException) Thread.currentThread().interrupt();
                } finally {
                    if (pulse) try { set(group, output, false, sequence); }
                    catch (RuntimeException e) { log.error("io event=reject_idle_failed group={} sequence={} do={}", group, sequence, output.port(), e); }
                }
            });
            edge.get(config.ackTimeoutMs(), TimeUnit.MILLISECONDS);
        } catch (Exception e) {
            if (e instanceof InterruptedException) Thread.currentThread().interrupt();
            throw new IllegalStateException("DO reject delivery failed group=" + group + " seq=" + sequence, e);
        }
    }
    private void set(int group, IoRejectConfig.Output output, boolean value, long sequence) {
        writer.write(output.port(), value == output.activeHigh());
        active.put(group, value);
        log.info("io event=reject_output group={} sequence={} do={} active={} physical_high={}",
                group, sequence, output.port(), value, value == output.activeHigh());
    }
    public void rejectAll(long sequence) { config.groups().keySet().forEach(group -> publish(group, sequence, false, true)); }
    @Override public synchronized void close() {
        if (closed) return;
        closed = true; workers.values().forEach(ExecutorService::shutdownNow);
        for (ExecutorService worker : workers.values()) {
            try { if (!worker.awaitTermination(config.shutdownTimeoutMs(), TimeUnit.MILLISECONDS)) log.error("io event=reject_worker_stop_timeout"); }
            catch (InterruptedException e) { Thread.currentThread().interrupt(); }
        }
        if (!config.mode().equals("timer") && writer != null) config.groups().forEach((group, output) -> {
            try { set(group, output, false, -1); }
            catch (RuntimeException e) { log.error("io event=reject_shutdown_idle_failed do={}", output.port(), e); }
        });
    }
}

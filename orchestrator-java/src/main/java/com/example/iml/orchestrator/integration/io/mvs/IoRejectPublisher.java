package com.example.iml.orchestrator.integration.io.mvs;

import org.apache.logging.log4j.LogManager;
import java.util.*;
import java.util.concurrent.*;

/** Hold results share one FIFO/batch; pulse/timer outputs retain independent FIFOs. */
public final class IoRejectPublisher implements AutoCloseable {
    @FunctionalInterface public interface Writer {
        void write(int port, boolean high);
        default void writeAll(Map<Integer, Boolean> levels) { levels.forEach(this::write); }
    }
    @FunctionalInterface public interface TimerWriter { void trigger(String timer, String line); }
    private volatile TimerWriter timerWriter;
    private final IoRejectConfig config;
    private volatile Writer writer;
    private volatile boolean closed;
    private final java.util.concurrent.atomic.AtomicLong generation = new java.util.concurrent.atomic.AtomicLong();
    private final Map<Integer, ExecutorService> workers = new TreeMap<>();
    private final ExecutorService holdWorker;
    private final Map<Integer, Long> earlyTimerSequences = new ConcurrentHashMap<>();
    private final Map<Integer, Boolean> active = new ConcurrentHashMap<>();
    private final Map<Integer, Long> heldSequences = new ConcurrentHashMap<>();
    private final org.apache.logging.log4j.Logger log = LogManager.getLogger(IoRejectPublisher.class);
    public IoRejectPublisher(IoRejectConfig config) {
        this.config = config;
        holdWorker = isHoldMode() ? worker("io-reject-hold") : null;
        config.groups().forEach((group, output) -> workers.put(group,
                holdWorker == null ? worker("io-reject-DO" + output.port()) : holdWorker));
    }
    private ExecutorService worker(String name) {
        return new ThreadPoolExecutor(1, 1, 0, TimeUnit.MILLISECONDS,
                new ArrayBlockingQueue<>(config.queueSize()), task -> {
                    Thread thread = new Thread(task, name); thread.setDaemon(true); return thread;
                }, new ThreadPoolExecutor.AbortPolicy());
    }
    public boolean isHoldMode() { return config.mode().equals("hold"); }
    public void bind(Writer writer) { this.writer = Objects.requireNonNull(writer); }
    public void bindTimer(TimerWriter writer) { this.timerWriter = Objects.requireNonNull(writer); }
    public void publish(int group, long sequence, boolean pass, boolean hold) {
        if (isHoldMode()) { publishBatch(sequence, Map.of(group, pass)); return; }
        IoRejectConfig.Output output = config.groups().get(group);
        if (output == null) throw new IllegalArgumentException("No DO reject mapping for group " + group);
        if (closed || (config.mode().equals("timer") ? timerWriter == null : writer == null)) throw new IllegalStateException("DO reject output is not ready");
        CompletableFuture<Void> edge = new CompletableFuture<>();
        long submittedGeneration = generation.get();
        try {
            workers.get(group).execute(() -> {
                if (submittedGeneration != generation.get()) { edge.complete(null); return; }
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
                boolean pulse = config.mode().equals("level") && !pass && !hold && config.pulseMs() > 0;
                try {
                    if (config.mode().equals("hold") && sequence < heldSequences.getOrDefault(group, Long.MIN_VALUE)) {
                        edge.complete(null); return;
                    }
                    if (config.mode().equals("hold") || !Boolean.valueOf(!pass).equals(active.get(group)) || pulse) set(group, output, !pass, sequence);
                    if (config.mode().equals("hold")) heldSequences.put(group, sequence);
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
    /** One FIFO entry and one SDK critical section for a sequence's independent hold levels. */
    public void publishBatch(long sequence, Map<Integer, Boolean> passes) {
        if (!isHoldMode()) throw new IllegalStateException("Batch delivery requires hold outputs");
        Map<Integer, Boolean> ordered = new TreeMap<>(passes);
        for (int group : ordered.keySet())
            if (!config.groups().containsKey(group)) throw new IllegalArgumentException("No DO reject mapping for group " + group);
        Writer target = writer;
        if (closed || target == null) throw new IllegalStateException("DO reject output is not ready");
        CompletableFuture<Void> done = new CompletableFuture<>();
        long submittedGeneration = generation.get(), queued = System.nanoTime();
        try {
            holdWorker.execute(() -> {
                if (submittedGeneration != generation.get()) { done.complete(null); return; }
                try {
                    Map<Integer, Boolean> levels = new LinkedHashMap<>();
                    Map<Integer, Boolean> delivered = new LinkedHashMap<>();
                    for (var entry : ordered.entrySet()) {
                        int group = entry.getKey();
                        if (sequence < heldSequences.getOrDefault(group, Long.MIN_VALUE)) continue;
                        delivered.put(group, entry.getValue());
                        // Fence old results before SDK access: a batch can fail after its
                        // first physical write. The same sequence may retry; older ones may not undo it.
                        heldSequences.put(group, sequence);
                        var output = config.groups().get(group);
                        levels.put(output.port(), (!entry.getValue()) == output.activeHigh());
                    }
                    long started = System.nanoTime();
                    if (!levels.isEmpty()) target.writeAll(Collections.unmodifiableMap(levels));
                    double writeMs = (System.nanoTime() - started) / 1_000_000.0;
                    for (var entry : delivered.entrySet()) {
                        int group = entry.getKey(); var output = config.groups().get(group);
                        boolean reject = !entry.getValue();
                        active.put(group, reject);
                        log.info("io event=reject_output group={} sequence={} do={} active={} physical_high={} write_ms={} batch=true queue_ms={}",
                                group, sequence, output.port(), reject, levels.get(output.port()), writeMs,
                                (started - queued) / 1_000_000.0);
                    }
                    done.complete(null);
                } catch (RuntimeException e) {
                    done.completeExceptionally(e);
                    log.error("io event=reject_batch_failed sequence={} groups={}", sequence, ordered.keySet(), e);
                }
            });
            done.get(config.ackTimeoutMs(), TimeUnit.MILLISECONDS);
        } catch (Exception e) {
            if (e instanceof InterruptedException) Thread.currentThread().interrupt();
            throw new IllegalStateException("DO batch delivery failed seq=" + sequence, e);
        }
    }
    private void set(int group, IoRejectConfig.Output output, boolean value, long sequence) {
        long writeStarted = System.nanoTime();
        writer.write(output.port(), value == output.activeHigh());
        double writeMs = (System.nanoTime() - writeStarted) / 1_000_000.0;
        active.put(group, value);
        log.info("io event=reject_output group={} sequence={} do={} active={} physical_high={} write_ms={}",
                group, sequence, output.port(), value, value == output.activeHigh(), writeMs);
    }
    public void rejectAll(long sequence) { config.groups().keySet().forEach(group -> publish(group, sequence, false, true)); }
    /** Session reset releases held rejects, even when no subsequent inspection arrives. */
    public void resetIdle() {
        generation.incrementAndGet();
        if (closed || config.mode().equals("timer") || writer == null) return;
        if (isHoldMode()) {
            CompletableFuture<Void> done = new CompletableFuture<>();
            try {
                holdWorker.execute(() -> {
                    try {
                        writer.writeAll(idleLevels());
                        heldSequences.clear(); earlyTimerSequences.clear(); active.clear(); done.complete(null);
                    } catch (RuntimeException e) { done.completeExceptionally(e); }
                });
                done.get(config.ackTimeoutMs(), TimeUnit.MILLISECONDS);
            } catch (Exception e) {
                if (e instanceof InterruptedException) Thread.currentThread().interrupt();
                log.error("io event=reject_reset_idle_failed batch=true", e);
            }
            return;
        }
        config.groups().forEach((group, output) -> {
            CompletableFuture<Void> reset = new CompletableFuture<>();
            try {
                workers.get(group).execute(() -> {
                    heldSequences.remove(group);
                    earlyTimerSequences.remove(group);
                    try { set(group, output, false, -1); reset.complete(null); }
                    catch (RuntimeException e) { reset.completeExceptionally(e); }
                });
                reset.get(config.ackTimeoutMs(), TimeUnit.MILLISECONDS);
            } catch (Exception e) {
                if (e instanceof InterruptedException) Thread.currentThread().interrupt();
                log.error("io event=reject_reset_idle_failed group={} do={}", group, output.port(), e);
            }
        });
    }
    private Map<Integer, Boolean> idleLevels() {
        Map<Integer, Boolean> levels = new TreeMap<>();
        config.groups().values().forEach(output -> levels.put(output.port(), !output.activeHigh()));
        return levels;
    }
    @Override public synchronized void close() {
        if (closed) return;
        closed = true; Set<ExecutorService> uniqueWorkers = new HashSet<>(workers.values());
        uniqueWorkers.forEach(ExecutorService::shutdownNow);
        for (ExecutorService worker : uniqueWorkers) {
            try { if (!worker.awaitTermination(config.shutdownTimeoutMs(), TimeUnit.MILLISECONDS)) log.error("io event=reject_worker_stop_timeout"); }
            catch (InterruptedException e) { Thread.currentThread().interrupt(); }
        }
        if (isHoldMode() && writer != null) {
            try { writer.writeAll(idleLevels()); }
            catch (RuntimeException e) { log.error("io event=reject_shutdown_idle_failed batch=true", e); }
        } else if (!config.mode().equals("timer") && writer != null) config.groups().forEach((group, output) -> {
            try { set(group, output, false, -1); }
            catch (RuntimeException e) { log.error("io event=reject_shutdown_idle_failed do={}", output.port(), e); }
        });
    }
}

package com.example.iml.orchestrator.integration.io.mvs;

import com.example.iml.orchestrator.integration.trigger.parse.IoInputDiChange;
import com.example.iml.orchestrator.integration.trigger.transport.TriggerTransport;
import org.apache.logging.log4j.Logger;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.*;

/** Independent DI acquisition and FIFO processing; no debounce, refractory or SkipBusy. */
public final class MvsIoTransport implements TriggerTransport {
    private record Event(long id, long generation, IoInputDiChange change, long queuedNs, String source) { }
    private final MvsIoConfig config;
    private final int triggerPort;
    private final Logger log;
    private final Supplier<MvsIoDevice> factory;
    private final Consumer<IoInputDiChange> receiver;
    private final BlockingQueue<Event> events = new LinkedBlockingQueue<>();
    private final AtomicLong eventIds = new AtomicLong(), processed = new AtomicLong(), discarded = new AtomicLong(), failures = new AtomicLong();
    private final AtomicBoolean running = new AtomicBoolean();
    private volatile Map<Integer, Boolean> outputStates = Map.of();
    private volatile long generation;
    private volatile boolean connected;
    private volatile MvsIoDevice device;
    private Thread acquisition, processing;

    public MvsIoTransport(MvsIoConfig config, int triggerPort, Logger log,
                          Supplier<MvsIoDevice> factory, Consumer<IoInputDiChange> receiver) {
        this.config = config; this.triggerPort = triggerPort; this.log = log;
        this.factory = factory; this.receiver = receiver;
    }
    @Override public void start() {
        if (!running.compareAndSet(false, true)) return;
        log.info("io event=start backend={} mode={} library={} port={} inputs={} outputs={} trigger_di={} configure_inputs={} health_ms={} output_poll_ms={} reconnect_ms={} shutdown_ms={}",
                config.backend(), config.inputMode(), config.library(), config.port(), config.inputs(), config.outputs(), triggerPort,
                config.configureInputs(), config.healthCheckMs(), config.outputPollMs(), config.reconnectMs(), config.shutdownTimeoutMs());
        processing = new Thread(this::process, "mvs-io-events");
        acquisition = new Thread(this::acquire, "mvs-io-acquisition");
        processing.setDaemon(true); acquisition.setDaemon(true);
        processing.start(); acquisition.start();
    }
    private void acquire() {
        while (running.get()) {
            long epoch = ++generation;
            log.info("io event=connect_attempt session={} backend={} port={}", epoch, config.backend(), config.port());
            try (MvsIoDevice current = factory.get()) {
                current.open(); device = current;
                Map<Integer, Boolean> previous = current.readInputs();
                outputStates = Map.copyOf(current.readOutputs());
                log.info("io event=connected session={} initial_di={} initial_do={}", epoch, previous, outputStates);
                connected = true;
                enqueue(epoch, null, "session_reset");
                // Seed work/direction/shutdown, but never synthesize DI3↑ on startup/reconnect.
                for (var entry : new TreeMap<>(previous).entrySet()) {
                    if (entry.getKey() != triggerPort) enqueue(epoch, new IoInputDiChange(entry.getKey(), entry.getValue()), "initial_state");
                }
                log.info("io event=startup_trigger_suppressed session={} di={} observed_value={} seeded_value=0", epoch, triggerPort, previous.get(triggerPort));
                enqueue(epoch, new IoInputDiChange(triggerPort, false), "initial_state");
                if (config.inputMode().equals("events")) {
                    current.subscribe(change -> enqueue(epoch, change));
                    log.info("io event=subscribed session={} inputs={}", epoch, config.inputs());
                }
                long scanCount = 0, scanTotalNs = 0, scanMaxNs = 0;
                long nextOutput = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(config.outputPollMs());
                long nextHealth = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(config.healthCheckMs());
                while (running.get() && connected) {
                    long scanStarted = System.nanoTime();
                    Map<Integer, Boolean> states = config.inputMode().equals("poll") ? current.readInputs() : Map.of();
                    long now = System.nanoTime();
                    if (!config.outputs().isEmpty() && now >= nextOutput) {
                        Map<Integer, Boolean> outputs = Map.copyOf(current.readOutputs());
                        if (!outputs.equals(outputStates)) {
                            log.info("io event=do_change session={} previous={} current={}", epoch, outputStates, outputs);
                            outputStates = outputs;
                        }
                        nextOutput = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(config.outputPollMs());
                    }
                    if (config.inputMode().equals("events") && now >= nextHealth) {
                        current.checkConnection();
                        log.debug("io event=health_ok session={} pending_events={}", epoch, events.size());
                        nextHealth = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(config.healthCheckMs());
                    }
                    long elapsed = System.nanoTime() - scanStarted;
                    scanTotalNs += elapsed; scanMaxNs = Math.max(scanMaxNs, elapsed);
                    if (++scanCount % config.scanLogEvery() == 0 && config.inputMode().equals("poll")) {
                        log.info("MVS DI/DO scan avg_ms={} max_ms={} sleep_ms={} pending_events={}",
                                scanTotalNs / 1_000_000.0 / scanCount, scanMaxNs / 1_000_000.0, config.pollMs(), events.size());
                        scanCount = scanTotalNs = scanMaxNs = 0;
                    }
                    if (config.inputMode().equals("poll")) {
                        for (var entry : new TreeMap<>(states).entrySet()) {
                            if (!Objects.equals(previous.get(entry.getKey()), entry.getValue()))
                                enqueue(epoch, new IoInputDiChange(entry.getKey(), entry.getValue()));
                        }
                        previous = states;
                    }
                    long sleepMs = config.pollMs();
                    if (config.inputMode().equals("events")) {
                        long deadline = config.outputs().isEmpty() ? nextHealth : Math.min(nextHealth, nextOutput);
                        sleepMs = Math.max(1, TimeUnit.NANOSECONDS.toMillis(deadline - System.nanoTime()));
                    }
                    Thread.sleep(sleepMs);
                }
            } catch (InterruptedException e) { Thread.currentThread().interrupt(); break; }
            catch (RuntimeException | LinkageError e) {
                log.error("io event=connection_failed session={} port={} reconnect_ms={}", epoch, config.port(), config.reconnectMs(), e);
            } finally {
                connected = false; device = null; outputStates = Map.of();
                log.info("io event=disconnected session={} stopping={}", epoch, !running.get());
            }
            if (running.get()) {
                try { Thread.sleep(config.reconnectMs()); }
                catch (InterruptedException e) { Thread.currentThread().interrupt(); break; }
            }
        }
    }
    private void enqueue(long epoch, IoInputDiChange change) {
        enqueue(epoch, change, config.inputMode());
    }
    private void enqueue(long epoch, IoInputDiChange change, String source) {
        if (!running.get()) {
            log.debug("io event=enqueue_ignored reason=stopping session={} change={}", epoch, change);
            return;
        }
        events.add(new Event(eventIds.incrementAndGet(), epoch, change, System.nanoTime(), source));
    }
    private void process() {
        while (running.get()) {
            try {
                Event event = events.take();
                if (event.generation() != generation || !connected) {
                    discarded.incrementAndGet();
                    log.warn("io event=discarded event_id={} session={} current_session={} connected={} source={} change={} reason=disconnected_session",
                            event.id(), event.generation(), generation, connected, event.source(), event.change());
                    continue;
                }
                long started = System.nanoTime();
                log.info("io event=dispatch event_id={} session={} source={} change={} queue_wait_ms={} pending_events={}",
                        event.id(), event.generation(), event.source(), event.change(),
                        (started - event.queuedNs()) / 1_000_000.0, events.size());
                boolean ok = false;
                try {
                    receiver.accept(event.change());
                    processed.incrementAndGet(); ok = true;
                } catch (RuntimeException e) {
                    failures.incrementAndGet();
                    log.error("io event=handler_failed event_id={} session={} change={}", event.id(), event.generation(), event.change(), e);
                } finally {
                    log.info("io event=dispatch_complete event_id={} session={} success={} handler_ms={}",
                            event.id(), event.generation(), ok, (System.nanoTime() - started) / 1_000_000.0);
                }
            } catch (InterruptedException e) { Thread.currentThread().interrupt(); break; }
        }
    }
    public void triggerTimer(String timer, String line) {
        MvsIoDevice current = device;
        if (!connected || current == null) throw new IllegalStateException("IO disconnected; timer cannot be triggered");
        current.triggerTimer(timer, line);
    }
    public void setOutput(int port, boolean high) {
        MvsIoDevice current = device;
        if (!connected || current == null) throw new IllegalStateException("IO is disconnected; reject output cannot be delivered");
        current.setOutput(port, high);
    }
    /** Latest observed DO levels; empty when disconnected. */
    public Map<Integer, Boolean> outputStates() { return connected ? outputStates : Map.of(); }
    public boolean isConnected() { return connected; }
    @Override public void close() {
        log.info("io event=stop_requested session={} connected={} pending_events={}", generation, connected, events.size());
        running.set(false); connected = false;
        if (acquisition != null) acquisition.interrupt();
        if (processing != null) processing.interrupt();
        join(processing); join(acquisition);
        int pending = events.size(); events.clear();
        log.info("io event=stopped received={} processed={} discarded={} handler_failures={} cleared_pending={}",
                eventIds.get(), processed.get(), discarded.get(), failures.get(), pending);
    }
    private void join(Thread thread) {
        if (thread == null || thread == Thread.currentThread()) return;
        try { thread.join(config.shutdownTimeoutMs()); }
        catch (InterruptedException e) { Thread.currentThread().interrupt(); }
        if (thread.isAlive()) log.warn("MVS IO thread still stopping: {}", thread.getName());
    }
}

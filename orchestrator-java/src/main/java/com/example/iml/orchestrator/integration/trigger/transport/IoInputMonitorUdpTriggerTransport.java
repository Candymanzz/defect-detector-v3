package com.example.iml.orchestrator.integration.trigger.transport;

import com.example.iml.orchestrator.integration.trigger.InspectionTriggerBus;
import com.example.iml.orchestrator.integration.trigger.InspectionTriggerEvent;
import com.example.iml.orchestrator.integration.trigger.config.IoInputDiscreteConfig;
import com.example.iml.orchestrator.integration.trigger.config.UdpTriggerConfig;
import com.example.iml.orchestrator.integration.trigger.gpio.LineDiscreteTriggerEvaluator;
import com.example.iml.orchestrator.integration.trigger.gpio.TriggerEdgeMode;
import com.example.iml.orchestrator.integration.trigger.parse.IoInputDiChange;
import com.example.iml.orchestrator.integration.trigger.parse.IoInputDiChangeParser;
import org.apache.logging.log4j.Logger;

import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.util.List;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * UDP-слушатель DI: DI2 — текущее направление, DI3 — триггер съёмки.
 */
public final class IoInputMonitorUdpTriggerTransport implements TriggerTransport {

    private final Logger log;
    private final UdpTriggerConfig udpConfig;
    private final IoInputDiscreteConfig ioInputConfig;
    private final InspectionTriggerBus bus;
    private final Runnable onLineWorkChanged;
    private final LineDiscreteTriggerEvaluator evaluator;
    private final IoInputDirectionLatch directionLatch = new IoInputDirectionLatch();
    private final IoInputDirectionAutoCapture directionAutoCapture = new IoInputDirectionAutoCapture();
    private final IoInputWorkSessionDirection workSessionDirection = new IoInputWorkSessionDirection();
    private final ScheduledExecutorService directionWaitExecutor;
    private final IoInputDirectionWaiter directionWaiter;
    private final AtomicBoolean running = new AtomicBoolean(false);
    private final AtomicBoolean lineWorkActive = new AtomicBoolean(false);

    private volatile boolean workActive;
    private volatile boolean directionRawActive;
    private volatile boolean directionActive;
    private volatile boolean triggerActive;
    private volatile boolean captureFiredThisPulse;
    private volatile long pendingPrefireSequence;
    private long lastFireMs;
    private Thread listenerThread;
    private DatagramSocket socket;

    public IoInputMonitorUdpTriggerTransport(
            Logger log,
            UdpTriggerConfig udpConfig,
            IoInputDiscreteConfig ioInputConfig,
            InspectionTriggerBus bus,
            Runnable onLineWorkChanged
    ) {
        this.log = log;
        this.udpConfig = udpConfig;
        this.ioInputConfig = ioInputConfig;
        this.bus = bus;
        this.onLineWorkChanged = onLineWorkChanged == null ? () -> { } : onLineWorkChanged;
        this.evaluator = new LineDiscreteTriggerEvaluator(ioInputConfig.triggerEdge());
        this.directionWaitExecutor = Executors.newSingleThreadScheduledExecutor(r -> {
            Thread t = new Thread(r, "io-input-direction-wait");
            t.setDaemon(true);
            return t;
        });
        this.directionWaiter = new IoInputDirectionWaiter(
                log,
                directionWaitExecutor,
                ioInputConfig.directionWaitMs(),
                ioInputConfig.directionPollMs(),
                () -> directionActive,
                () -> !ioInputConfig.requireWork() || isEffectiveWork(),
                () -> tryCommitLineCapture("direction wait", false),
                this::logDirectionWaitTimeout
        );
        if (ioInputConfig.stubWorkActive()) {
            workActive = true;
            lineWorkActive.set(true);
            if (ioInputConfig.directionLatchOnWork()) {
                workSessionDirection.onWorkStarted(directionActive, log);
            }
        }
    }

    public boolean isLineWorkActive() {
        return lineWorkActive.get();
    }

    @Override
    public void start() {
        if (!udpConfig.enabled() || !running.compareAndSet(false, true)) {
            return;
        }
        listenerThread = new Thread(this::listenLoop, "io-input-trigger");
        listenerThread.setDaemon(true);
        listenerThread.start();
    }

    private void listenLoop() {
        try {
            InetAddress bindAddress = InetAddress.getByName(udpConfig.bindHost());
            socket = new DatagramSocket(new InetSocketAddress(bindAddress, udpConfig.bindPort()));
            socket.setReuseAddress(true);
            log.info(
                    "io_input_trigger listening {}:{} payload_format={} di={}/{}/{} trigger_edge={} require_direction={} require_work={} di3_only={} direction_latch_on_work={} direction_arm_next_di3={} direction_invert={} direction_wait_ms={} direction_poll_ms={} debounce_ms={} stub_work={}",
                    udpConfig.bindHost(),
                    udpConfig.bindPort(),
                    ioInputConfig.payloadFormat(),
                    ioInputConfig.workPort(),
                    ioInputConfig.directionPort(),
                    ioInputConfig.triggerPort(),
                    ioInputConfig.triggerEdge(),
                    ioInputConfig.requireDirection(),
                    ioInputConfig.requireWork(),
                    ioInputConfig.di3Only(),
                    ioInputConfig.directionLatchOnWork(),
                    ioInputConfig.directionArmNextDi3(),
                    ioInputConfig.directionInvert(),
                    ioInputConfig.directionWaitMs(),
                    ioInputConfig.directionPollMs(),
                    ioInputConfig.debounceMs(),
                    ioInputConfig.stubWorkActive()
            );
            byte[] buffer = new byte[2048];
            while (running.get() && !socket.isClosed()) {
                DatagramPacket packet = new DatagramPacket(buffer, buffer.length);
                socket.receive(packet);
                handlePacket(packet);
            }
        } catch (Exception e) {
            if (running.get()) {
                log.error("io_input_trigger listener failed: {}", e.getMessage(), e);
            }
        } finally {
            closeSocket();
        }
    }

    private void handlePacket(DatagramPacket packet) {
        InetSocketAddress remote = new InetSocketAddress(packet.getAddress(), packet.getPort());
        if (!isRemoteAllowed(remote.getAddress().getHostAddress())) {
            log.debug("io_input_trigger ignored sender {}", remote);
            return;
        }
        IoInputDiChangeParser.parse(packet.getData(), packet.getLength(), ioInputConfig.payloadFormat())
                .ifPresent(this::applyDiChange);
    }

    private boolean isRemoteAllowed(String host) {
        List<String> allowed = udpConfig.allowedRemoteHosts();
        if (allowed == null || allowed.isEmpty()) {
            return true;
        }
        return allowed.contains(host);
    }

    private void applyDiChange(IoInputDiChange change) {
        int port = change.diPort();
        boolean active = change.active();
        if (port == ioInputConfig.workPort()) {
            boolean previousWork = workActive;
            workActive = active;
            updateLineWork(active);
            if (ioInputConfig.directionLatchOnWork()) {
                if (active && !previousWork) {
                    workSessionDirection.onWorkStarted(directionActive, log);
                } else if (!active && previousWork) {
                    workSessionDirection.onWorkStopped(log);
                }
            } else if (!ioInputConfig.di3Only() && ioInputConfig.requireDirection()) {
                evaluateTriggerDecision(port, active);
            }
            return;
        }
        if (port == ioInputConfig.directionPort()) {
            boolean previousRaw = directionRawActive;
            if (usesAutoDirection()) {
                if (previousRaw != active) {
                    log.info("io_input_trigger direction raw {} -> {}", previousRaw ? 1 : 0, active ? 1 : 0);
                    directionAutoCapture.onDirectionRawChange(previousRaw, active);
                }
                directionRawActive = active;
                directionActive = active;
                return;
            }
            boolean mapped = mapDirection(active);
            if (directionActive != mapped) {
                if (ioInputConfig.directionInvert()) {
                    log.info(
                            "io_input_trigger direction raw {} -> {} (effective {} -> {})",
                            directionRawActive() ? 1 : 0,
                            active ? 1 : 0,
                            directionActive ? 1 : 0,
                            mapped ? 1 : 0
                    );
                } else {
                    log.info("io_input_trigger direction {} -> {}", directionActive ? 1 : 0, mapped ? 1 : 0);
                }
            }
            directionRawActive = active;
            directionActive = mapped;
            if (ioInputConfig.directionLatchOnWork()) {
                workSessionDirection.onDirectionChange(mapped, workActive, log);
                return;
            }
            directionLatch.onDirectionChange(mapped, triggerActive);
            if (ioInputConfig.di3Only() && usesAutoDirection()) {
                return;
            }
            if (ioInputConfig.di3Only()) {
                if (!mapped && directionWaiter.isWaiting()) {
                    directionWaiter.cancel("DI2 direction=0");
                }
                directionWaiter.onDirectionReadyEvent();
                if (mapped && triggerActive) {
                    tryCommitLineCapture("DI2 during DI3 pulse", false);
                }
                return;
            }
            if (ioInputConfig.requireDirection()) {
                directionWaiter.onDirectionReadyEvent();
                if (triggerActive) {
                    evaluateTriggerDecision(port, active);
                }
            }
            return;
        }
        if (port == ioInputConfig.triggerPort()) {
            if (active && !triggerActive) {
                log.info(
                        "io_input_trigger DI3 capture edge direction={}",
                        directionActive ? 1 : 0
                );
                if (usesAutoDirection()) {
                    directionAutoCapture.onDi3Rising(directionRawActive);
                    tryPrefireOnDi3Rise();
                }
            } else if (!active && triggerActive) {
                log.info(
                        "io_input_trigger DI3 release direction={}",
                        directionActive ? 1 : 0
                );
                if (usesAutoDirection()) {
                    IoInputDirectionAutoCapture.Di3FallingAction fallAction =
                            directionAutoCapture.onDi3Falling(directionRawActive);
                    switch (fallAction) {
                        case CAPTURE_FORWARD -> {
                            log.info("io_input_trigger auto direction DI2=0 at DI3↓ after 1→0 — dispatch");
                            tryCommitLineCapture("DI3 fall forward", true);
                        }
                        case ABORT_PREFIRE -> {
                            log.info(
                                    "io_input_trigger auto direction no DI2 0→1 in pulse — abort prefire"
                            );
                            abortPendingPrefire("no forward confirmation");
                        }
                        case REVERSE_SKIP -> {
                            log.info(
                                    "io_input_trigger auto direction reverse pulse — skip"
                            );
                            abortPendingPrefire("reverse pulse");
                        }
                        case NONE -> { }
                    }
                    directionAutoCapture.onDi3Released();
                }
                captureFiredThisPulse = false;
                if (directionWaiter.isWaiting()) {
                    directionWaiter.cancel("DI3 released before direction");
                }
            }
            triggerActive = active;
            if (ioInputConfig.directionLatchOnWork()) {
                evaluateTriggerDecision(port, active);
            } else if (ioInputConfig.triggerEdge() == TriggerEdgeMode.RISING && active) {
                handleDi3RisingCapture();
            } else if (ioInputConfig.triggerEdge() == TriggerEdgeMode.FALLING && !active) {
                evaluateTriggerDecision(port, active);
                directionLatch.onTriggerRelease();
            }
            return;
        }
        if (port > 0) {
            log.debug("io_input_trigger ignored di={} value={}", port, active ? 1 : 0);
        } else {
            log.debug("io_input_trigger legacy payload without di port ignored");
        }
    }

    private boolean usesAutoDirection() {
        return ioInputConfig.di3Only()
                && ioInputConfig.requireDirection()
                && !ioInputConfig.directionLatchOnWork();
    }

    private void handleDi3RisingCapture() {
        if (usesAutoDirection()) {
            return;
        }
        if (directionActive || !ioInputConfig.requireDirection()) {
            tryCommitLineCapture("DI3 edge", false);
        } else {
            handleMissingDirection();
        }
    }

    private void evaluateTriggerDecision(int changedPort, boolean changedActive) {
        if (ioInputConfig.directionLatchOnWork()) {
            boolean effectiveWork = isEffectiveWork();
            if (!workSessionDirection.allowsCapture(
                    ioInputConfig.requireWork(),
                    effectiveWork,
                    ioInputConfig.requireDirection()
            )) {
                if (ioInputConfig.requireWork() && !effectiveWork) {
                    log.info("io_input_trigger skip: conveyor not running (work=0)");
                } else if (!workSessionDirection.sessionDirectionKnown()) {
                    log.info("io_input_trigger skip: session direction not latched yet (await DI2 after DI1)");
                } else {
                    log.info("io_input_trigger skip: session direction=0 (latched at work start)");
                }
                return;
            }
            LineDiscreteTriggerEvaluator.Decision sessionDecision = evaluator.evaluate(
                    true,
                    true,
                    triggerActive,
                    false,
                    false
            );
            if (sessionDecision == LineDiscreteTriggerEvaluator.Decision.FIRE) {
                log.info(
                        "io_input_trigger capture on DI3 edge session_direction={}",
                        workSessionDirection.sessionDirectionActive() ? 1 : 0
                );
                publishDebounced();
            }
            return;
        }

        boolean effectiveWork = isEffectiveWork();
        boolean effectiveDirection = ioInputConfig.requireDirection() ? directionActive : true;
        LineDiscreteTriggerEvaluator.Decision decision = evaluator.evaluate(
                effectiveWork,
                effectiveDirection,
                triggerActive,
                ioInputConfig.requireDirection(),
                ioInputConfig.requireWork()
        );
        switch (decision) {
            case NONE -> { }
            case SKIP_NOT_READY -> log.info("io_input_trigger skip: conveyor not running (work=0)");
            case SKIP_WRONG_DIRECTION -> handleMissingDirection();
            case FIRE -> {
                log.info(
                        "io_input_trigger capture on DI3 edge direction={}",
                        directionActive ? 1 : 0
                );
                publishDebounced();
            }
        }
    }

    private void handleMissingDirection() {
        if (ioInputConfig.requireDirection() && ioInputConfig.directionWaitMs() > 0) {
            directionWaiter.begin("DI3 edge, polling DI2");
            return;
        }
        log.info(
                "io_input_trigger skip: DI2=0 at DI3 edge — await DI2=1 during pulse (up to {} ms)",
                ioInputConfig.directionWaitMs() > 0 ? ioInputConfig.directionWaitMs() : "pulse end"
        );
    }

    /**
     * DI3↑: trigger_only сразу (экспозиция ~30 ms). Dispatch в пайплайн — после подтверждения DI2.
     */
    private void tryPrefireOnDi3Rise() {
        if (!directionAutoCapture.prefireAllowedAtRise(directionRawActive)) {
            log.info("io_input_trigger auto direction DI2=0 at DI3↑ — no prefire (reverse)");
            abortPendingPrefire("reverse DI3 rise");
            return;
        }
        abortPendingPrefire("new DI3 pulse");
        pendingPrefireSequence = bus.prefireLineBroadcast("io_input");
        log.info("io_input_trigger prefire at DI3↑ seq={}", pendingPrefireSequence);
    }

    private void abortPendingPrefire(String reason) {
        if (pendingPrefireSequence > 0L) {
            log.info(
                    "io_input_trigger prefire seq={} aborted ({})",
                    pendingPrefireSequence,
                    reason
            );
            pendingPrefireSequence = 0L;
        }
    }

    /**
     * Dispatch в пайплайн после подтверждения направления; prefire уже был на DI3↑.
     */
    private void tryCommitLineCapture(String source, boolean ignoreDirectionCheck) {
        if (captureFiredThisPulse) {
            return;
        }
        if (ioInputConfig.triggerEdge() == TriggerEdgeMode.RISING && !triggerActive) {
            if (!(usesAutoDirection() && pendingPrefireSequence > 0L)) {
                return;
            }
        }
        if (ioInputConfig.requireWork() && !isEffectiveWork()) {
            log.info("io_input_trigger skip: conveyor not running (work=0)");
            return;
        }
        if (!ignoreDirectionCheck && ioInputConfig.requireDirection() && !directionActive) {
            return;
        }
        if (ioInputConfig.debounceMs() > 0) {
            long now = System.currentTimeMillis();
            if (now - lastFireMs < ioInputConfig.debounceMs()) {
                log.debug("io_input_trigger debounced");
                return;
            }
            lastFireMs = now;
        }
        long triggerReceivedMs = System.currentTimeMillis();
        int published;
        if (usesAutoDirection() && pendingPrefireSequence > 0L) {
            long seq = pendingPrefireSequence;
            pendingPrefireSequence = 0L;
            published = bus.dispatchLineBroadcast("io_input", seq);
        } else {
            published = bus.publishBroadcast(InspectionTriggerEvent.lineBroadcast("io_input"));
        }
        if (published > 0) {
            captureFiredThisPulse = true;
            long dispatchMs = System.currentTimeMillis() - triggerReceivedMs;
            log.info(
                    "io_input_trigger capture direction={} cameras={} ({}) dispatch_ms={}",
                    directionActive ? 1 : 0,
                    published,
                    source,
                    dispatchMs
            );
        }
    }

    private void logDirectionWaitTimeout() {
        log.info(
                "io_input_trigger skip: direction timeout after {} ms (current DI2={})",
                ioInputConfig.directionWaitMs(),
                directionActive ? 1 : 0
        );
    }

    private boolean isEffectiveWork() {
        return ioInputConfig.stubWorkActive() || workActive;
    }

    private boolean mapDirection(boolean rawDiActive) {
        return ioInputConfig.directionInvert() ? !rawDiActive : rawDiActive;
    }

    private boolean directionRawActive() {
        return directionRawActive;
    }

    private void updateLineWork(boolean work) {
        boolean previous = lineWorkActive.getAndSet(work);
        if (previous != work) {
            log.info("io_input_trigger line work {} -> {}", previous ? 1 : 0, work ? 1 : 0);
            onLineWorkChanged.run();
        }
    }

    private void publishDebounced() {
        long triggerReceivedMs = System.currentTimeMillis();
        if (ioInputConfig.debounceMs() > 0) {
            long now = System.currentTimeMillis();
            if (now - lastFireMs < ioInputConfig.debounceMs()) {
                log.debug("io_input_trigger debounced");
                return;
            }
            lastFireMs = now;
        }
        int published = bus.publishBroadcast(InspectionTriggerEvent.lineBroadcast("io_input"));
        if (published > 0) {
            long dispatchMs = System.currentTimeMillis() - triggerReceivedMs;
            log.info(
                    "io_input_trigger line broadcast cameras={} dispatch_ms={}",
                    published,
                    dispatchMs
            );
        }
    }

    @Override
    public void close() {
        running.set(false);
        directionWaiter.close();
        directionWaitExecutor.shutdown();
        try {
            if (!directionWaitExecutor.awaitTermination(500L, TimeUnit.MILLISECONDS)) {
                directionWaitExecutor.shutdownNow();
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            directionWaitExecutor.shutdownNow();
        }
        closeSocket();
        if (listenerThread != null) {
            listenerThread.interrupt();
            try {
                listenerThread.join(1500L);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }
        log.info("io_input_trigger stopped");
    }

    private void closeSocket() {
        DatagramSocket s = socket;
        if (s != null && !s.isClosed()) {
            s.close();
        }
    }
}

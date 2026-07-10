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
 * Постоянный UDP-слушатель DI3 (триггер). DI2 (направление) учитывается с опросом и таймаутом.
 */
public final class IoInputMonitorUdpTriggerTransport implements TriggerTransport {

    private final Logger log;
    private final UdpTriggerConfig udpConfig;
    private final IoInputDiscreteConfig ioInputConfig;
    private final InspectionTriggerBus bus;
    private final Runnable onLineWorkChanged;
    private final LineDiscreteTriggerEvaluator evaluator;
    private final IoInputDirectionLatch directionLatch = new IoInputDirectionLatch();
    private final ScheduledExecutorService directionWaitExecutor;
    private final IoInputDirectionWaiter directionWaiter;
    private final AtomicBoolean running = new AtomicBoolean(false);
    private final AtomicBoolean lineWorkActive = new AtomicBoolean(false);

    private volatile boolean workActive;
    private volatile boolean directionRawActive;
    private volatile boolean directionActive;
    private volatile boolean triggerActive;
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
                () -> directionLatch.isSatisfied(directionActive),
                this::isEffectiveWork,
                this::publishDebounced,
                this::logDirectionWaitTimeout
        );
        if (ioInputConfig.stubWorkActive()) {
            workActive = true;
            lineWorkActive.set(true);
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
                    "io_input_trigger listening {}:{} payload_format={} di={}/{}/{} trigger_edge={} require_direction={} require_work={} di3_only={} direction_invert={} direction_wait_ms={} direction_poll_ms={} debounce_ms={} stub_work={}",
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
            workActive = active;
            updateLineWork(active);
            if (!ioInputConfig.di3Only() && ioInputConfig.requireDirection()) {
                evaluateTriggerDecision(port, active);
            }
            return;
        }
        if (port == ioInputConfig.directionPort()) {
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
            directionLatch.onDirectionChange(active, triggerActive);
            if (ioInputConfig.di3Only()) {
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
                directionLatch.onTriggerArm(directionActive);
                log.info(
                        "io_input_trigger DI3 capture edge direction={}",
                        directionActive ? 1 : 0
                );
            } else if (!active && triggerActive) {
                log.info(
                        "io_input_trigger DI3 release direction={} seen={}",
                        directionActive ? 1 : 0,
                        directionLatch.seenWhileTriggered() ? 1 : 0
                );
                if (directionWaiter.isWaiting()) {
                    directionWaiter.cancel("DI3 released before direction");
                }
                triggerActive = active;
                evaluateTriggerDecision(port, active);
                directionLatch.onTriggerRelease();
                return;
            }
            triggerActive = active;
            evaluateTriggerDecision(port, active);
            return;
        }
        if (port > 0) {
            log.debug("io_input_trigger ignored di={} value={}", port, active ? 1 : 0);
        } else {
            log.debug("io_input_trigger legacy payload without di port ignored");
        }
    }

    private void evaluateTriggerDecision(int changedPort, boolean changedActive) {
        boolean effectiveWork = isEffectiveWork();
        boolean effectiveDirection = resolveDirectionForTriggerEvaluation(changedPort, changedActive);
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
                if (ioInputConfig.di3Only()) {
                    log.info("io_input_trigger capture on DI3 edge (di3_only=true)");
                } else if (ioInputConfig.requireDirection()) {
                    log.info(
                            "io_input_trigger capture on DI3 edge direction={}",
                            effectiveDirection ? 1 : 0
                    );
                } else {
                    log.info(
                            "io_input_trigger capture on DI3 edge (direction={} tracked only)",
                            directionActive ? 1 : 0
                    );
                }
                publishDebounced();
            }
        }
    }

    private void handleMissingDirection() {
        if (ioInputConfig.requireDirection() && ioInputConfig.directionWaitMs() > 0 && isEffectiveWork()) {
            directionWaiter.begin("DI3 edge, polling DI2");
            return;
        }
        log.info(
                "io_input_trigger skip: DI2 direction=0 at DI3 edge (latched={} current={})",
                directionLatch.atTriggerArm() ? 1 : 0,
                directionActive ? 1 : 0
        );
    }

    private void logDirectionWaitTimeout() {
        log.info(
                "io_input_trigger skip: direction timeout after {} ms (latched={} current={} seen={})",
                ioInputConfig.directionWaitMs(),
                directionLatch.atTriggerArm() ? 1 : 0,
                directionActive ? 1 : 0,
                directionLatch.seenWhileTriggered() ? 1 : 0
        );
    }

    private boolean resolveDirectionForTriggerEvaluation(int changedPort, boolean changedActive) {
        if (!ioInputConfig.requireDirection()) {
            return true;
        }
        if (changedPort == ioInputConfig.triggerPort()
                && !changedActive
                && ioInputConfig.triggerEdge() == TriggerEdgeMode.FALLING) {
            return directionLatch.effectiveForFallingEdge(directionActive);
        }
        if (triggerActive) {
            return directionLatch.isSatisfied(directionActive);
        }
        return directionActive;
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

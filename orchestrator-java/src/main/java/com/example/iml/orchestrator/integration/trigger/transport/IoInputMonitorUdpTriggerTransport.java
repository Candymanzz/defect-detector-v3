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
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Приём DI от {@code IoInputMonitor} по UDP и оценка линии (работа / направление / триггер).
 */
public final class IoInputMonitorUdpTriggerTransport implements TriggerTransport {

    private final Logger log;
    private final UdpTriggerConfig udpConfig;
    private final IoInputDiscreteConfig ioInputConfig;
    private final InspectionTriggerBus bus;
    private final Runnable onLineWorkChanged;
    private final LineDiscreteTriggerEvaluator evaluator;
    private final AtomicBoolean running = new AtomicBoolean(false);
    private final AtomicBoolean lineWorkActive = new AtomicBoolean(false);

    private volatile boolean workActive;
    private volatile boolean directionActive;
    private volatile boolean triggerActive;
    /** Направление в момент замыкания DI3 (для falling edge, когда DI2 уже разомкнулся). */
    private volatile boolean directionAtTriggerArm;
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
                    "io_input_trigger listening {}:{} payload_format={} di={}/{}/{} trigger_edge={} debounce_ms={} stub_work={}",
                    udpConfig.bindHost(),
                    udpConfig.bindPort(),
                    ioInputConfig.payloadFormat(),
                    ioInputConfig.workPort(),
                    ioInputConfig.directionPort(),
                    ioInputConfig.triggerPort(),
                    ioInputConfig.triggerEdge(),
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
        } else if (port == ioInputConfig.directionPort()) {
            if (directionActive != active) {
                log.info("io_input_trigger direction {} -> {}", directionActive ? 1 : 0, active ? 1 : 0);
            }
            directionActive = active;
        } else if (port == ioInputConfig.triggerPort()) {
            if (active && !triggerActive) {
                directionAtTriggerArm = directionActive;
                log.info(
                        "io_input_trigger trigger arm direction={} (DI3 0->1)",
                        directionAtTriggerArm ? 1 : 0
                );
            } else if (!active && triggerActive) {
                log.info("io_input_trigger trigger release (DI3 1->0) latched_direction={}", directionAtTriggerArm ? 1 : 0);
            }
            triggerActive = active;
        } else if (port > 0) {
            log.debug("io_input_trigger ignored di={} value={}", port, active ? 1 : 0);
            return;
        } else {
            log.debug("io_input_trigger legacy payload without di port ignored");
            return;
        }

        boolean effectiveWork = ioInputConfig.stubWorkActive() || workActive;
        boolean effectiveDirection = resolveDirectionForTriggerEvaluation(port, active);
        LineDiscreteTriggerEvaluator.Decision decision = evaluator.evaluate(
                effectiveWork,
                effectiveDirection,
                triggerActive
        );
        switch (decision) {
            case NONE -> { }
            case SKIP_NOT_READY -> log.info("io_input_trigger skip: conveyor not running (work=0)");
            case SKIP_WRONG_DIRECTION -> log.info(
                    "io_input_trigger skip: direction=0 at trigger edge (latched={} current={})",
                    directionAtTriggerArm ? 1 : 0,
                    directionActive ? 1 : 0
            );
            case FIRE -> publishDebounced();
        }
    }

    private boolean resolveDirectionForTriggerEvaluation(int changedPort, boolean changedActive) {
        if (changedPort == ioInputConfig.triggerPort()
                && !changedActive
                && ioInputConfig.triggerEdge() == TriggerEdgeMode.FALLING) {
            return directionAtTriggerArm;
        }
        return directionActive;
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

package com.example.iml.orchestrator.integration.trigger.impl;

import com.example.iml.orchestrator.integration.trigger.config.IoInputDiscreteConfig;
import com.example.iml.orchestrator.integration.trigger.config.UdpTriggerConfig;
import com.example.iml.orchestrator.integration.trigger.DatagramSockets;
import com.example.iml.orchestrator.integration.trigger.parse.IoInputDiChange;
import com.example.iml.orchestrator.integration.trigger.parse.IoInputDiChangeParser;
import org.apache.logging.log4j.Logger;

import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Consumer;

/**
 * UDP loop: bind socket, receive packets, parse DI changes.
 */
final class IoInputUdpListener {

    private final Logger log;
    private final UdpTriggerConfig udpConfig;
    private final IoInputDiscreteConfig ioInputConfig;
    private final Consumer<IoInputDiChange> onDiChange;
    private final AtomicBoolean running = new AtomicBoolean(false);
    private Thread listenerThread;
    private DatagramSocket socket;

    IoInputUdpListener(
            Logger log,
            UdpTriggerConfig udpConfig,
            IoInputDiscreteConfig ioInputConfig,
            Consumer<IoInputDiChange> onDiChange
    ) {
        this.log = log;
        this.udpConfig = udpConfig;
        this.ioInputConfig = ioInputConfig;
        this.onDiChange = onDiChange;
    }

    /**
     * Arms the listener and starts the UDP thread. {@code afterArmed} runs after CAS success
     * and before the thread starts (for startup log lines).
     *
     * @return false if already running
     */
    boolean start(Runnable afterArmed) {
        if (!running.compareAndSet(false, true)) {
            return false;
        }
        if (afterArmed != null) {
            afterArmed.run();
        }
        listenerThread = new Thread(this::listenLoop, "io-input-trigger");
        listenerThread.setDaemon(true);
        listenerThread.start();
        return true;
    }

    void stop() {
        running.set(false);
    }

    void close() {
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
    }

    private void listenLoop() {
        try {
            InetAddress bindAddress = InetAddress.getByName(udpConfig.bindHost());
            socket = new DatagramSocket(new InetSocketAddress(bindAddress, udpConfig.bindPort()));
            socket.setReuseAddress(true);
            log.info(
                    "io_input_trigger listening {}:{} payload_format={} di={}/{}/{} trigger_edge={} require_direction={} require_work={} di3_only={} direction_latch={} direction_latch_on_work={} direction_arm_next_di3={} direction_invert={} direction_wait_ms={} direction_poll_ms={} capture_delay_ms={} debounce_ms={} stub_work={}",
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
                    ioInputConfig.directionLatch(),
                    ioInputConfig.directionLatchOnWork(),
                    ioInputConfig.directionArmNextDi3(),
                    ioInputConfig.directionInvert(),
                    ioInputConfig.directionWaitMs(),
                    ioInputConfig.directionPollMs(),
                    ioInputConfig.captureDelayMs(),
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
                .ifPresent(onDiChange);
    }

    private boolean isRemoteAllowed(String host) {
        List<String> allowed = udpConfig.allowedRemoteHosts();
        if (allowed == null || allowed.isEmpty()) {
            return true;
        }
        return allowed.contains(host);
    }

    private void closeSocket() {
        DatagramSockets.closeQuietly(socket);
    }
}

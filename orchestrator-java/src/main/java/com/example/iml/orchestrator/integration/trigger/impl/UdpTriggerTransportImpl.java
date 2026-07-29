package com.example.iml.orchestrator.integration.trigger.impl;

import com.example.iml.orchestrator.integration.trigger.api.TriggerTransport;

import com.example.iml.orchestrator.integration.trigger.InspectionTriggerBus;
import com.example.iml.orchestrator.integration.trigger.InspectionTriggerEvent;
import com.example.iml.orchestrator.integration.trigger.config.UdpTriggerConfig;
import com.example.iml.orchestrator.integration.trigger.impl.DiscreteUdpTriggerMessageParserImpl;
import com.example.iml.orchestrator.integration.trigger.impl.JsonUdpTriggerMessageParserImpl;
import com.example.iml.orchestrator.integration.trigger.impl.PlainUdpTriggerMessageParserImpl;
import com.example.iml.orchestrator.integration.trigger.api.UdpTriggerMessageParser;
import org.apache.logging.log4j.Logger;

import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;

public final class UdpTriggerTransportImpl implements TriggerTransport {

    private final Logger log;
    private final UdpTriggerConfig config;
    private final InspectionTriggerBus bus;
    private final UdpTriggerMessageParser parser;
    private final AtomicBoolean running = new AtomicBoolean(false);
    private static final int BROADCAST_DEBOUNCE_KEY = -1;

    private final Map<Integer, Long> lastTriggerMsByCamera = new ConcurrentHashMap<>();
    private Thread listenerThread;
    private DatagramSocket socket;

    public UdpTriggerTransportImpl(Logger log, UdpTriggerConfig config, InspectionTriggerBus bus) {
        this.log = log;
        this.config = config;
        this.bus = bus;
        this.parser = selectParser(config.format());
    }

    @Override
    public void start() {
        if (!config.enabled() || !running.compareAndSet(false, true)) {
            return;
        }
        listenerThread = new Thread(this::listenLoop, "udp-trigger");
        listenerThread.setDaemon(true);
        listenerThread.start();
    }

    private void listenLoop() {
        try {
            InetAddress bindAddress = InetAddress.getByName(config.bindHost());
            socket = new DatagramSocket(new InetSocketAddress(bindAddress, config.bindPort()));
            socket.setReuseAddress(true);
            log.info(
                    "udp_trigger listening {}:{} format={} default_camera_id={} debounce_ms={}",
                    config.bindHost(),
                    config.bindPort(),
                    config.format(),
                    config.defaultCameraId(),
                    config.debounceMs()
            );
            byte[] buffer = new byte[2048];
            while (running.get() && !socket.isClosed()) {
                DatagramPacket packet = new DatagramPacket(buffer, buffer.length);
                socket.receive(packet);
                handlePacket(packet);
            }
        } catch (Exception e) {
            if (running.get()) {
                log.error("udp_trigger listener failed: {}", e.getMessage(), e);
            }
        } finally {
            closeSocket();
        }
    }

    private void handlePacket(DatagramPacket packet) {
        InetSocketAddress remote = new InetSocketAddress(packet.getAddress(), packet.getPort());
        if (!isRemoteAllowed(remote.getAddress().getHostAddress())) {
            log.debug("udp_trigger ignored sender {}", remote);
            return;
        }
        parser.parse(packet.getData(), packet.getLength(), remote, config.defaultCameraId())
                .ifPresent(this::publishDebounced);
    }

    private boolean isRemoteAllowed(String host) {
        List<String> allowed = config.allowedRemoteHosts();
        if (allowed == null || allowed.isEmpty()) {
            return true;
        }
        return allowed.contains(host);
    }

    private void publishDebounced(InspectionTriggerEvent event) {
        if (event.broadcast()) {
            if (isDebounced(BROADCAST_DEBOUNCE_KEY)) {
                log.debug("udp_trigger debounced line broadcast");
                return;
            }
            int published = bus.publishBroadcast(event);
            if (published > 0) {
                log.info("udp_trigger line broadcast cameras={} (source={})", published, event.source());
            }
            return;
        }
        if (!bus.hasCamera(event.cameraId())) {
            log.warn("udp_trigger unknown camera_id={} from {}", event.cameraId(), event.source());
            return;
        }
        if (isDebounced(event.cameraId())) {
            log.debug("udp_trigger debounced cam={}", event.cameraId());
            return;
        }
        if (bus.publish(event)) {
            log.info("udp_trigger cam={} seq pending (source={})", event.cameraId(), event.source());
        }
    }

    private boolean isDebounced(int debounceKey) {
        if (config.debounceMs() <= 0) {
            return false;
        }
        long now = System.currentTimeMillis();
        Long last = lastTriggerMsByCamera.get(debounceKey);
        if (last != null && now - last < config.debounceMs()) {
            return true;
        }
        lastTriggerMsByCamera.put(debounceKey, now);
        return false;
    }

    private static UdpTriggerMessageParser selectParser(String format) {
        if ("discrete".equalsIgnoreCase(format)) {
            return new DiscreteUdpTriggerMessageParserImpl();
        }
        if ("plain".equalsIgnoreCase(format)) {
            return new PlainUdpTriggerMessageParserImpl();
        }
        return new JsonUdpTriggerMessageParserImpl();
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
        log.info("udp_trigger stopped");
    }

    private void closeSocket() {
        DatagramSocket s = socket;
        if (s != null && !s.isClosed()) {
            s.close();
        }
    }
}

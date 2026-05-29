package com.example.iml.orchestrator.integration.trigger.transport;

import com.example.iml.orchestrator.integration.trigger.InspectionTriggerBus;
import com.example.iml.orchestrator.integration.trigger.InspectionTriggerEvent;
import com.example.iml.orchestrator.integration.trigger.config.UdpTriggerConfig;
import com.example.iml.orchestrator.integration.trigger.parse.JsonUdpTriggerMessageParser;
import com.example.iml.orchestrator.integration.trigger.parse.PlainUdpTriggerMessageParser;
import com.example.iml.orchestrator.integration.trigger.parse.UdpTriggerMessageParser;
import org.apache.logging.log4j.Logger;

import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;

public final class UdpTriggerTransport implements TriggerTransport {

    private final Logger log;
    private final UdpTriggerConfig config;
    private final InspectionTriggerBus bus;
    private final UdpTriggerMessageParser parser;
    private final AtomicBoolean running = new AtomicBoolean(false);
    private final Map<Integer, Long> lastTriggerMsByCamera = new ConcurrentHashMap<>();
    private Thread listenerThread;
    private DatagramSocket socket;

    public UdpTriggerTransport(Logger log, UdpTriggerConfig config, InspectionTriggerBus bus) {
        this.log = log;
        this.config = config;
        this.bus = bus;
        this.parser = "plain".equalsIgnoreCase(config.format())
                ? new PlainUdpTriggerMessageParser()
                : new JsonUdpTriggerMessageParser();
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
        if (!bus.hasCamera(event.cameraId())) {
            log.warn("udp_trigger unknown camera_id={} from {}", event.cameraId(), event.source());
            return;
        }
        if (config.debounceMs() > 0) {
            long now = System.currentTimeMillis();
            Long last = lastTriggerMsByCamera.get(event.cameraId());
            if (last != null && now - last < config.debounceMs()) {
                log.debug("udp_trigger debounced cam={}", event.cameraId());
                return;
            }
            lastTriggerMsByCamera.put(event.cameraId(), now);
        }
        if (bus.publish(event)) {
            log.info("udp_trigger cam={} seq pending (source={})", event.cameraId(), event.source());
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
        log.info("udp_trigger stopped");
    }

    private void closeSocket() {
        DatagramSocket s = socket;
        if (s != null && !s.isClosed()) {
            s.close();
        }
    }
}

package com.example.iml.orchestrator.integration.clientws;

import com.example.iml.orchestrator.integration.clientws.config.ClientWsConfig;
import com.example.iml.orchestrator.integration.clientws.outbound.WsOutboundMessenger;
import com.example.iml.orchestrator.integration.clientws.routing.WsConnectionPath;
import com.example.iml.orchestrator.integration.stream.CameraStreamService;
import org.apache.logging.log4j.Logger;
import org.java_websocket.WebSocket;
import org.java_websocket.handshake.ClientHandshake;

import java.util.Collection;
import java.util.function.Supplier;

/**
 * Connection open/close, idle ping, and active-client tracking for {@link ClientWebSocketServer}.
 */
final class ClientWsSessionLifecycle {

    private final Logger log;
    private final ClientWsConfig cfg;
    private final WsOutboundMessenger outbound;
    private final Object sessionLock = new Object();
    private final Supplier<Collection<WebSocket>> connections;
    private final Supplier<CameraStreamService> cameraStreamService;
    private WebSocket activeClient;
    private volatile long lastClientActivityEpochMs = System.currentTimeMillis();

    ClientWsSessionLifecycle(
            Logger log,
            ClientWsConfig cfg,
            WsOutboundMessenger outbound,
            Supplier<Collection<WebSocket>> connections,
            Supplier<CameraStreamService> cameraStreamService
    ) {
        this.log = log;
        this.cfg = cfg;
        this.outbound = outbound;
        this.connections = connections;
        this.cameraStreamService = cameraStreamService;
    }

    void onOpen(WebSocket conn, ClientHandshake handshake) {
        if (!WsConnectionPath.allowed(cfg, handshake.getResourceDescriptor())) {
            log.info("client_ws rejected path={}", handshake.getResourceDescriptor());
            conn.close(1008, "invalid_path");
            return;
        }
        WebSocket previous;
        synchronized (sessionLock) {
            previous = activeClient;
            if (previous != null && previous.isOpen() && previous != conn) {
                if (!cfg.replaceExistingSession()) {
                    log.info("client_ws reject second client (replace_existing_session=false)");
                    conn.close(1008, "only_one_client_allowed");
                    return;
                }
            }
            activeClient = conn;
        }
        if (previous != null && previous.isOpen() && previous != conn) {
            log.info("client_ws accepted additional session (multi-client broadcast enabled)");
        }
        markActivity();
        outbound.sendHello(conn);
    }

    void onClose(WebSocket conn, int code, String reason, boolean remote) {
        synchronized (sessionLock) {
            if (activeClient == conn) {
                activeClient = firstOpenClient();
            }
        }
        CameraStreamService streams = cameraStreamService.get();
        Collection<WebSocket> conns = connections.get();
        if (streams != null && (conns == null || conns.isEmpty())) {
            streams.stopAll();
        }
        log.info("client_ws closed code={} reason={} remote={}", code, reason, remote);
    }

    void markActivity() {
        lastClientActivityEpochMs = System.currentTimeMillis();
    }

    void sendProtocolPing() {
        WebSocket c;
        synchronized (sessionLock) {
            c = activeClient;
        }
        if (c == null || !c.isOpen()) {
            return;
        }
        try {
            c.sendPing();
        } catch (RuntimeException e) {
            log.debug("client_ws sendPing: {}", e.getMessage());
        }
        if (idleExceeded()) {
            log.info("client_ws closing peer (no inbound activity within read_idle_timeout_ms)");
            try {
                c.close(1001, "read_idle_timeout");
            } catch (RuntimeException ignored) {
                // peer already gone
            }
        }
    }

    private boolean idleExceeded() {
        return System.currentTimeMillis() - lastClientActivityEpochMs > cfg.readIdleTimeoutMs();
    }

    private WebSocket firstOpenClient() {
        Collection<WebSocket> conns = connections.get();
        if (conns == null) {
            return null;
        }
        for (WebSocket conn : conns) {
            if (conn != null && conn.isOpen()) {
                return conn;
            }
        }
        return null;
    }
}

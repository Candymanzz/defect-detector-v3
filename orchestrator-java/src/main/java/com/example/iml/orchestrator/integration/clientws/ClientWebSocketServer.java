package com.example.iml.orchestrator.integration.clientws;

import com.example.iml.orchestrator.integration.binaryrpc.BinaryRpcSupervisor;
import com.example.iml.orchestrator.integration.clientws.application.ClientWsApplicationContext;
import com.example.iml.orchestrator.integration.clientws.bundle.ReferenceBundleSnapshot;
import com.example.iml.orchestrator.integration.clientws.config.ClientWsConfig;
import com.example.iml.orchestrator.integration.clientws.exception.ClientWsKopcheniSyncException;
import com.example.iml.orchestrator.integration.clientws.outbound.WsOutboundMessenger;
import com.example.iml.orchestrator.integration.clientws.routing.WsConnectionPath;
import com.example.iml.orchestrator.integration.clientws.routing.WsFrontController;
import com.example.iml.orchestrator.integration.clientws.service.ClientWsKopcheniBroadcaster;
import com.example.iml.orchestrator.integration.clientws.service.ReferenceBundleLifecycleService;
import com.example.iml.orchestrator.integration.clientws.session.ClientWsReferenceContext;
import com.example.iml.orchestrator.integration.clientws.session.ClientWsSessionState;
import com.example.iml.orchestrator.integration.config.YamlScalars;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.apache.logging.log4j.Logger;
import org.java_websocket.WebSocket;
import org.java_websocket.framing.Framedata;
import org.java_websocket.handshake.ClientHandshake;
import org.java_websocket.server.WebSocketServer;

import java.net.InetSocketAddress;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

/**
 * WebSocket-сервер UI: lifecycle + делегирование входящих сообщений {@link WsFrontController}.
 */
public final class ClientWebSocketServer extends WebSocketServer implements AutoCloseable {

    private static final ObjectMapper JSON = new ObjectMapper();

    private final Logger log;
    private final ClientWsConfig cfg;
    private final ClientWsReferenceContext referenceContext = new ClientWsReferenceContext();
    private final AtomicReference<ClientWsSessionState> sessionState =
            new AtomicReference<>(ClientWsSessionState.NO_REFERENCE);
    private final ClientWsKopcheniBroadcaster kopcheniBroadcaster;
    private final WsOutboundMessenger outbound;
    private final ClientWsApplicationContext application;
    private final WsFrontController frontController;
    private final Object sessionLock = new Object();
    private WebSocket activeClient;
    private final ScheduledExecutorService pingScheduler = Executors.newSingleThreadScheduledExecutor(r -> {
        Thread t = new Thread(r, "client-ws-ping");
        t.setDaemon(true);
        return t;
    });
    private volatile long lastClientActivityEpochMs = System.currentTimeMillis();

    public ClientWebSocketServer(Logger log, ClientWsConfig cfg) {
        super(new InetSocketAddress(cfg.host(), cfg.port()));
        this.log = log;
        this.cfg = cfg;
        this.kopcheniBroadcaster = new ClientWsKopcheniBroadcaster(log, cfg);
        this.outbound = new WsOutboundMessenger(log, cfg, referenceContext, sessionState::get);
        this.application = new ClientWsApplicationContext(
                log,
                cfg,
                referenceContext,
                sessionState,
                kopcheniBroadcaster,
                outbound
        );
        this.frontController = new WsFrontController(application);
        setReuseAddr(true);
        setConnectionLostTimeout(0);
    }

    public void begin() {
        pingScheduler.scheduleAtFixedRate(
                this::sendProtocolPing,
                cfg.pingIntervalMs(),
                cfg.pingIntervalMs(),
                TimeUnit.MILLISECONDS
        );
        start();
    }

    public ClientWsReferenceContext referenceContext() {
        return referenceContext;
    }

    public ClientWsSessionState sessionState() {
        return sessionState.get();
    }

    public void setSessionState(ClientWsSessionState state) {
        sessionState.set(state == null ? ClientWsSessionState.NO_REFERENCE : state);
    }

    public void setKopcheniPythonPool(List<? extends BinaryRpcSupervisor> pool) {
        kopcheniBroadcaster.setPool(pool);
    }

    public void applyReferenceSnapshotFromDraft(ReferenceBundleSnapshot snap) throws ClientWsKopcheniSyncException {
        WebSocket c;
        synchronized (sessionLock) {
            c = activeClient;
        }
        ReferenceBundleLifecycleService.applyFromDraft(application, c, snap);
    }

    public void notifyInspectResult(
            int cameraId,
            String productType,
            String detectorId,
            Map<String, Object> captureHeader,
            Path heatmapU8Path,
            int heatmapW,
            int heatmapH,
            String heatmapArtifactTokenOrNull,
            boolean includeHeatmapFilePathInWs
    ) {
        if (captureHeader == null || cameraId < 0) {
            return;
        }
        Object sn = captureHeader.get("shm_name");
        if (sn == null) {
            return;
        }
        String shmName = String.valueOf(sn).trim();
        if (shmName.isEmpty()) {
            return;
        }
        long frameId = YamlScalars.toLong(captureHeader.get("frame_id"), -1L);
        if (frameId < 0) {
            return;
        }
        WebSocket c;
        synchronized (sessionLock) {
            c = activeClient;
        }
        if (c == null || !c.isOpen()) {
            return;
        }
        outbound.sendInspectResult(
                c,
                cameraId,
                productType,
                detectorId,
                captureHeader,
                frameId,
                shmName,
                heatmapU8Path,
                heatmapW,
                heatmapH,
                heatmapArtifactTokenOrNull,
                includeHeatmapFilePathInWs
        );
    }

    @Override
    public void onOpen(WebSocket conn, ClientHandshake handshake) {
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
            log.info("client_ws closing previous session (replaced by new client)");
            previous.close(1000, "replaced_by_new_session");
        }
        lastClientActivityEpochMs = System.currentTimeMillis();
        outbound.sendHello(conn);
    }

    @Override
    public void onClose(WebSocket conn, int code, String reason, boolean remote) {
        synchronized (sessionLock) {
            if (activeClient == conn) {
                activeClient = null;
            }
        }
        log.info("client_ws closed code={} reason={} remote={}", code, reason, remote);
    }

    @Override
    public void onMessage(WebSocket conn, String message) {
        lastClientActivityEpochMs = System.currentTimeMillis();
        try {
            JsonNode root = JSON.readTree(message);
            String type = root.path("type").asText("?");
            frontController.dispatch(conn, root, type);
        } catch (JsonProcessingException e) {
            log.warn("client_ws message parse: {}", e.getMessage());
            outbound.sendError(conn, "parse_error", e.getOriginalMessage() == null ? "invalid json" : e.getOriginalMessage());
        }
    }

    @Override
    public void onWebsocketPong(WebSocket conn, Framedata f) {
        lastClientActivityEpochMs = System.currentTimeMillis();
    }

    @Override
    public void onError(WebSocket conn, Exception ex) {
        if (ex != null) {
            log.warn("client_ws error: {}", ex.toString());
        }
    }

    @Override
    public void onStart() {
        log.info(
                "client_ws listening ws://{}:{}{} replace_session={} read_idle_timeout_ms={} ping_interval_ms={}",
                cfg.host(),
                cfg.port(),
                cfg.path(),
                cfg.replaceExistingSession(),
                cfg.readIdleTimeoutMs(),
                cfg.pingIntervalMs()
        );
    }

    @Override
    public void close() {
        pingScheduler.shutdown();
        try {
            if (!pingScheduler.awaitTermination(2, TimeUnit.SECONDS)) {
                pingScheduler.shutdownNow();
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            pingScheduler.shutdownNow();
        }
        try {
            stop(3000);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    private void sendProtocolPing() {
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
}

package com.example.iml.orchestrator.integration.clientws;

import com.example.iml.orchestrator.integration.binaryrpc.BinaryRpcSupervisor;
import com.example.iml.orchestrator.integration.clientws.bundle.BundleParseException;
import com.example.iml.orchestrator.integration.clientws.bundle.FpZoneNorm;
import com.example.iml.orchestrator.integration.clientws.bundle.ReferenceBundleParser;
import com.example.iml.orchestrator.integration.clientws.bundle.ReferenceBundleSnapshot;
import com.example.iml.orchestrator.integration.config.YamlScalars;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.apache.logging.log4j.Logger;
import org.java_websocket.WebSocket;
import org.java_websocket.framing.Framedata;
import org.java_websocket.handshake.ClientHandshake;
import org.java_websocket.server.WebSocketServer;

import java.net.InetSocketAddress;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

/**
 * WebSocket сервер для одного клиента UI: hello, эталоны (Фаза 2), FP/active-view (Фаза 5), результат инспекции (Фаза 4), ping, idle.
 */
public final class ClientWebSocketServer extends WebSocketServer implements AutoCloseable {

    private static final ObjectMapper JSON = new ObjectMapper();

    private final Logger log;
    private final ClientWsConfig cfg;
    private final ClientWsReferenceContext referenceContext = new ClientWsReferenceContext();
    private final AtomicReference<ClientWsSessionState> sessionState = new AtomicReference<>(ClientWsSessionState.NO_REFERENCE);
    private volatile List<? extends BinaryRpcSupervisor> kopcheniPythonPool = List.of();
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
        setReuseAddr(true);
        setConnectionLostTimeout(0);
    }

    public void begin() {
        pingScheduler.scheduleAtFixedRate(this::sendProtocolPing, cfg.pingIntervalMs(), cfg.pingIntervalMs(), TimeUnit.MILLISECONDS);
        start();
    }

    /**
     * In-memory контекст эталонов (Фаза 2); для пайплайна — {@link #referenceContext()}.
     */
    public ClientWsReferenceContext referenceContext() {
        return referenceContext;
    }

    /**
     * Текущее состояние для {@code server.hello} и push-сообщений.
     */
    public ClientWsSessionState sessionState() {
        return sessionState.get();
    }

    public void setSessionState(ClientWsSessionState state) {
        sessionState.set(state == null ? ClientWsSessionState.NO_REFERENCE : state);
    }

    /**
     * Пул stdio kopcheni (тот же, что пайплайн инспекции): синхронизация FP/эталонов по Фазе 5.
     */
    public void setKopcheniPythonPool(List<? extends BinaryRpcSupervisor> pool) {
        kopcheniPythonPool = pool == null ? List.of() : List.copyOf(pool);
    }

    /**
     * После инспекции: один JSON {@code server.inspect_result} — ссылка на current (SHM), дескриптор heatmap (HTTP по opaque-токену с {@link com.example.iml.orchestrator.integration.ui.UiHttpServer} и/или {@code file_path}), FP из контекста (без бинарника).
     *
     * @param heatmapArtifactTokenOrNull токен из {@code UiHttpServer.registerHeatmapArtifact}; если не {@code null} — в payload {@code http_path} = {@code /api/heatmap-artifact/}{@code token} и {@code artifact_id}
     * @param includeHeatmapFilePathInWs если {@code true}, в дескриптор добавляется абсолютный {@code file_path} (fallback при отсутствии токена или отладка)
     */
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
        try {
            String json = buildInspectResultJson(
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
            c.send(json);
            log.info("client_ws sent type=server.inspect_result camera_id={} frame_id={}", cameraId, frameId);
        } catch (Exception e) {
            log.debug("client_ws inspect_result send failed: {}", e.getMessage());
        }
    }

    @Override
    public void onOpen(WebSocket conn, ClientHandshake handshake) {
        if (!pathAllowed(handshake.getResourceDescriptor())) {
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
        sendHello(conn);
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
            log.info("client_ws inbound type={}", type);
            if ("client.reference_bundle".equals(type)) {
                handleReferenceBundle(conn, root);
            } else if ("client.fp_zones_update".equals(type)) {
                handleFpZonesUpdate(conn, root);
            } else if ("client.set_active_reference_view".equals(type)) {
                handleSetActiveReferenceView(conn, root);
            }
        } catch (Exception e) {
            log.warn("client_ws message parse: {}", e.getMessage());
            sendError(conn, "parse_error", e.getMessage() == null ? "invalid json" : truncate(e.getMessage(), 400));
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
        log.info("client_ws listening ws://{}:{}{} replace_session={} read_idle_timeout_ms={} ping_interval_ms={}",
                cfg.host(),
                cfg.port(),
                cfg.path(),
                cfg.replaceExistingSession(),
                cfg.readIdleTimeoutMs(),
                cfg.pingIntervalMs());
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

    /** RFC6455 ping (библиотека + connection lost timeout ожидают pong). */
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
        } catch (Exception e) {
            log.debug("client_ws sendPing: {}", e.getMessage());
        }
        if (idleExceeded()) {
            log.info("client_ws closing peer (no inbound activity within read_idle_timeout_ms)");
            try {
                c.close(1001, "read_idle_timeout");
            } catch (Exception ignored) {
            }
        }
    }

    private boolean idleExceeded() {
        return System.currentTimeMillis() - lastClientActivityEpochMs > cfg.readIdleTimeoutMs();
    }

    private void sendHello(WebSocket conn) {
        try {
            String json = buildHelloJson();
            conn.send(json);
            log.info("client_ws sent type=server.hello session_state={}", sessionState.get());
        } catch (Exception e) {
            log.warn("client_ws hello failed: {}", e.getMessage());
        }
    }

    private String buildHelloJson() throws Exception {
        ObjectNode root = JSON.createObjectNode();
        root.put("type", "server.hello");
        root.put("protocol_version", cfg.protocolVersion());
        root.put("message_id", UUID.randomUUID().toString());
        ObjectNode payload = JSON.createObjectNode();
        payload.put("session_state", sessionState.get().name());
        payload.put("server_ts_ms", System.currentTimeMillis());
        root.set("payload", payload);
        return JSON.writeValueAsString(root);
    }

    private String buildInspectResultJson(
            int cameraId,
            String productType,
            String detectorId,
            Map<String, Object> captureHeader,
            long frameIdLong,
            String shmName,
            Path heatmapU8Path,
            int heatmapW,
            int heatmapH,
            String heatmapArtifactTokenOrNull,
            boolean includeHeatmapFilePathInWs
    ) throws Exception {
        ObjectNode current = buildCurrentShmObjectNode(cameraId, captureHeader, frameIdLong, shmName);
        ObjectNode root = JSON.createObjectNode();
        root.put("type", "server.inspect_result");
        root.put("protocol_version", cfg.protocolVersion());
        root.put("message_id", UUID.randomUUID().toString());
        ObjectNode payload = JSON.createObjectNode();
        payload.put("camera_id", cameraId);
        payload.put("frame_id", Long.toString(frameIdLong));
        payload.put("session_state", sessionState.get().name());
        payload.set("current", current);
        boolean hasHm = heatmapU8Path != null
                && heatmapW > 0
                && heatmapH > 0
                && Files.isRegularFile(heatmapU8Path);
        if (hasHm) {
            ObjectNode hm = JSON.createObjectNode();
            hm.put("width", heatmapW);
            hm.put("height", heatmapH);
            hm.put("pixel_format", "gray_u8");
            hm.put("channels", 1);
            String tok = heatmapArtifactTokenOrNull == null ? "" : heatmapArtifactTokenOrNull.trim();
            if (!tok.isEmpty()) {
                hm.put("artifact_id", tok);
                hm.put("http_path", "/api/heatmap-artifact/" + tok);
            }
            if (includeHeatmapFilePathInWs) {
                hm.put("file_path", heatmapU8Path.toAbsolutePath().toString());
            }
            payload.set("heatmap", hm);
        } else {
            payload.putNull("heatmap");
        }
        payload.put("active_reference_view_index", referenceContext.activeReferenceViewIndex());
        ObjectNode det = JSON.createObjectNode();
        if (detectorId != null && !detectorId.isBlank()) {
            det.put("detector_id", detectorId);
        }
        if (productType != null && !productType.isBlank()) {
            det.put("product_type", productType);
        }
        payload.set("detector", det);
        payload.set("fp_zones", fpZonesJsonArray());
        int hmw = referenceContext.effectiveHeatmapWidth();
        int hmh = referenceContext.effectiveHeatmapHeight();
        if (hmw > 0 && hmh > 0) {
            ObjectNode coo = payload.putObject("fp_coordinate_space");
            coo.put("heatmap_width", hmw);
            coo.put("heatmap_height", hmh);
        }
        payload.put("server_ts_ms", System.currentTimeMillis());
        root.set("payload", payload);
        return JSON.writeValueAsString(root);
    }

    private ObjectNode buildCurrentShmObjectNode(
            int cameraId,
            Map<String, Object> header,
            long frameIdLong,
            String shmName
    ) throws Exception {
        int width = YamlScalars.toInt(header.get("width"), 0);
        int height = YamlScalars.toInt(header.get("height"), 0);
        if (width <= 0 || height <= 0) {
            throw new IllegalArgumentException("width/height");
        }
        String pixelFormat = "bgr_u8";
        Object pf = header.get("pixel_format");
        if (pf != null) {
            String pfs = String.valueOf(pf).trim();
            if (!pfs.isEmpty()) {
                pixelFormat = pfs;
            }
        }
        int channels = YamlScalars.toInt(header.get("channels"), 0);
        if (channels <= 0) {
            channels = "gray_u8".equalsIgnoreCase(pixelFormat) ? 1 : 3;
        }
        int stride = YamlScalars.toInt(header.get("stride"), 0);
        if (stride <= 0) {
            stride = width * channels;
        }
        if (stride < width * channels) {
            throw new IllegalArgumentException("stride");
        }
        long shmOffsetLong = YamlScalars.toLong(header.get("shm_offset"), 0L);
        if (shmOffsetLong < 0 || shmOffsetLong > Integer.MAX_VALUE) {
            throw new IllegalArgumentException("shm_offset");
        }
        int shmOffset = (int) shmOffsetLong;
        ObjectNode current = JSON.createObjectNode();
        current.put("camera_id", cameraId);
        current.put("frame_id", Long.toString(frameIdLong));
        current.put("shm_name", shmName);
        current.put("width", width);
        current.put("height", height);
        current.put("stride", stride);
        current.put("shm_offset", shmOffset);
        current.put("pixel_format", pixelFormat);
        current.put("channels", channels);
        Object exp = header.get("expires_at_ms");
        if (exp instanceof Number n) {
            current.put("expires_at_ms", n.longValue());
        }
        Object ttl = header.get("ttl_ms");
        if (ttl instanceof Number n) {
            current.put("ttl_ms", n.intValue());
        }
        Object rt = header.get("read_token");
        if (rt != null) {
            String tok = String.valueOf(rt).trim();
            if (!tok.isEmpty()) {
                current.put("read_token", tok);
            }
        }
        return current;
    }

    private ArrayNode fpZonesJsonArray() {
        ArrayNode arr = JSON.createArrayNode();
        for (FpZoneNorm z : referenceContext.effectiveFpZones()) {
            ObjectNode zo = arr.addObject();
            if (z.id() != null && !z.id().isBlank()) {
                zo.put("id", z.id());
            }
            zo.put("note", z.note() != null ? z.note() : "");
            ArrayNode pts = zo.putArray("points_norm_heatmap");
            for (FpZoneNorm.PointNorm p : z.pointsNormHeatmap()) {
                ObjectNode po = pts.addObject();
                po.put("x", p.x());
                po.put("y", p.y());
            }
        }
        return arr;
    }

    private void handleReferenceBundle(WebSocket conn, JsonNode root) {
        ReferenceBundleParser.Result r = ReferenceBundleParser.parseBundle(root, cfg.protocolVersion());
        if (r instanceof ReferenceBundleParser.Result.Err err) {
            sendError(conn, err.code(), err.message());
            return;
        }
        ReferenceBundleSnapshot snap = ((ReferenceBundleParser.Result.Ok) r).snapshot();
        try {
            broadcastKopcheni(KopcheniClientWsSync.syncClientReferenceBundle(snap, 0));
        } catch (Exception e) {
            log.warn("client_ws kopcheni sync after bundle failed: {}", e.getMessage());
            sendError(conn, "kopcheni_sync_failed", truncate(e.getMessage(), 400));
            return;
        }
        referenceContext.applyBundle(snap);
        setSessionState(ClientWsSessionState.READY);
        sendReferenceBundleAck(conn, root);
        sendSessionState(conn, ClientWsSessionState.READY);
        setSessionState(ClientWsSessionState.OPERATIONAL);
        sendSessionState(conn, ClientWsSessionState.OPERATIONAL);
        log.info(
                "client_ws reference bundle accepted product_type={} joint_view_index={} fp_zones={}",
                snap.productType(),
                snap.jointViewIndex(),
                snap.fpZones().size()
        );
    }

    private void handleFpZonesUpdate(WebSocket conn, JsonNode root) {
        if (!referenceContext.hasCommittedBundle()) {
            sendError(conn, "no_reference", "accept client.reference_bundle first");
            return;
        }
        JsonNode payload = root.path("payload");
        if (!payload.isObject()) {
            sendError(conn, "invalid_payload", "payload must be object");
            return;
        }
        int pv = payload.path("protocol_version").asInt(-1);
        if (pv != cfg.protocolVersion()) {
            sendError(conn, "invalid_protocol_version", "expected protocol_version=" + cfg.protocolVersion());
            return;
        }
        int hw = payload.path("heatmap_width").asInt(0);
        int hh = payload.path("heatmap_height").asInt(0);
        if (hw <= 0 || hh <= 0) {
            sendError(conn, "invalid_heatmap_size", "heatmap_width and heatmap_height must be positive");
            return;
        }
        List<FpZoneNorm> zones;
        try {
            zones = ReferenceBundleParser.parseFpZonesPayload(payload.path("fp_zones"));
        } catch (BundleParseException e) {
            sendError(conn, e.code(), e.getMessage());
            return;
        }
        String productType = referenceContext.snapshot().map(ReferenceBundleSnapshot::productType).orElse("");
        if (productType.isEmpty()) {
            sendError(conn, "no_reference", "missing product_type context");
            return;
        }
        try {
            broadcastKopcheni(KopcheniClientWsSync.replaceFpZones(productType, hw, hh, zones));
        } catch (Exception e) {
            log.warn("client_ws kopcheni replace_fp_zones failed: {}", e.getMessage());
            sendError(conn, "kopcheni_sync_failed", truncate(e.getMessage(), 400));
            return;
        }
        referenceContext.applyFpZonesHotUpdate(hw, hh, zones);
        sendFpZonesAck(conn, root, true);
    }

    private void handleSetActiveReferenceView(WebSocket conn, JsonNode root) {
        if (!referenceContext.hasCommittedBundle()) {
            sendError(conn, "no_reference", "accept client.reference_bundle first");
            return;
        }
        JsonNode payload = root.path("payload");
        if (!payload.isObject()) {
            sendError(conn, "invalid_payload", "payload must be object");
            return;
        }
        int pv = payload.path("protocol_version").asInt(-1);
        if (pv != cfg.protocolVersion()) {
            sendError(conn, "invalid_protocol_version", "expected protocol_version=" + cfg.protocolVersion());
            return;
        }
        int viewIndex = payload.path("view_index").asInt(-1);
        if (viewIndex < 0 || viewIndex > 4) {
            sendError(conn, "invalid_view_index", "view_index must be 0..4");
            return;
        }
        String productType = referenceContext.snapshot().map(ReferenceBundleSnapshot::productType).orElse("");
        if (productType.isEmpty()) {
            sendError(conn, "no_reference", "missing product_type context");
            return;
        }
        try {
            broadcastKopcheni(KopcheniClientWsSync.setActiveReferenceView(productType, viewIndex));
        } catch (Exception e) {
            log.warn("client_ws kopcheni set_active_reference_view failed: {}", e.getMessage());
            sendError(conn, "kopcheni_sync_failed", truncate(e.getMessage(), 400));
            return;
        }
        referenceContext.setActiveReferenceViewIndex(viewIndex);
        sendActiveReferenceViewAck(conn, root, viewIndex);
    }

    private void broadcastKopcheni(Map<String, Object> header) throws Exception {
        if (!cfg.kopcheniBundleSyncEnabled()) {
            log.debug("client_ws skip kopcheni stdio sync (client_ws.kopcheni_bundle_sync_enabled=false) op={}", header.get("op"));
            return;
        }
        List<? extends BinaryRpcSupervisor> pool = kopcheniPythonPool;
        if (pool == null || pool.isEmpty()) {
            return;
        }
        Exception last = null;
        for (BinaryRpcSupervisor py : pool) {
            try {
                py.command(header);
            } catch (Exception e) {
                last = e;
                log.warn("client_ws kopcheni command failed worker={}: {}", py.supervisorLabel(), e.getMessage());
            }
        }
        if (last != null) {
            throw last;
        }
    }

    private void copyRequestMessageId(ObjectNode root, JsonNode requestEnvelope) {
        if (requestEnvelope != null && requestEnvelope.hasNonNull("message_id")) {
            String mid = requestEnvelope.get("message_id").asText("").trim();
            if (!mid.isEmpty()) {
                root.put("message_id", mid);
                return;
            }
        }
        root.put("message_id", UUID.randomUUID().toString());
    }

    private void sendReferenceBundleAck(WebSocket conn, JsonNode requestRoot) {
        try {
            ObjectNode root = JSON.createObjectNode();
            root.put("type", "server.reference_bundle_ack");
            root.put("protocol_version", cfg.protocolVersion());
            copyRequestMessageId(root, requestRoot);
            ObjectNode payload = JSON.createObjectNode();
            payload.put("ok", true);
            root.set("payload", payload);
            conn.send(JSON.writeValueAsString(root));
            log.info("client_ws sent type=server.reference_bundle_ack");
        } catch (Exception e) {
            log.warn("client_ws ack send failed: {}", e.getMessage());
        }
    }

    private void sendFpZonesAck(WebSocket conn, JsonNode requestRoot, boolean ok) {
        try {
            ObjectNode root = JSON.createObjectNode();
            root.put("type", "server.fp_zones_ack");
            root.put("protocol_version", cfg.protocolVersion());
            copyRequestMessageId(root, requestRoot);
            ObjectNode payload = JSON.createObjectNode();
            payload.put("ok", ok);
            payload.put("server_ts_ms", System.currentTimeMillis());
            root.set("payload", payload);
            conn.send(JSON.writeValueAsString(root));
            log.info("client_ws sent type=server.fp_zones_ack ok={}", ok);
        } catch (Exception e) {
            log.warn("client_ws fp_zones_ack send failed: {}", e.getMessage());
        }
    }

    private void sendActiveReferenceViewAck(WebSocket conn, JsonNode requestRoot, int viewIndex) {
        try {
            ObjectNode root = JSON.createObjectNode();
            root.put("type", "server.active_reference_view_ack");
            root.put("protocol_version", cfg.protocolVersion());
            copyRequestMessageId(root, requestRoot);
            ObjectNode payload = JSON.createObjectNode();
            payload.put("ok", true);
            payload.put("view_index", viewIndex);
            payload.put("server_ts_ms", System.currentTimeMillis());
            root.set("payload", payload);
            conn.send(JSON.writeValueAsString(root));
            log.info("client_ws sent type=server.active_reference_view_ack view_index={}", viewIndex);
        } catch (Exception e) {
            log.warn("client_ws active_reference_view_ack send failed: {}", e.getMessage());
        }
    }

    private void sendSessionState(WebSocket conn, ClientWsSessionState state) {
        try {
            ObjectNode root = JSON.createObjectNode();
            root.put("type", "server.state");
            root.put("protocol_version", cfg.protocolVersion());
            root.put("message_id", UUID.randomUUID().toString());
            ObjectNode payload = JSON.createObjectNode();
            payload.put("session_state", state.name());
            payload.put("server_ts_ms", System.currentTimeMillis());
            root.set("payload", payload);
            conn.send(JSON.writeValueAsString(root));
            log.info("client_ws sent type=server.state session_state={}", state);
        } catch (Exception e) {
            log.warn("client_ws state send failed: {}", e.getMessage());
        }
    }

    private void sendError(WebSocket conn, String code, String message) {
        try {
            ObjectNode root = JSON.createObjectNode();
            root.put("type", "server.error");
            root.put("protocol_version", cfg.protocolVersion());
            root.put("message_id", UUID.randomUUID().toString());
            ObjectNode payload = JSON.createObjectNode();
            payload.put("code", code == null ? "error" : code);
            payload.put("message", message == null ? "" : truncate(message, 800));
            root.set("payload", payload);
            conn.send(JSON.writeValueAsString(root));
            log.info("client_ws sent type=server.error code={}", code);
        } catch (Exception e) {
            log.warn("client_ws error send failed: {}", e.getMessage());
        }
    }

    private static String truncate(String s, int max) {
        if (s == null) {
            return "";
        }
        return s.length() <= max ? s : s.substring(0, max) + "…";
    }

    private boolean pathAllowed(String resourceDescriptor) {
        String p = resourceDescriptor == null ? "" : resourceDescriptor.trim();
        if (p.isEmpty()) {
            p = "/";
        }
        int q = p.indexOf('?');
        if (q >= 0) {
            p = p.substring(0, q);
        }
        String configured = cfg.path();
        if ("/".equals(configured)) {
            return p.startsWith("/");
        }
        return configured.equals(p);
    }
}

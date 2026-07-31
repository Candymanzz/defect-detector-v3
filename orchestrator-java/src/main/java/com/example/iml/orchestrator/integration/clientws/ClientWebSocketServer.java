package com.example.iml.orchestrator.integration.clientws;

import com.example.iml.orchestrator.integration.binaryrpc.BinaryRpcSupervisor;
import com.example.iml.orchestrator.integration.clientws.config.ClientWsConfig;
import com.example.iml.orchestrator.integration.clientws.routing.WsFrontController;
import com.example.iml.orchestrator.integration.clientws.session.ClientWsReferenceContext;
import com.example.iml.orchestrator.integration.clientws.session.ClientWsSessionState;
import com.example.iml.orchestrator.integration.fanout.BucketFanOutResult;
import com.example.iml.orchestrator.integration.lighting.LightBrightnessStore;
import com.example.iml.orchestrator.integration.lighting.LightTriggerClient;
import com.example.iml.orchestrator.integration.pipeline.InspectionDecision;
import com.example.iml.orchestrator.integration.pipeline.reference.PipelineReferenceRegistry;
import com.example.iml.orchestrator.integration.pipeline.spi.CameraCaptureStage;
import com.example.iml.orchestrator.integration.plc.PlcFinsTrafficEvent;
import com.example.iml.orchestrator.integration.preview.LivePreviewGate;
import com.example.iml.orchestrator.integration.preview.PreviewWsFrame;
import com.example.iml.orchestrator.integration.stream.CameraStreamService;
import com.example.iml.orchestrator.integration.stream.ClientStreamConfig;
import org.apache.logging.log4j.Logger;
import org.java_websocket.WebSocket;
import org.java_websocket.framing.Framedata;
import org.java_websocket.handshake.ClientHandshake;
import org.java_websocket.server.WebSocketServer;

import java.net.InetSocketAddress;
import java.nio.file.Path;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;

/**
 * WebSocket-сервер UI: lifecycle + делегирование входящих сообщений {@link WsFrontController}.
 */
public final class ClientWebSocketServer extends WebSocketServer implements AutoCloseable {

    private final Logger log;
    private final ClientWsConfig cfg;
    private final ClientWsReferenceContext referenceContext = new ClientWsReferenceContext();
    private final ClientWsServerParts parts;
    private final ScheduledExecutorService pingScheduler = Executors.newSingleThreadScheduledExecutor(r -> {
        Thread t = new Thread(r, "client-ws-ping");
        t.setDaemon(true);
        return t;
    });
    private volatile CameraStreamService cameraStreamService;

    public ClientWebSocketServer(Logger log, ClientWsConfig cfg) {
        super(new InetSocketAddress(cfg.host(), cfg.port()));
        this.log = log;
        this.cfg = cfg;
        this.parts = ClientWsServerParts.create(
                log, cfg, referenceContext, svc -> cameraStreamService = svc,
                () -> cameraStreamService, this::getConnections);
        setReuseAddr(true);
        setConnectionLostTimeout(0);
    }

    public void begin() {
        pingScheduler.scheduleAtFixedRate(
                parts.lifecycle::sendProtocolPing, cfg.pingIntervalMs(), cfg.pingIntervalMs(), TimeUnit.MILLISECONDS);
        start();
    }

    public ClientWsReferenceContext referenceContext() { return referenceContext; }
    public ClientWsSessionState sessionState() { return parts.wiring.sessionState(); }
    public void setSessionState(ClientWsSessionState state) { parts.wiring.setSessionState(state); }

    /** Колбэк на смену эталона (READY/OPERATIONAL / NO_REFERENCE) — для FINS vision_ready. */
    public void setSessionStateListener(Consumer<ClientWsSessionState> listener) {
        parts.wiring.setSessionStateListener(listener);
    }

    public void setKopcheniPythonPool(List<? extends BinaryRpcSupervisor> pool) {
        parts.wiring.setKopcheniPythonPool(pool);
    }

    public void attachPipelineReferences(PipelineReferenceRegistry registry, Map<Integer, String> detectorByCamera) {
        parts.wiring.attachPipelineReferences(registry, detectorByCamera);
    }

    public void setCaptureStage(CameraCaptureStage captureStage) { parts.wiring.setCaptureStage(captureStage); }
    public void setLightTriggerClient(LightTriggerClient c) { parts.wiring.setLightTriggerClient(c); }
    public void setLightBrightnessStore(LightBrightnessStore s) { parts.wiring.setLightBrightnessStore(s); }
    public void setCameraStreamService(CameraStreamService s) { parts.wiring.setCameraStreamService(s); }
    public void setClientStreamConfig(ClientStreamConfig c) { parts.wiring.setClientStreamConfig(c); }
    public void setLivePreviewGate(LivePreviewGate g) { parts.wiring.setLivePreviewGate(g); }
    public void setReferenceCameraIds(Collection<Integer> cameraIds) { parts.wiring.setReferenceCameraIds(cameraIds); }

    /**
     * Полная остановка инспекции: сброс эталона/ROI/FP в WS, пайплайне и analisSurface,
     * сессия {@link ClientWsSessionState#NO_REFERENCE}.
     */
    public void clearReferenceSession() { parts.referenceSessionOps.clearReferenceSession(); }

    public void notifyPreviewFrame(int cameraId, String productType, String detectorId,
                                   Map<String, Object> captureHeader, String httpPath) {
        parts.notifier.notifyPreviewFrame(cameraId, productType, detectorId, captureHeader, httpPath);
    }

    public void notifyPreviewBatch(long lineSeq, long serverTsMs, List<PreviewWsFrame> frames) {
        parts.notifier.notifyPreviewBatch(lineSeq, serverTsMs, frames);
    }

    public void notifyInspectResult(
            int cameraId, String productType, String detectorId, long inspectionId, InspectionDecision decision,
            Map<String, Object> captureHeader, Path heatmapU8Path, int heatmapW, int heatmapH,
            String currentHttpPath, String heatmapArtifactTokenOrNull, boolean includeHeatmapFilePathInWs,
            String inspectionArtifactBundleId) {
        parts.notifier.notifyInspectResult(
                cameraId, productType, detectorId, inspectionId, decision, captureHeader, heatmapU8Path,
                heatmapW, heatmapH, currentHttpPath, heatmapArtifactTokenOrNull, includeHeatmapFilePathInWs,
                inspectionArtifactBundleId);
    }

    public void notifyInspectBucketResult(BucketFanOutResult result) {
        parts.notifier.notifyInspectBucketResult(result);
    }

    public void notifyPlcFinsTraffic(PlcFinsTrafficEvent event) {
        parts.notifier.notifyPlcFinsTraffic(event);
    }

    @Override public void onOpen(WebSocket conn, ClientHandshake handshake) { parts.lifecycle.onOpen(conn, handshake); }
    @Override public void onClose(WebSocket conn, int code, String reason, boolean remote) {
        parts.lifecycle.onClose(conn, code, reason, remote);
    }
    @Override public void onMessage(WebSocket conn, String message) {
        parts.lifecycle.markActivity();
        parts.inboundRouter.onMessage(conn, message);
    }
    @Override @SuppressWarnings("unused") public void onWebsocketPong(WebSocket conn, Framedata f) {
        parts.lifecycle.markActivity();
    }
    @Override @SuppressWarnings("unused") public void onError(WebSocket conn, Exception ex) {
        if (ex != null) { log.warn("client_ws error: {}", ex.toString()); }
    }
    @Override public void onStart() {
        log.info("client_ws listening ws://{}:{}{} replace_session={} read_idle_timeout_ms={} ping_interval_ms={}",
                cfg.host(), cfg.port(), cfg.path(), cfg.replaceExistingSession(),
                cfg.readIdleTimeoutMs(), cfg.pingIntervalMs());
    }

    @Override
    public void close() {
        ClientWsServerShutdown.close(pingScheduler, timeoutMs -> {
            try {
                stop(timeoutMs);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        });
    }
}

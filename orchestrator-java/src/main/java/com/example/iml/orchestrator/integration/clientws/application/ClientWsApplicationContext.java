package com.example.iml.orchestrator.integration.clientws.application;

import com.example.iml.orchestrator.integration.clientws.config.ClientWsConfig;
import com.example.iml.orchestrator.integration.clientws.outbound.WsOutboundMessenger;
import com.example.iml.orchestrator.integration.clientws.service.ClientWsKopcheniBroadcaster;
import com.example.iml.orchestrator.integration.clientws.session.ClientWsReferenceContext;
import com.example.iml.orchestrator.integration.clientws.session.ClientWsSessionState;
import com.example.iml.orchestrator.integration.lighting.LightBrightnessStore;
import com.example.iml.orchestrator.integration.lighting.LightTriggerClient;
import com.example.iml.orchestrator.integration.preview.LivePreviewGate;
import com.example.iml.orchestrator.integration.pipeline.reference.PipelineReferenceRegistry;
import com.example.iml.orchestrator.integration.pipeline.spi.CameraCaptureStage;
import com.example.iml.orchestrator.integration.stream.CameraStreamService;
import com.example.iml.orchestrator.integration.stream.ClientStreamConfig;
import org.apache.logging.log4j.Logger;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;

/**
 * Зависимости WebSocket-слоя (DI для handlers).
 */
public final class ClientWsApplicationContext {

    private final Logger log;
    private final ClientWsConfig cfg;
    private final ClientWsReferenceContext referenceContext;
    private final AtomicReference<ClientWsSessionState> sessionState;
    private final ClientWsKopcheniBroadcaster kopcheniBroadcaster;
    private final WsOutboundMessenger outbound;
    private volatile PipelineReferenceRegistry pipelineReferences;
    private volatile Map<Integer, String> detectorByCamera = Map.of();
    private volatile LightTriggerClient lightTriggerClient;
    private volatile LightBrightnessStore lightBrightnessStore;
    private volatile CameraStreamService cameraStreamService;
    private volatile ClientStreamConfig clientStreamConfig = ClientStreamConfig.defaults();
    private volatile LivePreviewGate livePreviewGate;
    private volatile CameraCaptureStage captureStage;
    private volatile List<Integer> referenceCameraIds = List.of(0, 1, 2, 3);
    private volatile Consumer<ClientWsSessionState> sessionStateListener;

    public ClientWsApplicationContext(
            Logger log,
            ClientWsConfig cfg,
            ClientWsReferenceContext referenceContext,
            AtomicReference<ClientWsSessionState> sessionState,
            ClientWsKopcheniBroadcaster kopcheniBroadcaster,
            WsOutboundMessenger outbound
    ) {
        this.log = log;
        this.cfg = cfg;
        this.referenceContext = referenceContext;
        this.sessionState = sessionState;
        this.kopcheniBroadcaster = kopcheniBroadcaster;
        this.outbound = outbound;
    }

    public Logger log() {
        return log;
    }

    public ClientWsConfig cfg() {
        return cfg;
    }

    public ClientWsReferenceContext referenceContext() {
        return referenceContext;
    }

    public ClientWsSessionState sessionState() {
        return sessionState.get();
    }

    public void setSessionState(ClientWsSessionState state) {
        ClientWsSessionState next = state == null ? ClientWsSessionState.NO_REFERENCE : state;
        sessionState.set(next);
        Consumer<ClientWsSessionState> listener = sessionStateListener;
        if (listener != null) {
            try {
                listener.accept(next);
            } catch (Exception e) {
                log.warn("client_ws session_state listener error: {}", e.getMessage());
            }
        }
    }

    public void setSessionStateListener(Consumer<ClientWsSessionState> listener) {
        this.sessionStateListener = listener;
    }

    public ClientWsKopcheniBroadcaster kopcheniBroadcaster() {
        return kopcheniBroadcaster;
    }

    public WsOutboundMessenger outbound() {
        return outbound;
    }

    public void attachPipelineReferences(PipelineReferenceRegistry registry, Map<Integer, String> detectorByCamera) {
        this.pipelineReferences = registry;
        this.detectorByCamera = detectorByCamera == null ? Map.of() : Map.copyOf(detectorByCamera);
    }

    public PipelineReferenceRegistry pipelineReferences() {
        return pipelineReferences;
    }

    public String detectorForCamera(int cameraId) {
        return detectorByCamera.getOrDefault(cameraId, "v1");
    }

    public void setLightTriggerClient(LightTriggerClient lightTriggerClient) {
        this.lightTriggerClient = lightTriggerClient;
    }

    public LightTriggerClient lightTriggerClient() {
        return lightTriggerClient;
    }

    public void setLightBrightnessStore(LightBrightnessStore lightBrightnessStore) {
        this.lightBrightnessStore = lightBrightnessStore;
    }

    public LightBrightnessStore lightBrightnessStore() {
        return lightBrightnessStore;
    }

    public void setCameraStreamService(CameraStreamService cameraStreamService) {
        this.cameraStreamService = cameraStreamService;
    }

    public CameraStreamService cameraStreamService() {
        return cameraStreamService;
    }

    public void setClientStreamConfig(ClientStreamConfig clientStreamConfig) {
        this.clientStreamConfig = clientStreamConfig == null ? ClientStreamConfig.defaults() : clientStreamConfig;
    }

    public ClientStreamConfig clientStreamConfig() {
        return clientStreamConfig;
    }

    public void setLivePreviewGate(LivePreviewGate livePreviewGate) {
        this.livePreviewGate = livePreviewGate;
    }

    public LivePreviewGate livePreviewGate() {
        return livePreviewGate;
    }

    public void setCaptureStage(CameraCaptureStage captureStage) {
        this.captureStage = captureStage;
    }

    public CameraCaptureStage captureStage() {
        return captureStage;
    }

    public void setReferenceCameraIds(Collection<Integer> cameraIds) {
        if (cameraIds == null || cameraIds.isEmpty()) {
            this.referenceCameraIds = List.of(0, 1, 2, 3);
            return;
        }
        LinkedHashSet<Integer> deduplicated = new LinkedHashSet<>();
        for (Integer cameraId : cameraIds) {
            if (cameraId != null && cameraId >= 0) {
                deduplicated.add(cameraId);
            }
        }
        if (deduplicated.isEmpty()) {
            this.referenceCameraIds = List.of(0, 1, 2, 3);
            return;
        }
        ArrayList<Integer> ordered = new ArrayList<>(deduplicated);
        Collections.sort(ordered);
        this.referenceCameraIds = List.copyOf(ordered);
    }

    public List<Integer> referenceCameraIds() {
        return referenceCameraIds;
    }
}

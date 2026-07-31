package com.example.iml.orchestrator.integration.clientws;

import com.example.iml.orchestrator.integration.binaryrpc.BinaryRpcSupervisor;
import com.example.iml.orchestrator.integration.clientws.application.ClientWsApplicationContext;
import com.example.iml.orchestrator.integration.clientws.outbound.WsOutboundMessenger;
import com.example.iml.orchestrator.integration.clientws.service.ClientWsKopcheniBroadcaster;
import com.example.iml.orchestrator.integration.clientws.session.ClientWsSessionState;
import com.example.iml.orchestrator.integration.lighting.LightBrightnessStore;
import com.example.iml.orchestrator.integration.lighting.LightTriggerClient;
import com.example.iml.orchestrator.integration.pipeline.reference.PipelineReferenceRegistry;
import com.example.iml.orchestrator.integration.pipeline.spi.CameraCaptureStage;
import com.example.iml.orchestrator.integration.preview.LivePreviewGate;
import com.example.iml.orchestrator.integration.stream.CameraStreamService;
import com.example.iml.orchestrator.integration.stream.ClientStreamConfig;

import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;

/**
 * Setter wiring for collaborators attached to {@link ClientWebSocketServer}.
 */
final class ClientWsCollaboratorWiring {

    private final ClientWsApplicationContext application;
    private final ClientWsKopcheniBroadcaster kopcheniBroadcaster;
    private final WsOutboundMessenger outbound;
    private final Consumer<CameraStreamService> cameraStreamServiceSink;

    ClientWsCollaboratorWiring(
            ClientWsApplicationContext application,
            ClientWsKopcheniBroadcaster kopcheniBroadcaster,
            WsOutboundMessenger outbound,
            Consumer<CameraStreamService> cameraStreamServiceSink
    ) {
        this.application = application;
        this.kopcheniBroadcaster = kopcheniBroadcaster;
        this.outbound = outbound;
        this.cameraStreamServiceSink = cameraStreamServiceSink;
    }

    ClientWsSessionState sessionState() {
        return application.sessionState();
    }

    void setSessionState(ClientWsSessionState state) {
        application.setSessionState(state);
    }

    void setSessionStateListener(Consumer<ClientWsSessionState> listener) {
        application.setSessionStateListener(listener);
    }

    void setKopcheniPythonPool(List<? extends BinaryRpcSupervisor> pool) {
        kopcheniBroadcaster.setPool(pool);
    }

    void attachPipelineReferences(PipelineReferenceRegistry registry, Map<Integer, String> detectorByCamera) {
        application.attachPipelineReferences(registry, detectorByCamera);
    }

    void setCaptureStage(CameraCaptureStage captureStage) {
        application.setCaptureStage(captureStage);
    }

    void setLightTriggerClient(LightTriggerClient lightTriggerClient) {
        application.setLightTriggerClient(lightTriggerClient);
    }

    void setLightBrightnessStore(LightBrightnessStore lightBrightnessStore) {
        application.setLightBrightnessStore(lightBrightnessStore);
    }

    void setCameraStreamService(CameraStreamService cameraStreamService) {
        cameraStreamServiceSink.accept(cameraStreamService);
        application.setCameraStreamService(cameraStreamService);
        if (cameraStreamService != null) {
            cameraStreamService.setOutbound(outbound);
        }
    }

    void setClientStreamConfig(ClientStreamConfig clientStreamConfig) {
        application.setClientStreamConfig(clientStreamConfig);
    }

    void setLivePreviewGate(LivePreviewGate livePreviewGate) {
        application.setLivePreviewGate(livePreviewGate);
    }

    void setReferenceCameraIds(Collection<Integer> cameraIds) {
        application.setReferenceCameraIds(cameraIds);
    }
}

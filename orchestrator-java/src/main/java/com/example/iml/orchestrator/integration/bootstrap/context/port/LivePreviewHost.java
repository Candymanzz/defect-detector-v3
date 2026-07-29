package com.example.iml.orchestrator.integration.bootstrap.context.port;

import com.example.iml.orchestrator.integration.bootstrap.config.IntegrationBootConfig;
import com.example.iml.orchestrator.integration.camera.WorkerProcessSupervisor;
import com.example.iml.orchestrator.integration.capture.LineSynchronizedCaptureCoordinator;
import com.example.iml.orchestrator.integration.clientws.ClientWebSocketServer;
import com.example.iml.orchestrator.integration.lighting.LightTriggerClient;
import com.example.iml.orchestrator.integration.pipeline.reference.PipelineReferenceRegistry;
import com.example.iml.orchestrator.integration.pipeline.session.PerCameraInspectionGate;
import com.example.iml.orchestrator.integration.preview.LivePreviewGate;
import com.example.iml.orchestrator.integration.preview.LivePreviewPublisher;
import com.example.iml.orchestrator.integration.stream.CameraStreamService;
import com.example.iml.orchestrator.integration.ui.UiHttpServer;

import java.util.List;
import java.util.Map;

/**
 * Порт LivePreviewPublisher.
 */
public interface LivePreviewHost {

    Map<String, Object> root();

    Map<String, Object> integration();

    List<Map<String, Object>> activeCameras();

    Map<Integer, WorkerProcessSupervisor> workersByCamera();

    LightTriggerClient lightClient();

    UiHttpServer uiServer();

    ClientWebSocketServer clientWsServer();

    int flashLeadMs();

    Map<String, Object> uiCfg();

    IntegrationBootConfig bootConfig();

    PipelineReferenceRegistry pipelineReferenceRegistry();

    CameraStreamService cameraStreamService();

    LivePreviewGate livePreviewGate();

    PerCameraInspectionGate inspectionGate();

    LineSynchronizedCaptureCoordinator lineCaptureCoordinator();

    void setLivePreview(LivePreviewPublisher livePreview);
}

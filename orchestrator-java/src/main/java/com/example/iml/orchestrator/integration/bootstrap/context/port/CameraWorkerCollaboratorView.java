package com.example.iml.orchestrator.integration.bootstrap.context.port;

import com.example.iml.orchestrator.integration.camera.CameraSettingsStore;
import com.example.iml.orchestrator.integration.camera.WorkerProcessSupervisor;
import com.example.iml.orchestrator.integration.clientws.ClientWebSocketServer;
import com.example.iml.orchestrator.integration.pipeline.stages.WorkerCaptureCoordinator;
import com.example.iml.orchestrator.integration.preview.LivePreviewGate;
import com.example.iml.orchestrator.integration.ui.UiHttpServer;

import java.util.List;
import java.util.Map;

/** Existing collaborators needed while starting camera workers / stream. */
public interface CameraWorkerCollaboratorView {

    CameraSettingsStore cameraSettingsStore();

    UiHttpServer uiServer();

    ClientWebSocketServer clientWsServer();

    Map<Integer, String> detectorByCamera();

    WorkerCaptureCoordinator captureCoordinator();

    LivePreviewGate livePreviewGate();

    Map<Integer, WorkerProcessSupervisor> workersByCamera();

    List<Map<String, Object>> activeCameras();
}

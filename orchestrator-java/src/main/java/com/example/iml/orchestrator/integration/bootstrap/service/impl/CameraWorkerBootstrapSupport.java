package com.example.iml.orchestrator.integration.bootstrap.service.impl;

import com.example.iml.orchestrator.integration.bootstrap.context.port.CameraWorkerCollaboratorView;
import com.example.iml.orchestrator.integration.bootstrap.context.port.CameraWorkerConfigView;
import com.example.iml.orchestrator.integration.bootstrap.context.port.CameraWorkerSink;
import com.example.iml.orchestrator.integration.camera.CameraSettingsService;
import com.example.iml.orchestrator.integration.camera.CameraSettingsStore;
import com.example.iml.orchestrator.integration.camera.CameraWorkersHolder;
import com.example.iml.orchestrator.integration.camera.WorkerProcessSupervisor;
import com.example.iml.orchestrator.integration.stream.CameraStreamService;
import com.example.iml.orchestrator.integration.stream.ClientStreamConfig;
import org.apache.logging.log4j.Logger;

import java.util.Map;
import java.util.Set;

final class CameraWorkerBootstrapSupport {
    private CameraWorkerBootstrapSupport() {
    }

    static void attachStreamService(
            Logger log, CameraWorkerConfigView config, CameraWorkerCollaboratorView collaborators, CameraWorkerSink sink
    ) {
        if (collaborators.uiServer() == null || collaborators.workersByCamera().isEmpty()) {
            return;
        }
        ClientStreamConfig clientStreamCfg = ClientStreamConfig.fromRootYaml(config.root());
        collaborators.uiServer().attachCameraWorkers(collaborators.workersByCamera());
        CameraStreamService cameraStreamService = new CameraStreamService(
                log, clientStreamCfg, collaborators.workersByCamera(), config.uiCfg());
        sink.setCameraStreamService(cameraStreamService);
        collaborators.captureCoordinator().setCameraStreamService(cameraStreamService);
        if (collaborators.clientWsServer() != null) {
            collaborators.clientWsServer().setCameraStreamService(cameraStreamService);
            collaborators.clientWsServer().setClientStreamConfig(clientStreamCfg);
            collaborators.clientWsServer().setLivePreviewGate(collaborators.livePreviewGate());
        }
        collaborators.uiServer().attachCameraStreamService(cameraStreamService);
        log.info("client_stream ready default_max_fps={} cap={}", clientStreamCfg.defaultMaxFps(), clientStreamCfg.maxFpsCap());
    }

    static void sleepWorkerStartupStagger(int delayMs) {
        if (delayMs <= 0) {
            return;
        }
        try {
            Thread.sleep(delayMs);
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
        }
    }

    static void applyPersistedCameraSettings(
            Logger log, Map<Integer, WorkerProcessSupervisor> workersByCamera,
            CameraSettingsStore cameraSettingsStore, boolean hardwareLineTrigger
    ) {
        if (cameraSettingsStore == null || workersByCamera == null || workersByCamera.isEmpty()
                || cameraSettingsStore.allSettings().isEmpty()) {
            return;
        }
        if (hardwareLineTrigger) {
            log.info("camera persisted settings: capture_trigger_mode skipped (hardware_line_trigger=true; worker keeps line0 from config.json)");
        }
        CameraWorkersHolder workersHolder = new CameraWorkersHolder();
        workersHolder.set(workersByCamera);
        Set<String> excludeKeys = hardwareLineTrigger ? Set.of("capture_trigger_mode") : Set.of();
        new CameraSettingsService(workersHolder, null, cameraSettingsStore).applyPersistedSettings(excludeKeys);
    }
}

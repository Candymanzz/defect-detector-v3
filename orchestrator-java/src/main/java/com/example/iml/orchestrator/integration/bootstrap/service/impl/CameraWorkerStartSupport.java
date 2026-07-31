package com.example.iml.orchestrator.integration.bootstrap.service.impl;

import com.example.iml.orchestrator.integration.bootstrap.context.port.CameraWorkerConfigView;
import com.example.iml.orchestrator.integration.camera.CameraSettingsService;
import com.example.iml.orchestrator.integration.camera.CameraSettingsStore;
import com.example.iml.orchestrator.integration.camera.CameraWorkersHolder;
import com.example.iml.orchestrator.integration.camera.WorkerIpcMode;
import com.example.iml.orchestrator.integration.camera.WorkerProcessSupervisor;
import com.example.iml.orchestrator.integration.config.ConfiguredCameras;
import com.example.iml.orchestrator.protocol.BinaryProtocol;
import org.apache.logging.log4j.Logger;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CompletionException;

/** Single-camera worker start + persisted settings apply for {@link CameraWorkerBootstrapImpl}. */
final class CameraWorkerStartSupport {

    private CameraWorkerStartSupport() {
    }

    record StartedWorker(int cameraId, Map<String, Object> camera, WorkerProcessSupervisor worker) {
    }

    static StartedWorker startOneWorker(
            CameraWorkerConfigView config,
            Map<String, Object> camera,
            int launchIndex,
            int staggerMs,
            Logger log
    ) {
        int cameraId = ConfiguredCameras.requireId(camera);
        sleepWorkerStartupStagger(launchIndex * Math.max(0, staggerMs));
        var cfg = config.bootConfig();
        List<String> cmd = new ArrayList<>();
        cmd.add(config.workerBin().toString());
        cmd.add(config.workerConfigPath().toString());
        cmd.add(String.valueOf(cameraId));
        if (cfg.workerIpcMode() == WorkerIpcMode.STDIO) {
            cmd.add("--binary-stdio");
        } else {
            cmd.add("--named-pipe");
            cmd.add(String.format(cfg.workerPipeTemplate(), cameraId));
        }
        String workerPipePath = String.format(cfg.workerPipeTemplate(), cameraId);
        try {
            WorkerProcessSupervisor worker = new WorkerProcessSupervisor(
                    cameraId,
                    cmd,
                    config.projectRoot(),
                    cfg.workerIpcMode(),
                    workerPipePath,
                    cfg.workerPipeConnectTimeoutMs(),
                    cfg.workerCommandTimeoutMs()
            );
            worker.start();
            BinaryProtocol.Message health = worker.health();
            log.info("worker cam={} health type={} header={}", cameraId, health.type(), health.header());
            return new StartedWorker(cameraId, camera, worker);
        } catch (Exception e) {
            throw new CompletionException(e);
        }
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
            Map<Integer, WorkerProcessSupervisor> workersByCamera,
            CameraSettingsStore cameraSettingsStore,
            boolean hardwareLineTrigger,
            Logger log
    ) {
        if (cameraSettingsStore == null || workersByCamera == null || workersByCamera.isEmpty()) {
            return;
        }
        if (cameraSettingsStore.allSettings().isEmpty()) {
            return;
        }
        if (hardwareLineTrigger) {
            log.info(
                    "camera persisted settings: capture_trigger_mode skipped (hardware_line_trigger=true; worker keeps line0 from config.json)"
            );
        }
        CameraWorkersHolder workersHolder = new CameraWorkersHolder();
        workersHolder.set(workersByCamera);
        Set<String> excludeKeys = hardwareLineTrigger ? Set.of("capture_trigger_mode") : Set.of();
        new CameraSettingsService(workersHolder, null, cameraSettingsStore).applyPersistedSettings(excludeKeys);
    }
}

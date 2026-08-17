package com.example.iml.orchestrator.integration.bootstrap.service;

import com.example.iml.orchestrator.integration.bootstrap.config.SimultaneousLineCaptureConfig;
import com.example.iml.orchestrator.integration.bootstrap.context.IntegrationRuntimeContext;
import com.example.iml.orchestrator.integration.camera.CameraSettingsService;
import com.example.iml.orchestrator.integration.camera.CameraSettingsStore;
import com.example.iml.orchestrator.integration.camera.CameraWorkersHolder;
import com.example.iml.orchestrator.integration.camera.WorkerIpcMode;
import com.example.iml.orchestrator.integration.camera.WorkerProcessSupervisor;
import com.example.iml.orchestrator.integration.config.ConfiguredCameras;
import com.example.iml.orchestrator.integration.stream.CameraStreamService;
import com.example.iml.orchestrator.integration.stream.ClientStreamConfig;
import com.example.iml.orchestrator.protocol.BinaryProtocol;
import org.apache.logging.log4j.Logger;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

/**
 * Запуск camera-worker процессов, persisted settings, client stream.
 * Процессы стартуют с коротким stagger между launch; health ждётся параллельно.
 */
public final class CameraWorkerBootstrapService {

    private final Logger log;

    public CameraWorkerBootstrapService(Logger log) {
        this.log = log;
    }

    /**
     * @return {@code false} если ни один worker не стартовал
     */
    public boolean startWorkers(IntegrationRuntimeContext ctx) {
        Map<Integer, WorkerProcessSupervisor> workersByCamera = new LinkedHashMap<>();
        List<Map<String, Object>> activeCameras = new ArrayList<>();
        var cfg = ctx.bootConfig();

        List<Map<String, Object>> cameras = ctx.cameras();
        if (cameras.isEmpty()) {
            log.error("No cameras configured; integration pipeline skipped.");
            return false;
        }

        ExecutorService boot = Executors.newFixedThreadPool(Math.min(cameras.size(), 10), r -> {
            Thread t = new Thread(r, "camera-worker-boot");
            t.setDaemon(true);
            return t;
        });
        long t0 = System.nanoTime();
        List<Future<StartedWorker>> futures = new ArrayList<>(cameras.size());
        try {
            for (int i = 0; i < cameras.size(); i++) {
                Map<String, Object> camera = cameras.get(i);
                int cameraId = ((Number) camera.get("id")).intValue();
                List<String> cmd = new ArrayList<>();
                cmd.add(ctx.workerBin().toString());
                cmd.add(ctx.workerConfigPath().toString());
                cmd.add(String.valueOf(cameraId));
                if (cfg.workerIpcMode() == WorkerIpcMode.STDIO) {
                    cmd.add("--binary-stdio");
                } else {
                    cmd.add("--named-pipe");
                    cmd.add(String.format(cfg.workerPipeTemplate(), cameraId));
                }
                String workerPipePath = String.format(cfg.workerPipeTemplate(), cameraId);
                futures.add(boot.submit(() -> startOneWorker(cameraId, camera, cmd, workerPipePath, cfg, ctx)));
                // Только пауза между launch; health уже крутится в пуле потоков.
                if (i + 1 < cameras.size()) {
                    sleepWorkerStartupStagger(cfg.workerStartupStaggerMs());
                }
            }

            for (Future<StartedWorker> future : futures) {
                try {
                    StartedWorker started = future.get();
                    if (started == null) {
                        continue;
                    }
                    workersByCamera.put(started.cameraId(), started.worker());
                    activeCameras.add(started.camera());
                } catch (Exception e) {
                    log.error("worker boot join failed: {}", e.getMessage());
                }
            }
        } finally {
            boot.shutdownNow();
        }
        log.info(
                "camera workers boot done in {} ms started={}/{} stagger_ms={}",
                TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - t0),
                workersByCamera.size(),
                cameras.size(),
                cfg.workerStartupStaggerMs()
        );

        ctx.setWorkersByCamera(workersByCamera);
        ctx.setActiveCameras(activeCameras);
        if (workersByCamera.isEmpty()) {
            log.error("No camera workers started successfully; integration pipeline skipped.");
            return false;
        }

        SimultaneousLineCaptureConfig lineCaptureCfg =
                SimultaneousLineCaptureConfig.parse(ctx.integration(), ctx.root());
        applyPersistedCameraSettings(workersByCamera, ctx.cameraSettingsStore(), lineCaptureCfg.hardwareLineTrigger());
        return true;
    }

    private StartedWorker startOneWorker(
            int cameraId,
            Map<String, Object> camera,
            List<String> cmd,
            String workerPipePath,
            com.example.iml.orchestrator.integration.bootstrap.config.IntegrationBootConfig cfg,
            IntegrationRuntimeContext ctx
    ) {
        try {
            WorkerProcessSupervisor worker = new WorkerProcessSupervisor(
                    cameraId,
                    cmd,
                    ctx.projectRoot(),
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
            log.error(
                    "worker cam={} failed to start/health; skipping this camera and continuing with others: {}",
                    cameraId,
                    e.getMessage()
            );
            log.debug("worker start failure details cam={}", cameraId, e);
            return null;
        }
    }

    private record StartedWorker(int cameraId, Map<String, Object> camera, WorkerProcessSupervisor worker) {
    }

    public void attachStreamService(IntegrationRuntimeContext ctx) {
        if (ctx.uiServer() == null || ctx.workersByCamera().isEmpty()) {
            return;
        }
        Map<Integer, String> analysisProfileByCamera = new LinkedHashMap<>();
        for (Map<String, Object> camera : ctx.activeCameras()) {
            int cameraId = ((Number) camera.get("id")).intValue();
            analysisProfileByCamera.put(cameraId, ConfiguredCameras.analysisProfileForCamera(camera, cameraId));
        }
        com.example.iml.orchestrator.integration.clientapi.AnalisSurfaceHttpBinaryRpcSupervisor.setAnalysisProfilesByCamera(
                analysisProfileByCamera
        );
        ClientStreamConfig clientStreamCfg = ClientStreamConfig.fromRootYaml(ctx.root());
        ctx.uiServer().attachCameraWorkers(ctx.workersByCamera());
        CameraStreamService cameraStreamService = new CameraStreamService(
                log,
                clientStreamCfg,
                ctx.workersByCamera(),
                analysisProfileByCamera,
                ctx.detectorByCamera(),
                ctx.uiServer(),
                ctx.clientWsServer(),
                ctx.uiCfg()
        );
        ctx.setCameraStreamService(cameraStreamService);
        ctx.captureCoordinator().setCameraStreamService(cameraStreamService);
        if (ctx.clientWsServer() != null) {
            ctx.clientWsServer().setCameraStreamService(cameraStreamService);
            ctx.clientWsServer().setClientStreamConfig(clientStreamCfg);
            ctx.clientWsServer().setLivePreviewGate(ctx.livePreviewGate());
        }
        ctx.uiServer().attachCameraStreamService(cameraStreamService);
        log.info("client_stream ready default_max_fps={} cap={}", clientStreamCfg.defaultMaxFps(), clientStreamCfg.maxFpsCap());
    }

    private static void sleepWorkerStartupStagger(int delayMs) {
        if (delayMs <= 0) {
            return;
        }
        try {
            Thread.sleep(delayMs);
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
        }
    }

    private void applyPersistedCameraSettings(
            Map<Integer, WorkerProcessSupervisor> workersByCamera,
            CameraSettingsStore cameraSettingsStore,
            boolean hardwareLineTrigger
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

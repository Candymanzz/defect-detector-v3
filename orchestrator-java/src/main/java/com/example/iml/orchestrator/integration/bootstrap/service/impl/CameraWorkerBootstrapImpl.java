package com.example.iml.orchestrator.integration.bootstrap.service.impl;

import com.example.iml.orchestrator.integration.bootstrap.service.api.CameraWorkerBootstrap;

import com.example.iml.orchestrator.integration.bootstrap.service.api.AbstractBootstrapService;

import com.example.iml.orchestrator.integration.bootstrap.config.SimultaneousLineCaptureConfig;
import com.example.iml.orchestrator.integration.bootstrap.config.SimultaneousLineCaptureConfigMapper;
import com.example.iml.orchestrator.integration.bootstrap.context.port.CameraWorkerHost;
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
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Запуск camera-worker процессов, persisted settings, client stream.
 */
public final class CameraWorkerBootstrapImpl extends AbstractBootstrapService implements CameraWorkerBootstrap {

    public CameraWorkerBootstrapImpl(Logger log) {
        super(log);
    }

    /**
     * @return {@code false} если ни один worker не стартовал
     */
    @Override
    public boolean startWorkers(CameraWorkerHost ctx) {
        var cfg = ctx.bootConfig();
        List<Map<String, Object>> cameras = ctx.cameras();
        int cameraCount = cameras.size();
        if (cameraCount == 0) {
            log.error("No cameras configured; integration pipeline skipped.");
            return false;
        }

        // Parallel start: все воркеры поднимаются одновременно (порядок камер сохраняем при сборке результата).
        // worker_startup_stagger_ms > 0 — опциональный сдвиг старта i-й камеры на i*stagger (мягкий ramp).
        ExecutorService startPool = Executors.newFixedThreadPool(
                cameraCount,
                r -> {
                    Thread t = new Thread(r, "camera-worker-start-" + START_THREAD_SEQ.incrementAndGet());
                    t.setDaemon(true);
                    return t;
                }
        );
        List<CompletableFuture<StartedWorker>> futures = new ArrayList<>(cameraCount);
        try {
            for (int i = 0; i < cameraCount; i++) {
                Map<String, Object> camera = cameras.get(i);
                int launchIndex = i;
                futures.add(CompletableFuture.supplyAsync(
                        () -> startOneWorker(ctx, camera, launchIndex, cfg.workerStartupStaggerMs()),
                        startPool
                ));
            }
            Map<Integer, WorkerProcessSupervisor> workersByCamera = new LinkedHashMap<>();
            List<Map<String, Object>> activeCameras = new ArrayList<>();
            for (int i = 0; i < futures.size(); i++) {
                StartedWorker started;
                try {
                    started = futures.get(i).join();
                } catch (CompletionException e) {
                    Throwable cause = e.getCause() != null ? e.getCause() : e;
                    int cameraId = ((Number) cameras.get(i).get("id")).intValue();
                    log.error(
                            "worker cam={} failed to start/health; skipping this camera and continuing with others: {}",
                            cameraId,
                            cause.getMessage()
                    );
                    log.debug("worker start failure details cam={}", cameraId, cause);
                    continue;
                }
                if (started == null) {
                    continue;
                }
                workersByCamera.put(started.cameraId(), started.worker());
                activeCameras.add(started.camera());
            }
            ctx.setWorkersByCamera(workersByCamera);
            ctx.setActiveCameras(activeCameras);
            if (workersByCamera.isEmpty()) {
                log.error("No camera workers started successfully; integration pipeline skipped.");
                return false;
            }
            log.info(
                    "camera workers started parallel count={}/{} stagger_ms={}",
                    workersByCamera.size(),
                    cameraCount,
                    cfg.workerStartupStaggerMs()
            );

            SimultaneousLineCaptureConfig lineCaptureCfg =
                    SimultaneousLineCaptureConfigMapper.fromYaml(ctx.integration(), ctx.root());
            applyPersistedCameraSettings(workersByCamera, ctx.cameraSettingsStore(), lineCaptureCfg.hardwareLineTrigger());
            return true;
        } finally {
            startPool.shutdownNow();
            try {
                startPool.awaitTermination(5, TimeUnit.SECONDS);
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
            }
        }
    }

    private StartedWorker startOneWorker(
            CameraWorkerHost ctx,
            Map<String, Object> camera,
            int launchIndex,
            int staggerMs
    ) {
        int cameraId = ((Number) camera.get("id")).intValue();
        sleepWorkerStartupStagger(launchIndex * Math.max(0, staggerMs));
        var cfg = ctx.bootConfig();
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
            throw new CompletionException(e);
        }
    }

    @Override
    public void attachStreamService(CameraWorkerHost ctx) {
        if (ctx.uiServer() == null || ctx.workersByCamera().isEmpty()) {
            return;
        }
        Map<Integer, String> analysisProfileByCamera = new LinkedHashMap<>();
        for (Map<String, Object> camera : ctx.activeCameras()) {
            int cameraId = ((Number) camera.get("id")).intValue();
            analysisProfileByCamera.put(cameraId, ConfiguredCameras.analysisProfileForCamera(camera, cameraId));
        }
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

    private record StartedWorker(int cameraId, Map<String, Object> camera, WorkerProcessSupervisor worker) {
    }

    private static final AtomicInteger START_THREAD_SEQ = new AtomicInteger();
}

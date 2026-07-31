package com.example.iml.orchestrator.integration.bootstrap.service.impl;

import com.example.iml.orchestrator.integration.bootstrap.service.api.CameraWorkerBootstrap;
import com.example.iml.orchestrator.integration.bootstrap.service.api.AbstractBootstrapService;
import com.example.iml.orchestrator.integration.bootstrap.config.SimultaneousLineCaptureConfig;
import com.example.iml.orchestrator.integration.bootstrap.config.SimultaneousLineCaptureConfigMapper;
import com.example.iml.orchestrator.integration.bootstrap.context.port.CameraWorkerCollaboratorView;
import com.example.iml.orchestrator.integration.bootstrap.context.port.CameraWorkerConfigView;
import com.example.iml.orchestrator.integration.bootstrap.context.port.CameraWorkerHost;
import com.example.iml.orchestrator.integration.bootstrap.context.port.CameraWorkerSink;
import com.example.iml.orchestrator.integration.camera.WorkerProcessSupervisor;
import com.example.iml.orchestrator.integration.config.ConfiguredCameras;
import com.example.iml.orchestrator.integration.stream.CameraStreamService;
import com.example.iml.orchestrator.integration.stream.ClientStreamConfig;
import org.apache.logging.log4j.Logger;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
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

    private static final AtomicInteger START_THREAD_SEQ = new AtomicInteger();

    public CameraWorkerBootstrapImpl(Logger log) {
        super(log);
    }

    /**
     * @return {@code false} если ни один worker не стартовал
     */
    @Override
    public boolean startWorkers(CameraWorkerHost ctx) {
        return startWorkers(ctx, ctx, ctx);
    }

    boolean startWorkers(
            CameraWorkerConfigView config,
            CameraWorkerCollaboratorView collaborators,
            CameraWorkerSink sink
    ) {
        var cfg = config.bootConfig();
        List<Map<String, Object>> cameras = config.cameras();
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
        List<CompletableFuture<CameraWorkerStartSupport.StartedWorker>> futures = new ArrayList<>(cameraCount);
        try {
            for (int i = 0; i < cameraCount; i++) {
                Map<String, Object> camera = cameras.get(i);
                int launchIndex = i;
                futures.add(CompletableFuture.supplyAsync(
                        () -> CameraWorkerStartSupport.startOneWorker(
                                config, camera, launchIndex, cfg.workerStartupStaggerMs(), log),
                        startPool
                ));
            }
            Map<Integer, WorkerProcessSupervisor> workersByCamera = new LinkedHashMap<>();
            List<Map<String, Object>> activeCameras = new ArrayList<>();
            for (int i = 0; i < futures.size(); i++) {
                CameraWorkerStartSupport.StartedWorker started;
                try {
                    started = futures.get(i).join();
                } catch (CompletionException e) {
                    Throwable cause = e.getCause() != null ? e.getCause() : e;
                    int cameraId = ConfiguredCameras.requireId(cameras.get(i));
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
            sink.setWorkersByCamera(workersByCamera);
            sink.setActiveCameras(activeCameras);
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
                    SimultaneousLineCaptureConfigMapper.fromYaml(config.integration(), config.root());
            CameraWorkerStartSupport.applyPersistedCameraSettings(
                    workersByCamera, collaborators.cameraSettingsStore(), lineCaptureCfg.hardwareLineTrigger(), log);
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

    @Override
    public void attachStreamService(CameraWorkerHost ctx) {
        attachStreamService(ctx, ctx, ctx);
    }

    void attachStreamService(
            CameraWorkerConfigView config,
            CameraWorkerCollaboratorView collaborators,
            CameraWorkerSink sink
    ) {
        if (collaborators.uiServer() == null || collaborators.workersByCamera().isEmpty()) {
            return;
        }
        ClientStreamConfig clientStreamCfg = ClientStreamConfig.fromRootYaml(config.root());
        collaborators.uiServer().attachCameraWorkers(collaborators.workersByCamera());
        CameraStreamService cameraStreamService = new CameraStreamService(
                log,
                clientStreamCfg,
                collaborators.workersByCamera(),
                config.uiCfg()
        );
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
}

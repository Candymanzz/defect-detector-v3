package com.example.iml.orchestrator.integration.preview;

import com.example.iml.orchestrator.integration.camera.WorkerProcessSupervisor;
import com.example.iml.orchestrator.integration.capture.LineSynchronizedCaptureCoordinator;
import com.example.iml.orchestrator.integration.clientws.ClientWebSocketServer;
import com.example.iml.orchestrator.integration.config.ConfiguredCameras;
import com.example.iml.orchestrator.integration.config.IntegrationFeatureConfig;
import com.example.iml.orchestrator.integration.config.ReferenceSource;
import com.example.iml.orchestrator.integration.diagnostics.CaptureSyncDiagnostics;
import com.example.iml.orchestrator.integration.lighting.LightTriggerClient;
import com.example.iml.orchestrator.integration.pipeline.session.PerCameraInspectionGate;
import com.example.iml.orchestrator.integration.stream.CameraStreamService;
import com.example.iml.orchestrator.integration.ui.UiHttpServer;
import org.apache.logging.log4j.Logger;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.stream.Collectors;

/**
 * Периодический capture + JPEG на ui_http + {@code server.preview_batch} по WebSocket.
 * Не зависит от эталона (инспекция geometry+python — только после {@code client.reference_bundle}).
 */
public final class LivePreviewPublisher implements AutoCloseable {
    private final Logger log;
    private final ScheduledExecutorService scheduler;
    private final List<CameraPreviewTarget> previewTargets;
    private final CaptureSyncDiagnostics syncDiag;
    private final LivePreviewRuntimeContext context;
    private final LivePreviewPerCameraTicker perCameraTicker;
    private final LivePreviewLineBatchTicker lineBatchTicker;

    private LivePreviewPublisher(
            Logger log,
            ScheduledExecutorService scheduler,
            List<CameraPreviewTarget> previewTargets,
            CaptureSyncDiagnostics syncDiag,
            LivePreviewRuntimeContext context,
            LivePreviewPerCameraTicker perCameraTicker,
            LivePreviewLineBatchTicker lineBatchTicker
    ) {
        this.log = log;
        this.scheduler = scheduler;
        this.previewTargets = List.copyOf(previewTargets);
        this.syncDiag = syncDiag;
        this.context = context;
        this.perCameraTicker = perCameraTicker;
        this.lineBatchTicker = lineBatchTicker;
    }

    public static LivePreviewPublisher start(
            Logger log,
            Map<String, Object> rootYaml,
            List<Map<String, Object>> cameras,
            Map<Integer, WorkerProcessSupervisor> workersByCamera,
            LightTriggerClient lightClient,
            UiHttpServer uiServer,
            ClientWebSocketServer clientWs,
            int flashLeadMs,
            Map<String, Object> uiCfg,
            ReferenceSource referenceSource,
            IntegrationFeatureConfig.DevAutoTriggerStubConfig devAutoStub,
            CameraStreamService cameraStreamService,
            LivePreviewGate previewGate,
            PerCameraInspectionGate inspectionGate
    ) {
        LivePreviewConfig cfg = LivePreviewConfig.fromRootYaml(rootYaml);
        if (!cfg.enabled() || uiServer == null || workersByCamera == null || workersByCamera.isEmpty()) {
            return null;
        }
        if (devAutoStub.enabled() && referenceSource != ReferenceSource.CLIENT) {
            log.info("live_preview disabled: dev_auto_trigger_stub interval_ms={} drives captures",
                    devAutoStub.intervalMs());
            return null;
        }
        ScheduledExecutorService scheduler = createScheduler(workersByCamera.size());
        List<CameraPreviewTarget> targets = createTargets(cameras, workersByCamera);
        if (targets.isEmpty()) {
            scheduler.shutdownNow();
            return null;
        }

        int intervalMs = cfg.tickIntervalMs();
        int safeFlashLeadMs = Math.max(0, flashLeadMs);
        CaptureSyncDiagnostics syncDiag =
                new CaptureSyncDiagnostics(log, "preview", Math.max(2000L, intervalMs));
        LivePreviewRuntimeContext context = new LivePreviewRuntimeContext();
        LivePreviewTickPolicy policy =
                new LivePreviewTickPolicy(previewGate, inspectionGate, cameraStreamService);
        LivePreviewJpegPublisher jpegPublisher = new LivePreviewJpegPublisher(uiServer, uiCfg);
        LivePreviewWsNotifier wsNotifier =
                new LivePreviewWsNotifier(log, clientWs, syncDiag, context.metrics);
        LivePreviewPerCameraTicker perCameraTicker = new LivePreviewPerCameraTicker(
                log, cfg, lightClient, safeFlashLeadMs, syncDiag, context, policy,
                jpegPublisher, wsNotifier);
        LivePreviewLineBatchTicker lineBatchTicker = new LivePreviewLineBatchTicker(
                log, cfg, lightClient, safeFlashLeadMs, syncDiag, context, policy,
                jpegPublisher, wsNotifier, targets);
        LivePreviewPublisher publisher = new LivePreviewPublisher(
                log, scheduler, targets, syncDiag, context, perCameraTicker, lineBatchTicker);
        publisher.initializeTargets(cfg, intervalMs);
        scheduler.scheduleAtFixedRate(publisher::tickAllGuarded, 500L, intervalMs, TimeUnit.MILLISECONDS);
        return publisher;
    }

    public void setLineCaptureCoordinator(LineSynchronizedCaptureCoordinator lineCaptureCoordinator) {
        context.lineCaptureCoordinator = lineCaptureCoordinator;
    }

    private static ScheduledExecutorService createScheduler(int cameraCount) {
        return Executors.newScheduledThreadPool(Math.max(2, cameraCount + 1), runnable -> {
            Thread thread = new Thread(runnable, "live-preview");
            thread.setDaemon(true);
            return thread;
        });
    }

    private static List<CameraPreviewTarget> createTargets(
            List<Map<String, Object>> cameras,
            Map<Integer, WorkerProcessSupervisor> workersByCamera
    ) {
        List<CameraPreviewTarget> targets = new ArrayList<>();
        for (Map<String, Object> camera : cameras) {
            int cameraId = ConfiguredCameras.requireId(camera);
            WorkerProcessSupervisor worker = workersByCamera.get(cameraId);
            if (worker != null) {
                targets.add(new CameraPreviewTarget(
                        cameraId,
                        ConfiguredCameras.analysisProfileForCamera(camera, cameraId),
                        String.valueOf(camera.getOrDefault("detector", "v1")),
                        worker));
            }
        }
        return targets;
    }

    private void initializeTargets(LivePreviewConfig cfg, int intervalMs) {
        for (CameraPreviewTarget target : previewTargets) {
            int cameraId = target.cameraId();
            context.tickInProgressByCamera.putIfAbsent(cameraId, new AtomicBoolean(false));
            context.metrics.initialize(cameraId);
            log.info(
                    "live_preview cam={} interval_ms={} flash_on_tick={} (independent of reference; inspection needs reference_bundle)",
                    cameraId, intervalMs, cfg.flashOnTick());
        }
    }

    private void tickAllGuarded() {
        if (context.closed.get() || !context.cycleInProgress.compareAndSet(false, true)) {
            return;
        }
        long lineSeq = 1_000_000_000L + context.previewLineSequence.incrementAndGet();
        LineSynchronizedCaptureCoordinator lineCapture = context.lineCaptureCoordinator;
        if (lineCapture != null && lineCapture.isEnabled()) {
            scheduler.execute(() -> {
                try {
                    lineBatchTicker.tickLineBatchGuarded(lineSeq, lineCapture);
                } finally {
                    context.cycleInProgress.set(false);
                }
            });
            return;
        }
        try {
            List<Integer> cameraIds = previewTargets.stream()
                    .map(CameraPreviewTarget::cameraId)
                    .collect(Collectors.toList());
            long round = syncDiag.beginRound(cameraIds);
            for (CameraPreviewTarget target : previewTargets) {
                scheduler.execute(() -> perCameraTicker.tickGuarded(round, target));
            }
        } finally {
            context.cycleInProgress.set(false);
        }
    }

    @Override
    public void close() {
        if (!context.closed.compareAndSet(false, true)) {
            return;
        }
        scheduler.shutdownNow();
        syncDiag.close();
        try {
            if (!scheduler.awaitTermination(2, TimeUnit.SECONDS)) {
                scheduler.shutdownNow();
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            scheduler.shutdownNow();
        }
        log.info("live_preview stopped");
    }
}

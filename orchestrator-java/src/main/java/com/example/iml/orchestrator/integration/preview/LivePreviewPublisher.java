package com.example.iml.orchestrator.integration.preview;

import com.example.iml.orchestrator.integration.camera.WorkerProcessSupervisor;
import com.example.iml.orchestrator.integration.clientws.ClientWebSocketServer;
import com.example.iml.orchestrator.integration.config.ConfiguredCameras;
import com.example.iml.orchestrator.integration.config.IntegrationFeatureConfig;
import com.example.iml.orchestrator.integration.config.ReferenceSource;
import com.example.iml.orchestrator.integration.config.YamlScalars;
import com.example.iml.orchestrator.integration.pipeline.reference.PipelineReferenceRegistry;
import com.example.iml.orchestrator.integration.pipeline.session.PerCameraInspectionGate;
import com.example.iml.orchestrator.integration.lighting.LightTriggerClient;
import com.example.iml.orchestrator.integration.stream.CameraStreamService;
import com.example.iml.orchestrator.protocol.BinaryProtocol;
import com.example.iml.orchestrator.integration.ui.UiHttpServer;
import org.apache.logging.log4j.Logger;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.ArrayList;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.LongAdder;

/**
 * Периодический capture + JPEG на ui_http + {@code server.preview_frame} по WebSocket.
 * Не зависит от эталона (инспекция geometry+python — только после {@code client.reference_bundle}).
 */
public final class LivePreviewPublisher implements AutoCloseable {

    private final Logger log;
    private final LivePreviewConfig cfg;
    private final LightTriggerClient lightClient;
    private final UiHttpServer uiServer;
    private final ClientWebSocketServer clientWs;
    private final Map<String, Object> uiCfg;
    private final int flashLeadMs;
    private final ReferenceSource referenceSource;
    private final PipelineReferenceRegistry referenceRegistry;
    private final IntegrationFeatureConfig.DevAutoTriggerStubConfig devAutoStub;
    private final ScheduledExecutorService scheduler;
    private final CameraStreamService cameraStreamService;
    private final LivePreviewGate previewGate;
    private final PerCameraInspectionGate inspectionGate;
    private final AtomicBoolean closed = new AtomicBoolean(false);
    private final AtomicBoolean cycleInProgress = new AtomicBoolean(false);
    private final ConcurrentHashMap<Integer, AtomicBoolean> tickInProgressByCamera = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<Integer, PreviewMetrics> metricsByCamera = new ConcurrentHashMap<>();
    private final List<CameraPreviewTarget> previewTargets;

    private LivePreviewPublisher(
            Logger log,
            LivePreviewConfig cfg,
            LightTriggerClient lightClient,
            UiHttpServer uiServer,
            ClientWebSocketServer clientWs,
            Map<String, Object> uiCfg,
            int flashLeadMs,
            ReferenceSource referenceSource,
            PipelineReferenceRegistry referenceRegistry,
            IntegrationFeatureConfig.DevAutoTriggerStubConfig devAutoStub,
            ScheduledExecutorService scheduler,
            CameraStreamService cameraStreamService,
            LivePreviewGate previewGate,
            PerCameraInspectionGate inspectionGate,
            List<CameraPreviewTarget> previewTargets
    ) {
        this.log = log;
        this.cfg = cfg;
        this.lightClient = lightClient;
        this.uiServer = uiServer;
        this.clientWs = clientWs;
        this.uiCfg = uiCfg == null ? Map.of() : uiCfg;
        this.flashLeadMs = Math.max(0, flashLeadMs);
        this.referenceSource = referenceSource == null ? ReferenceSource.CAMERA : referenceSource;
        this.referenceRegistry = referenceRegistry;
        this.devAutoStub = devAutoStub == null
                ? new IntegrationFeatureConfig.DevAutoTriggerStubConfig(false, 5000)
                : devAutoStub;
        this.scheduler = scheduler;
        this.cameraStreamService = cameraStreamService;
        this.previewGate = previewGate;
        this.inspectionGate = inspectionGate;
        this.previewTargets = previewTargets == null ? List.of() : List.copyOf(previewTargets);
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
            PipelineReferenceRegistry referenceRegistry,
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
            log.info(
                    "live_preview disabled: dev_auto_trigger_stub interval_ms={} drives captures",
                    devAutoStub.intervalMs()
            );
            return null;
        }
        ScheduledExecutorService scheduler = Executors.newScheduledThreadPool(
                Math.max(2, workersByCamera.size() + 1),
                r -> {
                    Thread t = new Thread(r, "live-preview");
                    t.setDaemon(true);
                    return t;
                }
        );
        List<CameraPreviewTarget> targets = new ArrayList<>();
        int intervalMs = cfg.tickIntervalMs();
        for (Map<String, Object> camera : cameras) {
            int cameraId = ((Number) camera.get("id")).intValue();
            WorkerProcessSupervisor worker = workersByCamera.get(cameraId);
            if (worker == null) {
                continue;
            }
            String productType = ConfiguredCameras.analysisProfileForCamera(camera, cameraId);
            String detectorId = String.valueOf(camera.getOrDefault("detector", "v1"));
            targets.add(new CameraPreviewTarget(cameraId, productType, detectorId, worker));
        }
        if (targets.isEmpty()) {
            scheduler.shutdownNow();
            return null;
        }
        LivePreviewPublisher publisher = new LivePreviewPublisher(
                log,
                cfg,
                lightClient,
                uiServer,
                clientWs,
                uiCfg,
                flashLeadMs,
                referenceSource,
                referenceRegistry,
                devAutoStub,
                scheduler,
                cameraStreamService,
                previewGate,
                inspectionGate,
                targets
        );
        for (CameraPreviewTarget target : targets) {
            int cameraId = target.cameraId();
            publisher.tickInProgressByCamera.putIfAbsent(cameraId, new AtomicBoolean(false));
            publisher.metricsByCamera.putIfAbsent(cameraId, new PreviewMetrics());
            publisher.log.info(
                    "live_preview cam={} interval_ms={} flash_on_tick={} (independent of reference; inspection needs reference_bundle)",
                    cameraId,
                    intervalMs,
                    cfg.flashOnTick()
            );
        }
        scheduler.scheduleAtFixedRate(
                publisher::tickAllGuarded,
                500L,
                intervalMs,
                TimeUnit.MILLISECONDS
        );
        return publisher;
    }

    private void tickAllGuarded() {
        if (closed.get()) {
            return;
        }
        if (!cycleInProgress.compareAndSet(false, true)) {
            return;
        }
        try {
            for (CameraPreviewTarget target : previewTargets) {
                scheduler.execute(
                        () -> tickGuarded(
                                target.cameraId(),
                                target.productType(),
                                target.detectorId(),
                                target.worker()
                        )
                );
            }
        } finally {
            cycleInProgress.set(false);
        }
    }

    private void tickGuarded(int cameraId, String productType, String detectorId, WorkerProcessSupervisor worker) {
        AtomicBoolean inProgress = tickInProgressByCamera.computeIfAbsent(cameraId, ignored -> new AtomicBoolean(false));
        PreviewMetrics metrics = metricsByCamera.computeIfAbsent(cameraId, ignored -> new PreviewMetrics());
        if (!inProgress.compareAndSet(false, true)) {
            metrics.droppedTicks.increment();
            metrics.maybeLog(log, cameraId);
            return;
        }
        try {
            tick(cameraId, productType, detectorId, worker, metrics);
        } finally {
            inProgress.set(false);
            metrics.maybeLog(log, cameraId);
        }
    }

    private void tick(int cameraId, String productType, String detectorId, WorkerProcessSupervisor worker, PreviewMetrics metrics) {
        if (closed.get()) {
            return;
        }
        if (previewGate != null && previewGate.isPaused()) {
            return;
        }
        // Capture reuses the camera SHM buffer, so preview must not overwrite
        // pixels while an inspection stage is still reading them.
        if (inspectionGate != null && inspectionGate.isInspectionInFlight(cameraId)) {
            metrics.droppedTicks.increment();
            return;
        }
        if (cameraStreamService != null && cameraStreamService.isStreaming(cameraId)) {
            return;
        }
        try {
            BinaryProtocol.Message capture;
            synchronized (worker) {
                final BinaryProtocol.Message[] captureHolder = new BinaryProtocol.Message[1];
                if (cfg.flashOnTick()) {
                    lightClient.runCaptureWithLighting(cameraId, -1L, "preview", flashLeadMs, () -> {
                        captureHolder[0] = worker.command(Map.of("op", "capture", "sync", true));
                    });
                    capture = captureHolder[0];
                    if (!hasUsableCaptureHeader(capture)) {
                        capture = worker.command(Map.of("op", "capture"));
                        if (log.isDebugEnabled()) {
                            log.debug("live_preview cam={} fallback capture (sync response had no usable frame header)", cameraId);
                        }
                    }
                } else {
                    capture = worker.command(Map.of("op", "capture"));
                }
            }
            if (capture == null || capture.header() == null) {
                log.warn(
                        "live_preview cam={}: capture returned {}",
                        cameraId,
                        capture == null ? "null message" : "message without header"
                );
                return;
            }
            Map<String, Object> header = capture.header();
            long frameId = YamlScalars.toLong(header.get("frame_id"), -1L);
            if (frameId < 0) {
                log.warn(
                        "live_preview cam={}: invalid frame_id in capture header: {}",
                        cameraId,
                        header.get("frame_id")
                );
                return;
            }
            String shmName = String.valueOf(header.get("shm_name"));
            int width = YamlScalars.toInt(header.get("width"), 0);
            int height = YamlScalars.toInt(header.get("height"), 0);
            int stride = YamlScalars.toInt(header.get("stride"), 0);
            if (shmName.isBlank() || width <= 0 || height <= 0) {
                log.warn(
                        "live_preview cam={}: invalid capture geometry frame={} shm='{}' width={} height={} stride={}",
                        cameraId,
                        frameId,
                        shmName,
                        width,
                        height,
                        stride
                );
                return;
            }

            long shmOffset = YamlScalars.toLong(header.get("shm_offset"), 0L);
            long encodeStarted = System.nanoTime();
            PathHolder jpeg = writePreviewJpeg(cameraId, shmName, width, height, stride, shmOffset);
            metrics.encodeNs.add(System.nanoTime() - encodeStarted);
            if (jpeg.path == null || !Files.isRegularFile(jpeg.path)) {
                if (jpeg.error != null) {
                    log.warn("live_preview cam={} frame={}: {}", cameraId, frameId, jpeg.error);
                }
                return;
            }
            uiServer.update(
                    cameraId,
                    frameId,
                    productType,
                    detectorId,
                    shmName,
                    width,
                    height,
                    jpeg.path,
                    jpeg.width,
                    jpeg.height,
                    null,
                    0,
                    0,
                    null
            );
            if (clientWs != null) {
                long wsStarted = System.nanoTime();
                clientWs.notifyPreviewFrame(
                        cameraId,
                        productType,
                        detectorId,
                        header,
                        "/api/camera/" + cameraId + "/current.jpg"
                );
                metrics.wsNs.add(System.nanoTime() - wsStarted);
                metrics.frames.increment();
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        } catch (Exception e) {
            log.debug("live_preview cam={}: {}", cameraId, e.getMessage());
        }
    }

    private PathHolder writePreviewJpeg(int cameraId, String shmName, int width, int height, int stride, long shmOffset) {
        int previewMaxW = YamlScalars.toInt(uiCfg.get("client_preview_max_width"), 0);
        int qualPct = YamlScalars.toInt(uiCfg.get("client_preview_jpeg_quality"), 58);
        qualPct = Math.min(100, Math.max(5, qualPct));
        float q = qualPct / 100f;
        UiHttpServer.ClientPreviewArtifact art = UiHttpServer.writeCurrentJpegFromBgrShm(
                shmName, width, height, stride, shmOffset, previewMaxW, q, cameraId);
        return new PathHolder(art.path(), art.width(), art.height(), art.error());
    }

    private static boolean hasUsableCaptureHeader(BinaryProtocol.Message capture) {
        if (capture == null || capture.header() == null) {
            return false;
        }
        Map<String, Object> header = capture.header();
        long frameId = YamlScalars.toLong(header.get("frame_id"), -1L);
        String shmName = String.valueOf(header.getOrDefault("shm_name", "")).trim();
        int width = YamlScalars.toInt(header.get("width"), 0);
        int height = YamlScalars.toInt(header.get("height"), 0);
        return frameId >= 0 && !shmName.isEmpty() && width > 0 && height > 0;
    }

    private record PathHolder(Path path, int width, int height, String error) {
    }

    private record CameraPreviewTarget(
            int cameraId,
            String productType,
            String detectorId,
            WorkerProcessSupervisor worker
    ) {
    }

    private static final class PreviewMetrics {
        private static final long LOG_EVERY_MS = 10_000L;

        final LongAdder frames = new LongAdder();
        final LongAdder droppedTicks = new LongAdder();
        final LongAdder encodeNs = new LongAdder();
        final LongAdder wsNs = new LongAdder();
        final AtomicLong lastLogAtMs = new AtomicLong(System.currentTimeMillis());

        void maybeLog(Logger log, int cameraId) {
            long now = System.currentTimeMillis();
            long prev = lastLogAtMs.get();
            if (now - prev < LOG_EVERY_MS) {
                return;
            }
            if (!lastLogAtMs.compareAndSet(prev, now)) {
                return;
            }
            long frameCount = frames.sumThenReset();
            long dropped = droppedTicks.sumThenReset();
            long encodeTotalNs = encodeNs.sumThenReset();
            long wsTotalNs = wsNs.sumThenReset();
            double sec = LOG_EVERY_MS / 1000.0;
            double fps = frameCount / sec;
            double avgEncodeMs = frameCount == 0 ? 0.0 : (encodeTotalNs / 1_000_000.0) / frameCount;
            double avgWsMs = frameCount == 0 ? 0.0 : (wsTotalNs / 1_000_000.0) / frameCount;
            log.info(
                    "live_preview_stats camera={} fps={} dropped_ticks={} avg_encode_ms={} avg_ws_send_ms={}",
                    cameraId,
                    String.format("%.2f", fps),
                    dropped,
                    String.format("%.2f", avgEncodeMs),
                    String.format("%.2f", avgWsMs)
            );
        }
    }

    @Override
    public void close() {
        if (!closed.compareAndSet(false, true)) {
            return;
        }
        scheduler.shutdownNow();
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

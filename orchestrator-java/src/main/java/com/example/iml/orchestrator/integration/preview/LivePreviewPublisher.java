package com.example.iml.orchestrator.integration.preview;

import com.example.iml.orchestrator.integration.capture.LineSynchronizedCaptureCoordinator;
import com.example.iml.orchestrator.integration.camera.WorkerProcessSupervisor;
import com.example.iml.orchestrator.integration.clientws.ClientWebSocketServer;
import com.example.iml.orchestrator.integration.config.ConfiguredCameras;
import com.example.iml.orchestrator.integration.config.IntegrationFeatureConfig;
import com.example.iml.orchestrator.integration.config.ReferenceSource;
import com.example.iml.orchestrator.integration.config.YamlScalars;
import com.example.iml.orchestrator.integration.diagnostics.CaptureSyncDiagnostics;
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
import java.util.LinkedHashMap;
import java.util.stream.Collectors;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;
import java.util.concurrent.atomic.LongAdder;

/**
 * Периодический capture + JPEG на ui_http + {@code server.preview_batch} по WebSocket.
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
    private final CaptureSyncDiagnostics syncDiag;
    private volatile LineSynchronizedCaptureCoordinator lineCaptureCoordinator;
    private final AtomicLong previewLineSequence = new AtomicLong(0L);

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
            List<CameraPreviewTarget> previewTargets,
            CaptureSyncDiagnostics syncDiag
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
        this.syncDiag = syncDiag;
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
                targets,
                new CaptureSyncDiagnostics(log, "preview", Math.max(2000L, intervalMs))
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

    public void setLineCaptureCoordinator(LineSynchronizedCaptureCoordinator lineCaptureCoordinator) {
        this.lineCaptureCoordinator = lineCaptureCoordinator;
    }

    private void tickAllGuarded() {
        if (closed.get()) {
            return;
        }
        if (!cycleInProgress.compareAndSet(false, true)) {
            return;
        }
        long lineSeq = 1_000_000_000L + previewLineSequence.incrementAndGet();
        LineSynchronizedCaptureCoordinator lineCapture = lineCaptureCoordinator;
        if (lineCapture != null && lineCapture.isEnabled()) {
            scheduler.execute(() -> {
                try {
                    tickLineBatchGuarded(lineSeq, lineCapture);
                } finally {
                    cycleInProgress.set(false);
                }
            });
            return;
        }
        try {
            List<Integer> cameraIds = previewTargets.stream().map(CameraPreviewTarget::cameraId).collect(Collectors.toList());
            long round = syncDiag.beginRound(cameraIds);
            for (CameraPreviewTarget target : previewTargets) {
                scheduler.execute(
                        () -> tickGuarded(
                                round,
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

    private void tickLineBatchGuarded(long lineSeq, LineSynchronizedCaptureCoordinator lineCapture) {
        if (closed.get()) {
            return;
        }
        if (previewGate != null && previewGate.isPaused()) {
            return;
        }
        if (inspectionGate != null && inspectionGate.hasAnyInspectionInFlight()) {
            for (CameraPreviewTarget target : previewTargets) {
                metricsByCamera(target.cameraId()).droppedTicks.increment();
            }
            return;
        }
        List<Integer> cameraIds = previewTargets.stream().map(CameraPreviewTarget::cameraId).collect(Collectors.toList());
        long round = syncDiag.beginRound(cameraIds);
        List<CameraPreviewTarget> activeTargets = new ArrayList<>();
        for (CameraPreviewTarget target : previewTargets) {
            int cameraId = target.cameraId();
            if (inspectionGate != null && inspectionGate.isInspectionInFlight(cameraId)) {
                syncDiag.recordCaptureSkipped(round, cameraId, "inspection_in_flight");
                metricsByCamera(cameraId).droppedTicks.increment();
                continue;
            }
            if (cameraStreamService != null && cameraStreamService.isStreaming(cameraId)) {
                syncDiag.recordCaptureSkipped(round, cameraId, "client_stream_active");
                continue;
            }
            activeTargets.add(target);
        }
        if (activeTargets.isEmpty()) {
            return;
        }
        long captureStartedNs = System.nanoTime();
        try {
            Map<Integer, WorkerProcessSupervisor> workersByCamera = new LinkedHashMap<>();
            for (CameraPreviewTarget target : activeTargets) {
                workersByCamera.put(target.cameraId(), target.worker());
            }
            Map<Integer, BinaryProtocol.Message> captured;
            try {
                captured = capturePreviewLineBatch(lineCapture, lineSeq, workersByCamera);
            } catch (Exception batchError) {
                log.warn("live_preview line batch failed: {}", batchError.getMessage());
                captured = capturePreviewSoloSequential(activeTargets);
            }
            if (captured == null || captured.isEmpty()) {
                log.warn("live_preview: no usable frames after line batch");
                return;
            }
            for (CameraPreviewTarget target : activeTargets) {
                BinaryProtocol.Message msg = captured.get(target.cameraId());
                if (!hasUsableCaptureHeader(msg)) {
                    syncDiag.recordCaptureFail(round, target.cameraId(), "missing after batch", elapsedMs(captureStartedNs));
                    continue;
                }
                Map<String, Object> header = msg.header();
                syncDiag.recordCaptureOk(
                        round,
                        target.cameraId(),
                        YamlScalars.toLong(header.get("frame_id"), -1L),
                        YamlScalars.toLong(header.get("capture_started_ns"), 0L),
                        YamlScalars.toLong(header.get("capture_latency_ns"), 0L),
                        elapsedMs(captureStartedNs)
                );
            }
            publishPreviewCaptures(round, lineSeq, activeTargets, captured);
        } catch (Exception e) {
            log.warn("live_preview tick failed: {}", e.getMessage());
        } finally {
            for (CameraPreviewTarget target : activeTargets) {
                metricsByCamera(target.cameraId()).maybeLog(log, target.cameraId());
            }
        }
    }

    private Map<Integer, BinaryProtocol.Message> capturePreviewLineBatch(
            LineSynchronizedCaptureCoordinator lineCapture,
            long lineSeq,
            Map<Integer, WorkerProcessSupervisor> workersByCamera
    ) throws Exception {
        if (cfg.flashOnTick()) {
            int flashCameraId = workersByCamera.keySet().stream().sorted().findFirst().orElse(0);
            AtomicReference<Map<Integer, BinaryProtocol.Message>> capturedHolder = new AtomicReference<>();
            lightClient.runCaptureWithLighting(flashCameraId, -1L, "preview", flashLeadMs, () -> {
                try {
                    capturedHolder.set(lineCapture.captureLineBatch(lineSeq, workersByCamera, true));
                } catch (Exception e) {
                    throw new RuntimeException(e);
                }
            });
            return capturedHolder.get();
        }
        return lineCapture.captureLineBatch(lineSeq, workersByCamera, true);
    }

    private Map<Integer, BinaryProtocol.Message> capturePreviewSoloSequential(List<CameraPreviewTarget> activeTargets) {
        Map<Integer, BinaryProtocol.Message> captured = new LinkedHashMap<>();
        for (CameraPreviewTarget target : activeTargets) {
            int cameraId = target.cameraId();
            try {
                BinaryProtocol.Message msg;
                synchronized (target.worker()) {
                    msg = target.worker().command(Map.of("op", "capture", "sync", true));
                }
                if (hasUsableCaptureHeader(msg)) {
                    captured.put(cameraId, msg);
                }
            } catch (Exception e) {
                log.warn("live_preview solo cam={}: {}", cameraId, e.getMessage());
            }
        }
        return captured;
    }

    private void publishPreviewCaptures(
            long round,
            long lineSeq,
            List<CameraPreviewTarget> activeTargets,
            Map<Integer, BinaryProtocol.Message> captured
    ) {
        boolean imagesEnabled = previewGate == null || previewGate.areImagesEnabled();
        long serverTsMs = System.currentTimeMillis();
        List<PreviewWsFrame> wsFrames = new ArrayList<>(captured.size());
        for (CameraPreviewTarget target : activeTargets) {
            int cameraId = target.cameraId();
            BinaryProtocol.Message capture = captured.get(cameraId);
            if (!hasUsableCaptureHeader(capture)) {
                continue;
            }
            PreviewMetrics metrics = metricsByCamera(cameraId);
            Map<String, Object> header = capture.header();
            long frameId = YamlScalars.toLong(header.get("frame_id"), -1L);
            String shmName = String.valueOf(header.get("shm_name"));
            int width = YamlScalars.toInt(header.get("width"), 0);
            int height = YamlScalars.toInt(header.get("height"), 0);
            int stride = YamlScalars.toInt(header.get("stride"), 0);
            long shmOffset = YamlScalars.toLong(header.get("shm_offset"), 0L);
            String httpPath = null;
            if (imagesEnabled) {
                long encodeStarted = System.nanoTime();
                PathHolder jpeg = writePreviewJpeg(cameraId, shmName, width, height, stride, shmOffset);
                metrics.encodeNs.add(System.nanoTime() - encodeStarted);
                if (jpeg.path == null || !Files.isRegularFile(jpeg.path)) {
                    if (jpeg.error != null) {
                        syncDiag.recordCaptureFail(round, cameraId, "jpeg: " + jpeg.error, 0L);
                        log.warn("live_preview cam={} frame={}: {}", cameraId, frameId, jpeg.error);
                    }
                    continue;
                }
                uiServer.update(
                        cameraId,
                        frameId,
                        target.productType(),
                        target.detectorId(),
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
                httpPath = "/api/camera/" + cameraId + "/current.jpg";
            }
            wsFrames.add(new PreviewWsFrame(cameraId, target.productType(), target.detectorId(), header, httpPath));
        }
        if (!wsFrames.isEmpty()) {
            notifyPreviewBatch(round, lineSeq, serverTsMs, wsFrames);
        }
    }

    private PreviewMetrics metricsByCamera(int cameraId) {
        return metricsByCamera.computeIfAbsent(cameraId, ignored -> new PreviewMetrics());
    }

    private void notifyPreviewBatch(long round, long lineSeq, long serverTsMs, List<PreviewWsFrame> frames) {
        if (clientWs == null || frames.isEmpty()) {
            return;
        }
        long wsStarted = System.nanoTime();
        clientWs.notifyPreviewBatch(lineSeq, serverTsMs, frames);
        long wsNs = System.nanoTime() - wsStarted;
        long perCameraWsNs = wsNs / Math.max(1, frames.size());
        for (PreviewWsFrame frame : frames) {
            PreviewMetrics metrics = metricsByCamera(frame.cameraId());
            metrics.wsNs.add(perCameraWsNs);
            metrics.frames.increment();
            long frameId = YamlScalars.toLong(frame.captureHeader().get("frame_id"), -1L);
            syncDiag.recordWsSend(round, frame.cameraId(), frameId);
        }
        log.info("live_preview batch published line_seq={} cameras={}", lineSeq, frames.size());
    }

    private void tickGuarded(
            long round,
            int cameraId,
            String productType,
            String detectorId,
            WorkerProcessSupervisor worker
    ) {
        AtomicBoolean inProgress = tickInProgressByCamera.computeIfAbsent(cameraId, ignored -> new AtomicBoolean(false));
        PreviewMetrics metrics = metricsByCamera.computeIfAbsent(cameraId, ignored -> new PreviewMetrics());
        if (!inProgress.compareAndSet(false, true)) {
            metrics.droppedTicks.increment();
            metrics.maybeLog(log, cameraId);
            return;
        }
        try {
            tick(round, cameraId, productType, detectorId, worker, metrics);
        } finally {
            inProgress.set(false);
            metrics.maybeLog(log, cameraId);
        }
    }

    private void tick(
            long round,
            int cameraId,
            String productType,
            String detectorId,
            WorkerProcessSupervisor worker,
            PreviewMetrics metrics
    ) {
        if (closed.get()) {
            return;
        }
        if (previewGate != null && previewGate.isPaused()) {
            syncDiag.recordCaptureSkipped(round, cameraId, "preview_paused");
            return;
        }
        // Capture reuses the camera SHM buffer, so preview must not overwrite
        // pixels while an inspection stage is still reading them.
        if (inspectionGate != null && (inspectionGate.isInspectionInFlight(cameraId)
                || inspectionGate.hasAnyInspectionInFlight())) {
            metrics.droppedTicks.increment();
            syncDiag.recordCaptureSkipped(round, cameraId, "inspection_in_flight");
            return;
        }
        if (cameraStreamService != null && cameraStreamService.isStreaming(cameraId)) {
            syncDiag.recordCaptureSkipped(round, cameraId, "client_stream_active");
            return;
        }
        long captureStartedNs = System.nanoTime();
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
                syncDiag.recordCaptureFail(
                        round,
                        cameraId,
                        capture == null ? "null message" : "message without header",
                        elapsedMs(captureStartedNs)
                );
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
                syncDiag.recordCaptureFail(round, cameraId, "invalid frame_id: " + header.get("frame_id"), elapsedMs(captureStartedNs));
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
                syncDiag.recordCaptureFail(
                        round,
                        cameraId,
                        "invalid geometry shm=" + shmName + " w=" + width + " h=" + height,
                        elapsedMs(captureStartedNs)
                );
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

            long orchMs = elapsedMs(captureStartedNs);
            syncDiag.recordCaptureOk(
                    round,
                    cameraId,
                    frameId,
                    YamlScalars.toLong(header.get("capture_started_ns"), 0L),
                    YamlScalars.toLong(header.get("capture_latency_ns"), 0L),
                    orchMs
            );

            long shmOffset = YamlScalars.toLong(header.get("shm_offset"), 0L);
            if (previewGate != null && !previewGate.areImagesEnabled()) {
                notifyPreviewFrame(round, cameraId, productType, detectorId, header, null, metrics, frameId);
                return;
            }

            long encodeStarted = System.nanoTime();
            PathHolder jpeg = writePreviewJpeg(cameraId, shmName, width, height, stride, shmOffset);
            metrics.encodeNs.add(System.nanoTime() - encodeStarted);
            if (jpeg.path == null || !Files.isRegularFile(jpeg.path)) {
                if (jpeg.error != null) {
                    syncDiag.recordCaptureFail(round, cameraId, "jpeg: " + jpeg.error, elapsedMs(captureStartedNs));
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
            notifyPreviewFrame(
                    round,
                    cameraId,
                    productType,
                    detectorId,
                    header,
                    "/api/camera/" + cameraId + "/current.jpg",
                    metrics,
                    frameId
            );
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            syncDiag.recordCaptureFail(round, cameraId, "interrupted", elapsedMs(captureStartedNs));
        } catch (Exception e) {
            syncDiag.recordCaptureFail(round, cameraId, e.getMessage(), elapsedMs(captureStartedNs));
            log.debug("live_preview cam={}: {}", cameraId, e.getMessage());
        }
    }

    private static long elapsedMs(long startedNs) {
        return (System.nanoTime() - startedNs) / 1_000_000L;
    }

    private void notifyPreviewFrame(
            long round,
            int cameraId,
            String productType,
            String detectorId,
            Map<String, Object> header,
            String httpPath,
            PreviewMetrics metrics,
            long frameId
    ) {
        if (clientWs == null) {
            return;
        }
        long wsStarted = System.nanoTime();
        clientWs.notifyPreviewFrame(cameraId, productType, detectorId, header, httpPath);
        metrics.wsNs.add(System.nanoTime() - wsStarted);
        metrics.frames.increment();
        syncDiag.recordWsSend(round, cameraId, frameId);
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

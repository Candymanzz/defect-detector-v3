package com.example.iml.orchestrator.integration.ui;

import com.example.iml.orchestrator.integration.clientapi.ClientApiMount;
import com.example.iml.orchestrator.integration.clientapi.GeometryRuntimeConfig;
import com.example.iml.orchestrator.integration.clientws.ClientWebSocketServer;
import com.example.iml.orchestrator.integration.lighting.LightTriggerClient;
import com.example.iml.orchestrator.integration.camera.CameraSettingsStore;
import com.example.iml.orchestrator.integration.lighting.LightBrightnessStore;
import com.example.iml.orchestrator.integration.capture.FrameJpegWriter;
import com.example.iml.orchestrator.integration.capture.ImlShmJanitor;
import com.example.iml.orchestrator.integration.capture.LineFramePinService;
import com.example.iml.orchestrator.integration.config.CameraAnalysisProfiles;
import com.example.iml.orchestrator.integration.config.YamlScalars;
import com.example.iml.orchestrator.integration.pipeline.BinaryInspectHeaders;
import com.example.iml.orchestrator.integration.pipeline.InspectionDecision;
import com.example.iml.orchestrator.integration.pipeline.ReferenceSnapshot;
import com.example.iml.orchestrator.integration.pipeline.spi.AfterInspectionSidecar;
import com.example.iml.orchestrator.integration.pipeline.stages.InspectPositioningExecutor;
import com.example.iml.orchestrator.integration.binaryrpc.BinaryRpcSupervisor;
import com.example.iml.orchestrator.protocol.BinaryProtocol;
import org.apache.logging.log4j.Logger;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.channels.FileChannel;
import java.nio.file.StandardOpenOption;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Асинхронная подготовка UI-артефактов из результата основной инспекции.
 */
public final class UiArtifactsSidecar implements AfterInspectionSidecar {

    /**
     * Путь и размеры heatmap после ответа {@code inspect_shm} (поля заголовка задаёт Python).
     */
    private record HeatmapArtifact(Path path, int width, int height) {
        private static HeatmapArtifact empty() {
            return new HeatmapArtifact(null, 0, 0);
        }
    }

    private record FrozenFrame(Path path, String shmName, boolean deleteWhenDone) {
    }

    private record UiPublishTask(int cameraId, Runnable delegate, Runnable cleanup) implements Runnable {
        @Override
        public void run() {
            delegate.run();
        }

        private void discard() {
            cleanup.run();
        }
    }

    private final Logger log;
    private volatile ClientWebSocketServer clientWebSocketServer;
    private volatile FrameArchiveService frameArchiveService;
    private volatile GeometryRuntimeConfig geometryRuntimeConfig;
    private volatile Map<String, Object> pythonCfg;
    private final java.util.concurrent.atomic.LongAdder droppedUiPublishTasks = new java.util.concurrent.atomic.LongAdder();
    private final AtomicLong uiPublishSequence = new AtomicLong();
    private final ConcurrentHashMap<Integer, Long> latestUiPublishByCamera = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<Integer, Object> uiPublishLockByCamera = new ConcurrentHashMap<>();

    public UiArtifactsSidecar(Logger log) {
        this.log = log;
    }

    /**
     * Push {@code server.inspect_result} после инспекции (Фаза 4): даже при {@code ui_http.enabled: false} для части путей.
     */
    public void setClientWebSocketServer(ClientWebSocketServer clientWebSocketServer) {
        this.clientWebSocketServer = clientWebSocketServer;
    }

    public void setFrameArchiveService(FrameArchiveService frameArchiveService) {
        this.frameArchiveService = frameArchiveService;
    }

    /**
     * Тот же geometry-runtime / python YAML, что у вердиктного inspect — иначе heatmap пересчитывается
     * без threshold/ROI overrides и «скачет» относительно решения.
     */
    public void setPythonHeatmapContext(GeometryRuntimeConfig geometryRuntimeConfig, Map<String, Object> pythonCfg) {
        this.geometryRuntimeConfig = geometryRuntimeConfig;
        this.pythonCfg = pythonCfg;
    }

    public UiHttpServer startHttpServerIfEnabled(
            Map<String, Object> uiCfg,
            GeometrySnapshotCache geometrySnapshotCache,
            ClientApiMount clientApiMount,
            LightTriggerClient lightClient,
            Map<String, Object> rootYaml,
            CameraSettingsStore cameraSettingsStore,
            LightBrightnessStore lightBrightnessStore,
            FrameArchiveService frameArchiveService
    ) {
        boolean enabled = YamlScalars.toBool(uiCfg == null ? null : uiCfg.get("enabled"), false);
        if (!enabled) {
            return null;
        }
        String host = String.valueOf(uiCfg.getOrDefault("host", "127.0.0.1"));
        int port = YamlScalars.toInt(uiCfg.get("port"), 8099);
        try {
            UiHttpServer server = new UiHttpServer(
                    host,
                    port,
                    geometrySnapshotCache,
                    clientApiMount == null ? ClientApiMount.disabled() : clientApiMount,
                    lightClient,
                    rootYaml == null ? Map.of() : rootYaml,
                    cameraSettingsStore,
                    lightBrightnessStore,
                    frameArchiveService
            );
            log.info("ui http started on {}:{} (front controller)", host, port);
            return server;
        } catch (Exception e) {
            log.warn("ui http failed to start: {}", e.getMessage());
            return null;
        }
    }

    /**
     * Heatmap/visuals через тот же FastAPI-пул, что и пайплайн ({@code POST /inspect-shm-visuals}).
     */
    public BinaryRpcSupervisor resolveVisualsDetector(Map<String, Object> uiCfg, BinaryRpcSupervisor pythonHttp) {
        boolean uiEnabled = YamlScalars.toBool(uiCfg == null ? null : uiCfg.get("enabled"), false);
        boolean visualsAsyncEnabled = YamlScalars.toBool(uiCfg == null ? null : uiCfg.get("visuals_async_enabled"), false);
        boolean storeHeatmapU8 = YamlScalars.toBool(uiCfg == null ? null : uiCfg.get("store_heatmap_u8"), true);
        // Keep visuals RPC enabled whenever heatmap storage is requested, even if visuals_async_enabled
        // was accidentally disabled in YAML. This prevents silent heatmap loss after config drift.
        boolean enabled = uiEnabled && (visualsAsyncEnabled || storeHeatmapU8);
        if (!enabled || pythonHttp == null) {
            return null;
        }
        log.info(
                "ui visuals use analisSurface HTTP ({}) async_enabled={} store_heatmap_u8={}",
                pythonHttp.supervisorLabel(),
                visualsAsyncEnabled,
                storeHeatmapU8
        );
        return pythonHttp;
    }

    /**
     * Пул фоновой публикации артефактов в {@link UiHttpServer}: при {@code ui_http.enabled} и сохранении
     * JPEG и/или heatmap. Не требует {@code visuals_async_enabled} — без второго Python-процесса
     * публикуется превью JPEG из SHM; heatmap — только если задан отдельный процесс visuals.
     */
    public ExecutorService startUiPublishExecutorIfEnabled(Map<String, Object> uiCfg) {
        boolean enabled = YamlScalars.toBool(uiCfg == null ? null : uiCfg.get("enabled"), false);
        if (!enabled) {
            return null;
        }
        boolean storeCurrent = YamlScalars.toBool(uiCfg == null ? null : uiCfg.get("store_current_jpeg"), true);
        boolean storeHeatmapU8 = YamlScalars.toBool(uiCfg == null ? null : uiCfg.get("store_heatmap_u8"), true);
        if (!storeCurrent && !storeHeatmapU8) {
            return null;
        }
        int q = Math.max(1, YamlScalars.toInt(uiCfg == null ? null : uiCfg.get("visuals_queue_size"), 8));
        int parallelism = Math.max(
                1,
                YamlScalars.toInt(uiCfg == null ? null : uiCfg.get("visuals_parallelism"), 2)
        );
        ThreadPoolExecutor executor = new ThreadPoolExecutor(
                parallelism,
                parallelism,
                30L,
                TimeUnit.SECONDS,
                new java.util.concurrent.ArrayBlockingQueue<>(q),
                r -> {
                    Thread t = new Thread(r, "ui-publish");
                    t.setDaemon(true);
                    return t;
                },
                new ThreadPoolExecutor.AbortPolicy()
        );
        executor.allowCoreThreadTimeOut(false);
        log.info("ui artifact publisher started parallelism={} queue_size={}", parallelism, q);
        return executor;
    }

    @Override
    public void scheduleAfterInspection(
            UiHttpServer uiServer,
            Map<String, Object> uiCfg,
            BinaryRpcSupervisor uiVisualsPython,
            ExecutorService uiArtifactsExecutor,
            int cameraId,
            String productType,
            String detectorId,
            long inspectionId,
            ReferenceSnapshot activeReference,
            InspectionDecision decision,
            BinaryProtocol.Message capture,
            BinaryProtocol.Message pyResp,
            BinaryProtocol.Message geometry
    ) {
        if (capture == null) {
            return;
        }
        Map<String, Object> cap = new LinkedHashMap<>(capture.header());
        // Prefer positioned buffer for UI JPEG / cards (analysis already remapped shm_name).
        String previewShm = resolveUiPreviewShmName(cap, cameraId);
        if (previewShm != null) {
            cap.put("shm_name", previewShm);
            cap.put("shm_offset", 0L);
        }
        ClientWebSocketServer ws = clientWebSocketServer;
        String shmName = String.valueOf(cap.get("shm_name"));
        long frameId = YamlScalars.toLong(cap.get("frame_id"), -1L);
        int width = YamlScalars.toInt(cap.get("width"), 1224);
        int height = YamlScalars.toInt(cap.get("height"), 1024);
        int stride = YamlScalars.toInt(cap.get("stride"), width * 3);
        HeatmapArtifact resolvedSourceHeatmap = resolveHeatmapArtifact(
                pyResp == null ? null : pyResp.header(),
                null,
                width,
                height
        );
        if (uiServer == null || uiArtifactsExecutor == null) {
            if (ws != null) {
                try {
                    ws.notifyInspectResult(cameraId, productType, detectorId, inspectionId, decision, cap, null, 0, 0, null, null, false, null);
                } catch (Exception e) {
                    log.debug("client_ws inspect_result (no ui pool) cam={}: {}", cameraId, e.getMessage());
                }
            }
            deleteTemporaryArtifact(resolvedSourceHeatmap.path(), "unused source heatmap");
            LineFramePinService.releasePinnedCapture(capture.header());
            return;
        }
        boolean storeCurrent = YamlScalars.toBool(uiCfg == null ? null : uiCfg.get("store_current_jpeg"), true);
        boolean storeHeatmapU8 = YamlScalars.toBool(uiCfg == null ? null : uiCfg.get("store_heatmap_u8"), true);
        if (!storeCurrent && !storeHeatmapU8) {
            if (ws != null) {
                try {
                    ws.notifyInspectResult(cameraId, productType, detectorId, inspectionId, decision, cap, null, 0, 0, null, null, false, null);
                } catch (Exception e) {
                    log.debug("client_ws inspect_result (no store flags) cam={}: {}", cameraId, e.getMessage());
                }
            }
            deleteTemporaryArtifact(resolvedSourceHeatmap.path(), "disabled source heatmap");
            LineFramePinService.releasePinnedCapture(capture.header());
            return;
        }

        final HeatmapArtifact sourceHeatmap;
        if (!storeHeatmapU8) {
            deleteTemporaryArtifact(resolvedSourceHeatmap.path(), "disabled source heatmap");
            sourceHeatmap = HeatmapArtifact.empty();
        } else {
            sourceHeatmap = resolvedSourceHeatmap;
        }

        if (ws != null) {
            try {
                // Deliver decision immediately; heavy UI artifacts are published in a later update.
                ws.notifyInspectResult(cameraId, productType, detectorId, inspectionId, decision, cap, null, 0, 0, null, null, false, null);
            } catch (Exception e) {
                log.debug("client_ws inspect_result immediate cam={}: {}", cameraId, e.getMessage());
            }
        }

        long publishSequence = uiPublishSequence.incrementAndGet();
        latestUiPublishByCamera.put(cameraId, publishSequence);
        final FrozenFrame frozenFrame;
        try {
            frozenFrame = freezeInspectionFrame(cameraId, frameId, shmName, width, height, stride, cap);
        } catch (IOException e) {
            deleteTemporaryArtifact(sourceHeatmap.path(), "failed source heatmap");
            LineFramePinService.releasePinnedCapture(capture.header());
            log.warn(
                    "inspection frame freeze failed camera_id={} frame_id={}: {}",
                    cameraId,
                    frameId,
                    e.getMessage()
            );
            return;
        }
        // Freeze no longer retains line-pin paths; free per-cycle SHM asap.
        LineFramePinService.releasePinnedCapture(capture.header());
        if (!isLatestPublish(cameraId, publishSequence)) {
            deleteTemporaryArtifact(sourceHeatmap.path(), "stale source heatmap");
            deleteFrozenFrameIfOwned(frozenFrame, "stale frozen inspection frame");
            return;
        }

        UiPublishTask publishTask = new UiPublishTask(cameraId, () -> {
            Object cameraPublishLock = uiPublishLockByCamera.computeIfAbsent(cameraId, ignored -> new Object());
            synchronized (cameraPublishLock) {
                Path generatedHeatmapPreview = null;
                Path currentJpeg = null;
                Path temporaryCurrentJpeg = null;
                Path cardJpeg = null;
                Path temporaryCardJpeg = null;
                try {
                    String artifactShmName = frozenFrame.shmName();
                    int currentJpegW = 0;
                    int currentJpegH = 0;
                    if (storeCurrent) {
                        int previewMaxW = YamlScalars.toInt(
                                uiCfg == null ? null : uiCfg.get("inspection_preview_max_width"),
                                YamlScalars.toInt(uiCfg == null ? null : uiCfg.get("client_preview_max_width"), 1280)
                        );
                        int qualPct = YamlScalars.toInt(
                                uiCfg == null ? null : uiCfg.get("inspection_preview_jpeg_quality"),
                                YamlScalars.toInt(uiCfg == null ? null : uiCfg.get("client_preview_jpeg_quality"), 45)
                        );
                        qualPct = Math.min(100, Math.max(5, qualPct));
                        int cardPreviewMaxW = YamlScalars.toInt(
                                uiCfg == null ? null : uiCfg.get("inspection_card_preview_max_width"),
                                384
                        );
                        int cardQualPct = YamlScalars.toInt(
                                uiCfg == null ? null : uiCfg.get("inspection_card_preview_jpeg_quality"),
                                30
                        );
                        cardQualPct = Math.min(100, Math.max(5, cardQualPct));

                        UiHttpServer.InspectionPreviewArtifacts previews =
                                UiHttpServer.writeInspectionJpegsFromBgrShm(
                                        artifactShmName,
                                        width,
                                        height,
                                        stride,
                                        0L,
                                        previewMaxW,
                                        qualPct / 100f,
                                        cardPreviewMaxW,
                                        cardQualPct / 100f
                                );
                        UiHttpServer.ClientPreviewArtifact frameArtifact = previews.frame();
                        if (frameArtifact.path() == null && frameArtifact.error() != null) {
                            log.warn("ui sidecar cam={} preview jpeg: {}", cameraId, frameArtifact.error());
                        }
                        currentJpeg = frameArtifact.path();
                        currentJpegW = frameArtifact.width();
                        currentJpegH = frameArtifact.height();
                        temporaryCurrentJpeg = currentJpeg;

                        UiHttpServer.ClientPreviewArtifact cardArtifact = previews.card();
                        if (cardArtifact.path() == null && cardArtifact.error() != null) {
                            log.debug("ui sidecar cam={} card jpeg: {}", cameraId, cardArtifact.error());
                        }
                        cardJpeg = cardArtifact.path();
                        temporaryCardJpeg = cardJpeg;
                    }

                    CameraPreviewStore.RegisteredInspectionArtifacts registeredArtifacts = null;
                    String bundleId = null;
                    boolean hasCur =
                            currentJpeg != null && currentJpegW > 0 && currentJpegH > 0 && Files.isRegularFile(currentJpeg);
                    if (hasCur) {
                        try {
                            registeredArtifacts = uiServer.registerInspectionArtifacts(
                                    cameraId,
                                    frameId,
                                    currentJpeg,
                                    cardJpeg,
                                    null
                            );
                            bundleId = registeredArtifacts.bundleId();
                            currentJpeg = registeredArtifacts.frameJpeg();
                            cardJpeg = registeredArtifacts.cardJpeg();
                            hasCur = currentJpeg != null
                                    && currentJpegW > 0
                                    && currentJpegH > 0
                                    && Files.isRegularFile(currentJpeg);
                        } catch (IOException e) {
                            log.warn(
                                    "inspection artifact frame bundle failed camera_id={} frame_id={}: {}",
                                    cameraId,
                                    frameId,
                                    e.getMessage()
                            );
                        }
                    }

                    if (hasCur) {
                        uiServer.update(
                                cameraId,
                                frameId,
                                productType,
                                detectorId,
                                shmName,
                                width,
                                height,
                                currentJpeg,
                                currentJpegW,
                                currentJpegH,
                                null,
                                0,
                                0,
                                decision
                        );
                        if (ws != null) {
                            try {
                                String frameHttpPath = resolveInspectionFrameHttpPath(cameraId, bundleId, hasCur);
                                ws.notifyInspectResult(
                                        cameraId,
                                        productType,
                                        detectorId,
                                        inspectionId,
                                        decision,
                                        cap,
                                        null,
                                        0,
                                        0,
                                        frameHttpPath,
                                        null,
                                        false,
                                        bundleId
                                );
                                if (activeReference == null || activeReference.header() == null) {
                                    ws.notifyPreviewFrame(cameraId, productType, detectorId, cap, frameHttpPath);
                                }
                            } catch (Exception e) {
                                log.debug("client_ws inspect_result frame-ready cam={}: {}", cameraId, e.getMessage());
                            }
                        }
                    }

                    // A newer inspection may arrive while this task is encoding the JPEG.
                    // Keep the frame-ready publication above, but avoid spending detector/CPU
                    // capacity on a heatmap that the UI will immediately replace.
                    // Archive the frame JPEG immediately so a superseded publish still persists history.
                    // test-analyze must never rewrite the rolling archive slot used to pin the source frame.
                    boolean testAnalyze = YamlScalars.toBool(cap.get("test_analyze"), false);
                    if (!isLatestPublish(cameraId, publishSequence)) {
                        if (!testAnalyze) {
                            saveFrameArchiveImmediately(
                                    cameraId,
                                    frameId,
                                    inspectionId,
                                    productType,
                                    detectorId,
                                    decision,
                                    hasCur ? currentJpeg : null,
                                    null,
                                    0,
                                    0
                            );
                        }
                        return;
                    }

                    HeatmapArtifact heatmapSource = sourceHeatmap;
                    if (storeHeatmapU8
                            && (heatmapSource.path() == null || heatmapSource.width() <= 0 || heatmapSource.height() <= 0)) {
                        heatmapSource = generateHeatmapArtifact(
                                uiVisualsPython,
                                activeReference,
                                geometry,
                                uiCfg,
                                cameraId,
                                frameId,
                                productType,
                                detectorId,
                                frozenFrame,
                                width,
                                height,
                                stride
                        );
                    }
                    Path heatmapU8 = heatmapSource.path();
                    int uw = heatmapSource.width();
                    int uh = heatmapSource.height();
                    int heatmapPreviewMaxWidth = Math.max(
                            0,
                            YamlScalars.toInt(uiCfg == null ? null : uiCfg.get("heatmap_preview_max_width"), 512)
                    );
                    if (heatmapU8 != null && uw > 0 && uh > 0 && heatmapPreviewMaxWidth > 0) {
                        try {
                            HeatmapU8PreviewScaler.ScaledHeatmap preview = HeatmapU8PreviewScaler.scale(
                                    heatmapU8,
                                    uw,
                                    uh,
                                    heatmapPreviewMaxWidth
                            );
                            if (!preview.path().equals(heatmapU8)) {
                                generatedHeatmapPreview = preview.path();
                            }
                            heatmapU8 = preview.path();
                            uw = preview.width();
                            uh = preview.height();
                        } catch (IOException e) {
                            log.warn(
                                    "ui heatmap preview scale failed cam={} frame={} size={}x{} max_width={}: {}",
                                    cameraId,
                                    frameId,
                                    uw,
                                    uh,
                                    heatmapPreviewMaxWidth,
                                    e.getMessage()
                            );
                        }
                    }

                    boolean hasHm = heatmapU8 != null && uw > 0 && uh > 0 && Files.isRegularFile(heatmapU8);
                    if (bundleId != null && hasHm) {
                        try {
                            registeredArtifacts = uiServer.attachInspectionHeatmap(bundleId, heatmapU8);
                            heatmapU8 = registeredArtifacts.heatmapU8();
                            hasHm = heatmapU8 != null
                                    && uw > 0
                                    && uh > 0
                                    && Files.isRegularFile(heatmapU8);
                        } catch (IOException e) {
                            log.warn(
                                    "inspection artifact heatmap attach failed camera_id={} frame_id={} bundle_id={}: {}",
                                    cameraId,
                                    frameId,
                                    bundleId,
                                    e.getMessage()
                            );
                        }
                    }

                    if (hasCur || hasHm) {
                        uiServer.update(
                                cameraId,
                                frameId,
                                productType,
                                detectorId,
                                shmName,
                                width,
                                height,
                                hasCur ? currentJpeg : null,
                                hasCur ? currentJpegW : 0,
                                hasCur ? currentJpegH : 0,
                                hasHm ? heatmapU8 : null,
                                hasHm ? uw : 0,
                                hasHm ? uh : 0,
                                decision
                        );
                    }
                    // Snapshot/copy while JPEG and heatmap files are still on disk (before finally).
                    FrameArchiveService archive = frameArchiveService;
                    boolean archived = !testAnalyze && saveFrameArchiveImmediately(
                            cameraId,
                            frameId,
                            inspectionId,
                            productType,
                            detectorId,
                            decision,
                            hasCur ? currentJpeg : null,
                            hasHm ? heatmapU8 : null,
                            hasHm ? uw : 0,
                            hasHm ? uh : 0
                    );
                    if (ws != null && (hasCur || hasHm)) {
                        try {
                            // test-analyze must keep live artifact URLs so the UI can show the freshly
                            // generated heatmap instead of the immutable archive copy for this frame.
                            String frameHttpPath = !testAnalyze && archived && archive != null
                                    ? archive.frameArtifactHttpPath(cameraId, frameId, "frame.jpg")
                                    : resolveInspectionFrameHttpPath(cameraId, bundleId, hasCur);
                            String heatmapArtifactToken = bundleId == null && hasHm
                                    ? uiServer.registerHeatmapArtifact(cameraId, heatmapU8)
                                    : null;
                            // Keep bundleId so live heatmap still resolves; frame http_path prefers archive.
                            ws.notifyInspectResult(
                                    cameraId,
                                    productType,
                                    detectorId,
                                    inspectionId,
                                    decision,
                                    cap,
                                    hasHm ? heatmapU8 : null,
                                    hasHm ? uw : 0,
                                    hasHm ? uh : 0,
                                    frameHttpPath,
                                    heatmapArtifactToken,
                                    false,
                                    bundleId
                            );
                        } catch (Exception e) {
                            log.debug("client_ws inspect_result cam={}: {}", cameraId, e.getMessage());
                        }
                    }
                } catch (Exception e) {
                    log.warn(
                            "ui artifact publish failed camera_id={} frame_id={}: {}",
                            cameraId,
                            frameId,
                            e.getMessage()
                    );
                } finally {
                    if (temporaryCurrentJpeg != null && !temporaryCurrentJpeg.equals(currentJpeg)) {
                        deleteTemporaryArtifact(temporaryCurrentJpeg, "temporary inspection jpeg");
                    }
                    if (temporaryCardJpeg != null) {
                        deleteTemporaryArtifact(temporaryCardJpeg, "temporary inspection card jpeg");
                    }
                    deleteTemporaryArtifact(sourceHeatmap.path(), "source heatmap");
                    deleteTemporaryArtifact(generatedHeatmapPreview, "scaled heatmap");
                    deleteFrozenFrameIfOwned(frozenFrame, "frozen inspection frame");
                }
            }
        }, () -> {
            deleteTemporaryArtifact(sourceHeatmap.path(), "discarded queued source heatmap");
            deleteFrozenFrameIfOwned(frozenFrame, "discarded queued frozen inspection frame");
        });
        removeQueuedPublishForCamera(uiArtifactsExecutor, cameraId);
        try {
            uiArtifactsExecutor.execute(publishTask);
        } catch (java.util.concurrent.RejectedExecutionException e) {
            deleteTemporaryArtifact(sourceHeatmap.path(), "rejected source heatmap");
            deleteFrozenFrameIfOwned(frozenFrame, "rejected frozen inspection frame");
            droppedUiPublishTasks.increment();
            log.warn("ui publish rejected camera_id={} frame_id={} dropped_total={}", cameraId, frameId, droppedUiPublishTasks.sum());
        }
    }

    private static String resolveInspectionFrameHttpPath(int cameraId, String bundleId, boolean hasCurrentJpeg) {
        if (bundleId != null && !bundleId.isBlank()) {
            return "/api/inspection-artifacts/" + bundleId + "/frame.jpg";
        }
        return hasCurrentJpeg ? "/api/camera/" + cameraId + "/current.jpg" : null;
    }

    private boolean saveFrameArchiveImmediately(
            int cameraId,
            long frameId,
            long inspectionId,
            String productType,
            String detectorId,
            InspectionDecision decision,
            Path frameJpeg,
            Path heatmapU8,
            int heatmapWidth,
            int heatmapHeight
    ) {
        FrameArchiveService archive = frameArchiveService;
        if (archive == null || !archive.enabled() || frameJpeg == null) {
            return false;
        }
        return archive.saveImmediately(new FrameArchiveService.SaveRequest(
                cameraId,
                frameId,
                inspectionId,
                productType,
                detectorId,
                decision,
                frameJpeg,
                heatmapU8,
                heatmapWidth,
                heatmapHeight
        ));
    }

    private void removeQueuedPublishForCamera(ExecutorService executor, int cameraId) {
        if (!(executor instanceof ThreadPoolExecutor pool)) {
            return;
        }
        for (Runnable queued : pool.getQueue()) {
            if (queued instanceof UiPublishTask task
                    && task.cameraId() == cameraId
                    && pool.remove(queued)) {
                task.discard();
            }
        }
    }

    @Override
    public void discardInspectionArtifacts(BinaryProtocol.Message pyResp) {
        try {
            HeatmapArtifact heatmap = resolveHeatmapArtifact(
                    pyResp == null ? null : pyResp.header(),
                    null,
                    0,
                    0
            );
            deleteTemporaryArtifact(heatmap.path(), "discarded source heatmap");
        } catch (RuntimeException e) {
            log.debug("discarded source heatmap cleanup failed: {}", e.getMessage());
        }
    }

    private boolean isLatestPublish(int cameraId, long publishSequence) {
        return Long.valueOf(publishSequence).equals(latestUiPublishByCamera.get(cameraId));
    }

    private void deleteTemporaryArtifact(Path path, String label) {
        if (path == null) {
            return;
        }
        try {
            Files.deleteIfExists(path);
        } catch (IOException e) {
            log.debug("{} cleanup failed path={}: {}", label, path, e.getMessage());
        }
    }

    private void deleteFrozenFrameIfOwned(FrozenFrame frozenFrame, String label) {
        if (frozenFrame == null || !frozenFrame.deleteWhenDone()) {
            return;
        }
        deleteTemporaryArtifact(frozenFrame.path(), label);
    }

    /**
     * Preview/card JPEG must show the positioned frame when positioning succeeded.
     */
    private static String resolveUiPreviewShmName(Map<String, Object> cap, int cameraId) {
        if (cap == null || cap.isEmpty()) {
            return null;
        }
        Object explicit = cap.get("ui_preview_shm_name");
        if (explicit != null) {
            String name = String.valueOf(explicit).trim();
            if (!name.isEmpty() && previewShmExists(name, cameraId)) {
                return name.startsWith("/") ? name : "/" + name.replace("/", "_");
            }
        }
        if (YamlScalars.toBool(cap.get("positioning_aligned"), false)) {
            Object shm = cap.get("shm_name");
            if (shm != null) {
                String name = String.valueOf(shm).trim();
                if (name.contains("iml_pos") && previewShmExists(name, cameraId)) {
                    return name.startsWith("/") ? name : "/" + name.replace("/", "_");
                }
            }
        }
        String positioned = "/iml_pos_cam_" + cameraId;
        if (previewShmExists(positioned, cameraId)) {
            Object status = cap.get("positioning_status");
            boolean aligned = YamlScalars.toBool(cap.get("positioning_aligned"), false)
                    || "PASS".equalsIgnoreCase(String.valueOf(status == null ? "" : status));
            if (aligned) {
                return positioned;
            }
        }
        return null;
    }

    private static boolean previewShmExists(String shmName, int cameraId) {
        Path path = FrameJpegWriter.resolveShmPath(shmName, cameraId);
        return path != null && Files.isRegularFile(path);
    }

    private static FrozenFrame freezeInspectionFrame(
            int cameraId,
            long frameId,
            String shmName,
            int width,
            int height,
            int stride,
            Map<String, Object> captureHeader
    ) throws IOException {
        if (width <= 0 || height <= 0 || stride < width * 3) {
            throw new IOException("invalid frame geometry");
        }
        long sourceOffset = YamlScalars.toLong(captureHeader.get("shm_offset"), 0L);
        long frameBytes = Math.multiplyExact((long) stride, (long) height);
        Path source = FrameJpegWriter.resolveShmPath(shmName, cameraId);
        if (source == null || !Files.isRegularFile(source)) {
            throw new IOException("source SHM is missing");
        }

        String base = shmName.startsWith("/") ? shmName.substring(1) : shmName;
        base = base.replace('/', '_');
        // Line-pin files are per-cycle; always copy into a stable UI buffer so the pin can be deleted
        // immediately after freeze without racing the async JPEG publisher.
        boolean ephemeralPin = YamlScalars.toBool(captureHeader.get("line_pinned"), false)
                || ImlShmJanitor.isEphemeralLinePin(base);
        if (sourceOffset == 0L && !ephemeralPin && ImlShmJanitor.isDedicatedOrchestratorBuffer(base)) {
            return new FrozenFrame(source, "/" + base, false);
        }

        String frozenName = "iml_ui_inspect_cam_" + cameraId;
        Path target = FrameJpegWriter.imlShmFilePath(frozenName);
        Files.createDirectories(target.getParent());
        try (FileChannel input = FileChannel.open(source, StandardOpenOption.READ);
             FileChannel output = FileChannel.open(
                     target,
                     StandardOpenOption.CREATE,
                     StandardOpenOption.WRITE,
                     StandardOpenOption.TRUNCATE_EXISTING
             )) {
            if (input.size() < sourceOffset + frameBytes) {
                throw new IOException("source SHM is smaller than the captured frame");
            }
            long copied = 0L;
            while (copied < frameBytes) {
                long count = input.transferTo(sourceOffset + copied, frameBytes - copied, output);
                if (count <= 0L) {
                    throw new IOException("could not copy the complete captured frame");
                }
                copied += count;
            }
        } catch (IOException | RuntimeException e) {
            Files.deleteIfExists(target);
            throw e;
        }
        // Stable overwrite name — keep for next frame; pin cleanup happens via ImlShmJanitor.
        return new FrozenFrame(target, "/" + frozenName, false);
    }

    private HeatmapArtifact generateHeatmapArtifact(
            BinaryRpcSupervisor uiVisualsPython,
            ReferenceSnapshot activeReference,
            BinaryProtocol.Message geometry,
            Map<String, Object> uiCfg,
            int cameraId,
            long frameId,
            String productType,
            String detectorId,
            FrozenFrame frozenFrame,
            int width,
            int height,
            int stride
    ) {
        if (uiVisualsPython == null) {
            return HeatmapArtifact.empty();
        }
        if (activeReference == null || activeReference.header() == null
                || activeReference.header().get("shm_name") == null) {
            log.debug("ui heatmap skipped cam={} frame={} reason=reference_not_synced", cameraId, frameId);
            return HeatmapArtifact.empty();
        }
        try {
            Map<String, Object> captureHeader = new LinkedHashMap<>();
            captureHeader.put("frame_id", frameId);
            captureHeader.put("shm_name", frozenFrame.shmName());
            captureHeader.put("shm_offset", 0L);
            captureHeader.put("width", width);
            captureHeader.put("height", height);
            captureHeader.put("stride", stride);
            String frozenName = frozenFrame.shmName() == null ? "" : frozenFrame.shmName();
            if (frozenName.contains("iml_pos")) {
                captureHeader.put(InspectPositioningExecutor.HEADER_ALIGNED, true);
            }
            BinaryProtocol.Message captureMsg = new BinaryProtocol.Message(
                    BinaryProtocol.MSG_RESPONSE,
                    Map.copyOf(captureHeader),
                    new byte[0]
            );
            Map<String, Object> pyHeader = BinaryInspectHeaders.pythonInspectHeader(
                    cameraId,
                    productType,
                    detectorId,
                    captureMsg,
                    geometry,
                    pythonCfg,
                    false,
                    activeReference
            );
            String analysisProfile = CameraAnalysisProfiles.resolve(cameraId, productType);
            if (analysisProfile != null && !analysisProfile.isBlank()) {
                pyHeader.put("analysis_profile", analysisProfile);
            }
            if (geometryRuntimeConfig != null) {
                geometryRuntimeConfig.applyToPythonHeader(pyHeader, pythonCfg, analysisProfile);
            }
            Path heatmapOutRequested = FrameJpegWriter.imlShmFilePath("iml_ui_heatmap_cam_" + cameraId);
            pyHeader.put("heatmap_u8_output_path", heatmapOutRequested.toString());
            pyHeader.put(
                    "heatmap_max_width",
                    Math.max(0, YamlScalars.toInt(uiCfg == null ? null : uiCfg.get("heatmap_preview_max_width"), 512))
            );
            BinaryProtocol.Message heatmapResp = uiVisualsPython.command(pyHeader);
            if (heatmapResp.type() == BinaryProtocol.MSG_ERROR) {
                log.warn(
                        "ui heatmap generation failed cam={} frame={} error={}",
                        cameraId,
                        frameId,
                        heatmapResp.header() == null ? "unknown" : heatmapResp.header().get("error")
                );
                return HeatmapArtifact.empty();
            }
            return resolveHeatmapArtifact(heatmapResp.header(), heatmapOutRequested, width, height);
        } catch (Exception e) {
            log.warn("ui heatmap generation failed cam={} frame={}: {}", cameraId, frameId, e.getMessage());
            return HeatmapArtifact.empty();
        }
    }

    /**
     * Ожидается, что Python в заголовке ответа передаёт путь к записанному heatmap (и при необходимости размеры).
     * Поддерживаются несколько имён полей; если путь в JSON отсутствует, берётся файл по
     * {@code heatmap_u8_output_path} из запроса (тот же путь, куда пишет воркер).
     */
    private static HeatmapArtifact resolveHeatmapArtifact(
            Map<String, Object> respHeader,
            Path requestedOutputPath,
            int captureWidth,
            int captureHeight
    ) {
        Map<String, Object> hdr = respHeader == null ? Map.of() : respHeader;
        String raw = firstNonBlankString(
                hdr,
                "heatmap_u8_path",
                "heatmap_u8_output_path",
                "heatmap_path",
                "heatmap_file",
                "heatmapFile"
        );
        if (raw == null && requestedOutputPath != null) {
            raw = requestedOutputPath.toString();
        }
        if (raw == null || raw.isBlank()) {
            return HeatmapArtifact.empty();
        }
        Path candidate = Path.of(raw.trim());
        if (!Files.isRegularFile(candidate)) {
            return HeatmapArtifact.empty();
        }
        int uw = YamlScalars.toInt(hdr.get("heatmap_u8_width"), 0);
        if (uw <= 0) {
            uw = YamlScalars.toInt(hdr.get("heatmap_width"), 0);
        }
        int uh = YamlScalars.toInt(hdr.get("heatmap_u8_height"), 0);
        if (uh <= 0) {
            uh = YamlScalars.toInt(hdr.get("heatmap_height"), 0);
        }
        if (uw <= 0 || uh <= 0) {
            uw = inferHeatmapWidth(hdr, captureWidth, uw);
            uh = inferHeatmapHeight(candidate, hdr, captureWidth, captureHeight, uh, uw);
        }
        if (uw <= 0 || uh <= 0) {
            uw = Math.max(1, captureWidth);
            uh = Math.max(1, captureHeight);
        }
        return new HeatmapArtifact(candidate, uw, uh);
    }

    private static int inferHeatmapWidth(Map<String, Object> hdr, int captureWidth, int uw) {
        if (uw > 0) {
            return uw;
        }
        return Math.max(1, YamlScalars.toInt(hdr.get("width"), captureWidth));
    }

    private static int inferHeatmapHeight(
            Path file,
            Map<String, Object> hdr,
            int captureWidth,
            int captureHeight,
            int uh,
            int uw
    ) {
        if (uh > 0) {
            return uh;
        }
        int fromHdr = YamlScalars.toInt(hdr.get("height"), 0);
        if (fromHdr > 0) {
            return fromHdr;
        }
        try {
            long sz = Files.size(file);
            if (uw > 0 && sz > 0 && sz % uw == 0) {
                int h = (int) (sz / uw);
                if (h > 0 && h <= Math.max(1, captureHeight) * 16L) {
                    return h;
                }
            }
            if (captureHeight > 0 && captureWidth > 0) {
                if (sz == (long) captureWidth * captureHeight || sz == (long) captureWidth * captureHeight * 3) {
                    return captureHeight;
                }
            }
        } catch (IOException ignored) {
        }
        return Math.max(1, captureHeight);
    }

    private static String firstNonBlankString(Map<String, Object> map, String... keys) {
        for (String key : keys) {
            Object v = map.get(key);
            if (v == null) {
                continue;
            }
            String s = String.valueOf(v).trim();
            if (!s.isEmpty() && !"null".equalsIgnoreCase(s)) {
                return s;
            }
        }
        return null;
    }
}

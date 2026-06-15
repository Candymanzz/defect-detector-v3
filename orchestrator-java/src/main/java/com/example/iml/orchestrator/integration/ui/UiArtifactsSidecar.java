package com.example.iml.orchestrator.integration.ui;

import com.example.iml.orchestrator.integration.clientapi.ClientApiMount;
import com.example.iml.orchestrator.integration.clientws.ClientWebSocketServer;
import com.example.iml.orchestrator.integration.lighting.LightTriggerClient;
import com.example.iml.orchestrator.integration.capture.FrameJpegWriter;
import com.example.iml.orchestrator.integration.config.YamlScalars;
import com.example.iml.orchestrator.integration.pipeline.InspectionDecision;
import com.example.iml.orchestrator.integration.pipeline.spi.AfterInspectionSidecar;
import com.example.iml.orchestrator.integration.binaryrpc.BinaryRpcSupervisor;
import com.example.iml.orchestrator.protocol.BinaryProtocol;
import org.apache.logging.log4j.Logger;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.channels.FileChannel;
import java.nio.file.StandardOpenOption;
import java.util.Map;
import java.util.UUID;
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

    private record FrozenFrame(Path path, String shmName) {
    }

    private record UiPublishTask(int cameraId, Runnable delegate) implements Runnable {
        @Override
        public void run() {
            delegate.run();
        }
    }

    private final Logger log;
    private volatile ClientWebSocketServer clientWebSocketServer;
    private final java.util.concurrent.atomic.LongAdder droppedUiPublishTasks = new java.util.concurrent.atomic.LongAdder();
    private final AtomicLong uiPublishSequence = new AtomicLong();
    private final ConcurrentHashMap<Integer, Long> latestUiPublishByCamera = new ConcurrentHashMap<>();

    public UiArtifactsSidecar(Logger log) {
        this.log = log;
    }

    /**
     * Push {@code server.inspect_result} после инспекции (Фаза 4): даже при {@code ui_http.enabled: false} для части путей.
     */
    public void setClientWebSocketServer(ClientWebSocketServer clientWebSocketServer) {
        this.clientWebSocketServer = clientWebSocketServer;
    }

    public UiHttpServer startHttpServerIfEnabled(
            Map<String, Object> uiCfg,
            GeometrySnapshotCache geometrySnapshotCache,
            ClientApiMount clientApiMount,
            LightTriggerClient lightClient,
            Map<String, Object> rootYaml
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
                    rootYaml == null ? Map.of() : rootYaml
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
            ExecutorService uiArtifactsExecutor,
            int cameraId,
            String productType,
            String detectorId,
            InspectionDecision decision,
            BinaryProtocol.Message capture,
            BinaryProtocol.Message pyResp
    ) {
        if (capture == null) {
            return;
        }
        Map<String, Object> cap = capture.header();
        ClientWebSocketServer ws = clientWebSocketServer;
        String shmName = String.valueOf(cap.get("shm_name"));
        long frameId = YamlScalars.toLong(cap.get("frame_id"), -1L);
        int width = YamlScalars.toInt(cap.get("width"), 2448);
        int height = YamlScalars.toInt(cap.get("height"), 2048);
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
                    ws.notifyInspectResult(cameraId, productType, detectorId, decision, cap, null, 0, 0, null, null, false, null);
                } catch (Exception e) {
                    log.debug("client_ws inspect_result (no ui pool) cam={}: {}", cameraId, e.getMessage());
                }
            }
            deleteTemporaryArtifact(resolvedSourceHeatmap.path(), "unused source heatmap");
            return;
        }
        boolean storeCurrent = YamlScalars.toBool(uiCfg == null ? null : uiCfg.get("store_current_jpeg"), true);
        boolean storeHeatmapU8 = YamlScalars.toBool(uiCfg == null ? null : uiCfg.get("store_heatmap_u8"), true);
        if (!storeCurrent && !storeHeatmapU8) {
            if (ws != null) {
                try {
                    ws.notifyInspectResult(cameraId, productType, detectorId, decision, cap, null, 0, 0, null, null, false, null);
                } catch (Exception e) {
                    log.debug("client_ws inspect_result (no store flags) cam={}: {}", cameraId, e.getMessage());
                }
            }
            deleteTemporaryArtifact(resolvedSourceHeatmap.path(), "disabled source heatmap");
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
                ws.notifyInspectResult(cameraId, productType, detectorId, decision, cap, null, 0, 0, null, null, false, null);
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
            log.warn(
                    "inspection frame freeze failed camera_id={} frame_id={}: {}",
                    cameraId,
                    frameId,
                    e.getMessage()
            );
            return;
        }
        if (!isLatestPublish(cameraId, publishSequence)) {
            deleteTemporaryArtifact(sourceHeatmap.path(), "stale source heatmap");
            deleteTemporaryArtifact(frozenFrame.path(), "stale frozen inspection frame");
            return;
        }

        UiPublishTask publishTask = new UiPublishTask(cameraId, () -> {
            Path generatedHeatmapPreview = null;
            try {
                if (!isLatestPublish(cameraId, publishSequence)) {
                    return;
                }
                String artifactShmName = frozenFrame.shmName();
                Path heatmapU8 = sourceHeatmap.path();
                int uw = sourceHeatmap.width();
                int uh = sourceHeatmap.height();
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
                Path currentJpeg = null;
                int currentJpegW = 0;
                int currentJpegH = 0;
                Path temporaryJpeg = null;
                if (storeCurrent) {
                    int previewMaxW = YamlScalars.toInt(
                            uiCfg == null ? null : uiCfg.get("inspection_preview_max_width"),
                            YamlScalars.toInt(uiCfg == null ? null : uiCfg.get("client_preview_max_width"), 0)
                    );
                    int qualPct = YamlScalars.toInt(
                            uiCfg == null ? null : uiCfg.get("inspection_preview_jpeg_quality"),
                            YamlScalars.toInt(uiCfg == null ? null : uiCfg.get("client_preview_jpeg_quality"), 58)
                    );
                    qualPct = Math.min(100, Math.max(5, qualPct));
                    float q = qualPct / 100f;
                    boolean canCreateBundle =
                            heatmapU8 != null && uw > 0 && uh > 0 && Files.isRegularFile(heatmapU8);
                    UiHttpServer.ClientPreviewArtifact art =
                            UiHttpServer.writeCurrentJpegFromBgrShm(
                                    artifactShmName,
                                    width,
                                    height,
                                    stride,
                                    0L,
                                    previewMaxW,
                                    q,
                                    canCreateBundle ? -1 : cameraId
                            );
                    if (art.path() == null && art.error() != null) {
                        log.debug("ui sidecar cam={} preview jpeg: {}", cameraId, art.error());
                    }
                    currentJpeg = art.path();
                    currentJpegW = art.width();
                    currentJpegH = art.height();
                    if (canCreateBundle) {
                        temporaryJpeg = currentJpeg;
                    }
                }
                if (!isLatestPublish(cameraId, publishSequence)) {
                    deleteTemporaryArtifact(temporaryJpeg, "stale inspection jpeg");
                    return;
                }
                CameraPreviewStore.RegisteredInspectionArtifacts registeredArtifacts = null;
                boolean bundleSourcesReady =
                        currentJpeg != null && currentJpegW > 0 && currentJpegH > 0 && Files.isRegularFile(currentJpeg)
                                && heatmapU8 != null && uw > 0 && uh > 0 && Files.isRegularFile(heatmapU8);
                try {
                    if (bundleSourcesReady) {
                        registeredArtifacts = uiServer.registerInspectionArtifacts(
                                cameraId,
                                frameId,
                                currentJpeg,
                                heatmapU8
                        );
                        currentJpeg = registeredArtifacts.frameJpeg();
                        heatmapU8 = registeredArtifacts.heatmapU8();
                    }
                } catch (IOException e) {
                    currentJpeg = null;
                    currentJpegW = 0;
                    currentJpegH = 0;
                    heatmapU8 = null;
                    uw = 0;
                    uh = 0;
                    log.warn(
                            "inspection artifact bundle failed camera_id={} frame_id={}: {}",
                            cameraId,
                            frameId,
                            e.getMessage()
                    );
                } finally {
                    if (temporaryJpeg != null) {
                        try {
                            Files.deleteIfExists(temporaryJpeg);
                        } catch (IOException e) {
                            log.debug(
                                    "temporary inspection jpeg cleanup failed path={}: {}",
                                    temporaryJpeg,
                                    e.getMessage()
                            );
                        }
                    }
                }
                if (!isLatestPublish(cameraId, publishSequence)) {
                    return;
                }
                boolean hasCur =
                        currentJpeg != null && currentJpegW > 0 && currentJpegH > 0 && Files.isRegularFile(currentJpeg);
                boolean hasHm = heatmapU8 != null && uw > 0 && uh > 0 && Files.isRegularFile(heatmapU8);
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
                if (ws != null) {
                    try {
                        String bundleId = registeredArtifacts == null ? null : registeredArtifacts.bundleId();
                        String currentHttpPath = bundleId == null
                                ? null
                                : "/api/inspection-artifacts/" + bundleId + "/frame.jpg";
                        ws.notifyInspectResult(
                                cameraId,
                                productType,
                                detectorId,
                                decision,
                                cap,
                                hasHm ? heatmapU8 : null,
                                hasHm ? uw : 0,
                                hasHm ? uh : 0,
                                currentHttpPath,
                                null,
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
                deleteTemporaryArtifact(sourceHeatmap.path(), "source heatmap");
                deleteTemporaryArtifact(generatedHeatmapPreview, "scaled heatmap");
                try {
                    Files.deleteIfExists(frozenFrame.path());
                } catch (IOException e) {
                    log.debug("frozen inspection frame cleanup failed path={}: {}", frozenFrame.path(), e.getMessage());
                }
            }
        });
        removeQueuedPublishForCamera(uiArtifactsExecutor, cameraId);
        try {
            uiArtifactsExecutor.execute(publishTask);
        } catch (java.util.concurrent.RejectedExecutionException e) {
            deleteTemporaryArtifact(sourceHeatmap.path(), "rejected source heatmap");
            try {
                Files.deleteIfExists(frozenFrame.path());
            } catch (IOException cleanupError) {
                log.debug(
                        "rejected frozen inspection frame cleanup failed path={}: {}",
                        frozenFrame.path(),
                        cleanupError.getMessage()
                );
            }
            droppedUiPublishTasks.increment();
            log.warn("ui publish rejected camera_id={} frame_id={} dropped_total={}", cameraId, frameId, droppedUiPublishTasks.sum());
        }
    }

    private void removeQueuedPublishForCamera(ExecutorService executor, int cameraId) {
        if (!(executor instanceof ThreadPoolExecutor pool)) {
            return;
        }
        for (Runnable queued : pool.getQueue()) {
            if (queued instanceof UiPublishTask task
                    && task.cameraId() == cameraId
                    && pool.remove(queued)) {
                task.run();
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

        String frozenName = "iml_ui_inspect_cam_" + cameraId + "_f" + frameId + "_" + UUID.randomUUID() + ".bgr";
        Path target = FrameJpegWriter.imlShmFilePath(frozenName);
        Files.createDirectories(target.getParent());
        try (FileChannel input = FileChannel.open(source, StandardOpenOption.READ);
             FileChannel output = FileChannel.open(
                     target,
                     StandardOpenOption.CREATE_NEW,
                     StandardOpenOption.WRITE
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
        return new FrozenFrame(target, frozenName);
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

package com.example.iml.orchestrator.integration.ui;

import com.example.iml.orchestrator.integration.clientws.ClientWebSocketServer;
import com.example.iml.orchestrator.integration.clientws.bundle.FpZoneNorm;
import com.example.iml.orchestrator.integration.capture.FrameJpegWriter;
import com.example.iml.orchestrator.integration.config.YamlScalars;
import com.example.iml.orchestrator.integration.pipeline.spi.AfterInspectionSidecar;
import com.example.iml.orchestrator.integration.referencedraft.ReferenceDraftCoordinator;
import com.example.iml.orchestrator.protocol.BinaryProtocol;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.apache.logging.log4j.Logger;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.time.Duration;

/**
 * Асинхронные артефакты UI: пул задач после инспекции; heatmap через HTTP FastAPI {@code /inspect-shm-visuals}.
 */
public final class UiArtifactsSidecar implements AfterInspectionSidecar {

    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final HttpClient HTTP_CLIENT = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(15))
            .build();

    /**
     * Путь и размеры heatmap после ответа {@code inspect_shm} (поля заголовка задаёт Python).
     */
    private record HeatmapArtifact(Path path, int width, int height) {
        private static HeatmapArtifact empty() {
            return new HeatmapArtifact(null, 0, 0);
        }
    }

    private final Logger log;
    private volatile ClientWebSocketServer clientWebSocketServer;
    private volatile ReferenceDraftCoordinator referenceDraftCoordinator;

    public UiArtifactsSidecar(Logger log) {
        this.log = log;
    }

    public void setReferenceDraftCoordinator(ReferenceDraftCoordinator referenceDraftCoordinator) {
        this.referenceDraftCoordinator = referenceDraftCoordinator;
    }

    /**
     * Push {@code server.inspect_result} после инспекции (Фаза 4): даже при {@code ui_http.enabled: false} для части путей.
     */
    public void setClientWebSocketServer(ClientWebSocketServer clientWebSocketServer) {
        this.clientWebSocketServer = clientWebSocketServer;
    }

    public UiHttpServer startHttpServerIfEnabled(
            Map<String, Object> uiCfg,
            ReferenceDraftCoordinator referenceDraftCoordinator,
            InspectionResultCache inspectionResultCache,
            ClientWebSocketServer orchestratorClientWs,
            String analisSurfaceHttpBaseUrl
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
                    referenceDraftCoordinator,
                    inspectionResultCache,
                    orchestratorClientWs,
                    analisSurfaceHttpBaseUrl
            );
            log.info("ui http started on {}:{}", host, port);
            return server;
        } catch (Exception e) {
            log.warn("ui http failed to start: {}", e.getMessage());
            return null;
        }
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
        ThreadPoolExecutor executor = new ThreadPoolExecutor(
                1,
                1,
                30L,
                TimeUnit.SECONDS,
                new java.util.concurrent.ArrayBlockingQueue<>(q),
                r -> {
                    Thread t = new Thread(r, "ui-publish");
                    t.setDaemon(true);
                    return t;
                },
                new ThreadPoolExecutor.DiscardPolicy()
        );
        executor.allowCoreThreadTimeOut(false);
        return executor;
    }

    @Override
    public void scheduleAfterInspection(
            UiHttpServer uiServer,
            Map<String, Object> uiCfg,
            String analisSurfaceHttpBaseUrl,
            int analisSurfaceHttpTimeoutMs,
            ExecutorService uiArtifactsExecutor,
            int cameraId,
            String productType,
            String detectorId,
            BinaryProtocol.Message capture,
            BinaryProtocol.Message python,
            BinaryProtocol.Message geometry,
            long inspectionCycleEndNanos
    ) {
        if (capture == null) {
            return;
        }
        Map<String, Object> cap = capture.header();
        ClientWebSocketServer wsSnap = clientWebSocketServer;
        List<FpZoneNorm> fpiSnap = wsSnap == null ? List.of() : List.copyOf(wsSnap.referenceContext().effectiveFpZones());
        ReferenceDraftCoordinator draft = referenceDraftCoordinator;
        if (draft != null) {
            draft.recordCapture(cameraId, cap);
        }
        if (draft != null && draft.isPaused()) {
            return;
        }
        ClientWebSocketServer ws = clientWebSocketServer;
        if (uiServer == null || uiArtifactsExecutor == null) {
            if (ws != null) {
                try {
                    ws.notifyInspectResult(
                            cameraId,
                            productType,
                            detectorId,
                            cap,
                            python,
                            geometry,
                            fpiSnap,
                            null,
                            0,
                            0,
                            null,
                            false,
                            inspectionCycleEndNanos
                    );
                } catch (Exception e) {
                    log.debug("client_ws inspect_result (no ui pool) cam={}: {}", cameraId, e.getMessage());
                }
            }
            return;
        }
        boolean storeCurrent = YamlScalars.toBool(uiCfg == null ? null : uiCfg.get("store_current_jpeg"), true);
        boolean storeHeatmapU8 = YamlScalars.toBool(uiCfg == null ? null : uiCfg.get("store_heatmap_u8"), true);
        if (!storeCurrent && !storeHeatmapU8) {
            if (ws != null) {
                try {
                    ws.notifyInspectResult(
                            cameraId,
                            productType,
                            detectorId,
                            cap,
                            python,
                            geometry,
                            fpiSnap,
                            null,
                            0,
                            0,
                            null,
                            false,
                            inspectionCycleEndNanos
                    );
                } catch (Exception e) {
                    log.debug("client_ws inspect_result (no store flags) cam={}: {}", cameraId, e.getMessage());
                }
            }
            return;
        }

        String shmName = String.valueOf(cap.get("shm_name"));
        long frameId = YamlScalars.toLong(cap.get("frame_id"), -1L);
        int width = YamlScalars.toInt(cap.get("width"), 2448);
        int height = YamlScalars.toInt(cap.get("height"), 2048);
        int stride = YamlScalars.toInt(cap.get("stride"), width * 3);

        final long pipelineDoneNanos = inspectionCycleEndNanos;
        uiArtifactsExecutor.execute(() -> {
            try {
                ReferenceDraftCoordinator d = referenceDraftCoordinator;
                if (d != null && d.isPaused()) {
                    return;
                }
                Path heatmapU8 = null;
                int uw = 0;
                int uh = 0;
                if (storeHeatmapU8 && analisSurfaceHttpBaseUrl != null && !analisSurfaceHttpBaseUrl.isBlank()) {
                    Map<String, Object> body = new HashMap<>();
                    body.put("product_type", productType);
                    body.put("detector_id", detectorId);
                    body.put("threshold", 0.25);
                    body.put("shm_name", shmName);
                    body.put("shm_offset", cap.get("shm_offset"));
                    body.put("width", width);
                    body.put("height", height);
                    body.put("stride", stride);
                    String base = (shmName.startsWith("/") ? shmName.substring(1) : shmName);
                    Path heatmapOutRequested = FrameJpegWriter.imlShmFilePath(base + ".heatmap.u8");
                    body.put("heatmap_u8_output_path", heatmapOutRequested.toString());
                    try {
                        Map<String, Object> json = postInspectShmVisuals(analisSurfaceHttpBaseUrl, body, analisSurfaceHttpTimeoutMs);
                        Map<String, Object> flat = flattenHeatmapFromVisualsResponse(json);
                        HeatmapArtifact hm = resolveHeatmapArtifact(flat, heatmapOutRequested, width, height);
                        heatmapU8 = hm.path();
                        uw = hm.width();
                        uh = hm.height();
                    } catch (Exception e) {
                        log.debug("ui heatmap inspect-shm-visuals failed cam={}: {}", cameraId, e.getMessage());
                    }
                }
                Path currentJpeg = null;
                int currentJpegW = 0;
                int currentJpegH = 0;
                if (storeCurrent) {
                    int previewMaxW = YamlScalars.toInt(uiCfg == null ? null : uiCfg.get("client_preview_max_width"), 0);
                    int qualPct = YamlScalars.toInt(uiCfg == null ? null : uiCfg.get("client_preview_jpeg_quality"), 58);
                    qualPct = Math.min(100, Math.max(5, qualPct));
                    float q = qualPct / 100f;
                    UiHttpServer.ClientPreviewArtifact art =
                            UiHttpServer.writeCurrentJpegFromBgrShm(shmName, width, height, stride, previewMaxW, q);
                    currentJpeg = art.path();
                    currentJpegW = art.width();
                    currentJpegH = art.height();
                }
                UiHttpServer.Latest prev = uiServer.latest(cameraId).orElse(null);
                if (currentJpeg == null && prev != null) {
                    currentJpeg = prev.currentJpeg();
                    currentJpegW = prev.currentJpegWidth();
                    currentJpegH = prev.currentJpegHeight();
                }
                if (heatmapU8 == null && prev != null) {
                    heatmapU8 = prev.heatmapU8();
                    uw = prev.heatmapU8Width();
                    uh = prev.heatmapU8Height();
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
                            hasHm ? uh : 0
                    );
                }
                if (ws != null) {
                    try {
                        boolean filePathInWs = hasHm;
                        ws.notifyInspectResult(
                                cameraId,
                                productType,
                                detectorId,
                                cap,
                                python,
                                geometry,
                                fpiSnap,
                                hasHm ? heatmapU8 : null,
                                hasHm ? uw : 0,
                                hasHm ? uh : 0,
                                null,
                                filePathInWs,
                                pipelineDoneNanos
                        );
                    } catch (Exception e) {
                        log.debug("client_ws inspect_result cam={}: {}", cameraId, e.getMessage());
                    }
                }
            } catch (Exception ignored) {
            }
        });
    }

    private static Map<String, Object> postInspectShmVisuals(String baseUrl, Map<String, Object> body, int timeoutMs)
            throws IOException, InterruptedException {
        String root = baseUrl.endsWith("/") ? baseUrl.substring(0, baseUrl.length() - 1) : baseUrl;
        byte[] json = MAPPER.writeValueAsBytes(body);
        HttpRequest req = HttpRequest.newBuilder(URI.create(root + "/inspect-shm-visuals"))
                .timeout(Duration.ofMillis(Math.max(100, timeoutMs)))
                .header("Content-Type", "application/json; charset=utf-8")
                .header("Accept", "application/json")
                .POST(HttpRequest.BodyPublishers.ofByteArray(json))
                .build();
        HttpResponse<byte[]> resp = HTTP_CLIENT.send(req, HttpResponse.BodyHandlers.ofByteArray());
        if (resp.statusCode() / 100 != 2) {
            throw new IOException("inspect-shm-visuals HTTP " + resp.statusCode());
        }
        byte[] rb = resp.body();
        if (rb == null || rb.length == 0) {
            return Map.of();
        }
        return MAPPER.readValue(rb, new TypeReference<>() {});
    }

    private static Map<String, Object> flattenHeatmapFromVisualsResponse(Map<String, Object> json) {
        Map<String, Object> h = new HashMap<>();
        Object hm = json.get("heatmap_u8");
        if (hm instanceof Map<?, ?> m) {
            if (m.get("path") != null) {
                h.put("heatmap_u8_path", m.get("path"));
            }
            if (m.get("width") != null) {
                h.put("heatmap_u8_width", m.get("width"));
            }
            if (m.get("height") != null) {
                h.put("heatmap_u8_height", m.get("height"));
            }
        }
        return h;
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

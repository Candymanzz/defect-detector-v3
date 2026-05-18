package com.example.iml.orchestrator.integration.ui;

import com.example.iml.orchestrator.integration.clientapi.ClientApiMount;
import com.example.iml.orchestrator.integration.clientws.ClientWebSocketServer;
import com.example.iml.orchestrator.integration.lighting.LightTriggerClient;
import com.example.iml.orchestrator.integration.capture.FrameJpegWriter;
import com.example.iml.orchestrator.integration.config.YamlScalars;
import com.example.iml.orchestrator.integration.pipeline.spi.AfterInspectionSidecar;
import com.example.iml.orchestrator.integration.binaryrpc.BinaryRpcSupervisor;
import com.example.iml.orchestrator.protocol.BinaryProtocol;
import org.apache.logging.log4j.Logger;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;

/**
 * Асинхронные артефакты UI: отдельный Python и пул задач после инспекции (не в горячем пути).
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

    private final Logger log;
    private volatile ClientWebSocketServer clientWebSocketServer;

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
        boolean enabled = YamlScalars.toBool(uiCfg == null ? null : uiCfg.get("enabled"), false)
                && YamlScalars.toBool(uiCfg == null ? null : uiCfg.get("visuals_async_enabled"), false);
        if (!enabled || pythonHttp == null) {
            return null;
        }
        log.info("ui visuals use analisSurface HTTP ({})", pythonHttp.supervisorLabel());
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
            BinaryRpcSupervisor uiVisualsPython,
            ExecutorService uiArtifactsExecutor,
            int cameraId,
            String productType,
            String detectorId,
            BinaryProtocol.Message capture,
            BinaryProtocol.Message geomResp
    ) {
        if (capture == null) {
            return;
        }
        Map<String, Object> cap = capture.header();
        ClientWebSocketServer ws = clientWebSocketServer;
        if (uiServer == null || uiArtifactsExecutor == null) {
            if (ws != null) {
                try {
                    ws.notifyInspectResult(cameraId, productType, detectorId, cap, null, 0, 0, null, false);
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
                    ws.notifyInspectResult(cameraId, productType, detectorId, cap, null, 0, 0, null, false);
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

        Object homography = geomResp == null ? null : geomResp.header().get("homographyRefToCurrent");

        uiArtifactsExecutor.execute(() -> {
            try {
                Path heatmapU8 = null;
                int uw = 0;
                int uh = 0;
                if (uiVisualsPython != null && storeHeatmapU8) {
                    Map<String, Object> pyHeader = new HashMap<>();
                    pyHeader.put("op", "inspect_shm");
                    pyHeader.put("camera_id", cameraId);
                    pyHeader.put("frame_id", frameId);
                    pyHeader.put("product_type", productType);
                    pyHeader.put("detector_id", detectorId);
                    pyHeader.put("threshold", 0.25);
                    pyHeader.put("include_visuals", false);
                    pyHeader.put("shm_name", shmName);
                    pyHeader.put("shm_offset", cap.get("shm_offset"));
                    pyHeader.put("width", width);
                    pyHeader.put("height", height);
                    pyHeader.put("stride", stride);
                    if (homography != null) {
                        pyHeader.put("alignment_h_ref_to_cur", homography);
                    }
                    String base = (shmName.startsWith("/") ? shmName.substring(1) : shmName);
                    Path heatmapOutRequested = FrameJpegWriter.imlShmFilePath(base + ".heatmap.u8");
                    pyHeader.put("heatmap_u8_output_path", heatmapOutRequested.toString());
                    BinaryProtocol.Message pyResp = uiVisualsPython.command(pyHeader);
                    if (pyResp.type() != BinaryProtocol.MSG_ERROR) {
                        HeatmapArtifact hm = resolveHeatmapArtifact(pyResp.header(), heatmapOutRequested, width, height);
                        heatmapU8 = hm.path();
                        uw = hm.width();
                        uh = hm.height();
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
                CameraPreviewStore.Latest prev = uiServer.latest(cameraId).orElse(null);
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
                        String hmToken = null;
                        if (hasHm) {
                            hmToken = uiServer.registerHeatmapArtifact(cameraId, heatmapU8);
                        }
                        boolean filePathInWs = hasHm && hmToken == null;
                        ws.notifyInspectResult(
                                cameraId,
                                productType,
                                detectorId,
                                cap,
                                hasHm ? heatmapU8 : null,
                                hasHm ? uw : 0,
                                hasHm ? uh : 0,
                                hmToken,
                                filePathInWs
                        );
                    } catch (Exception e) {
                        log.debug("client_ws inspect_result cam={}: {}", cameraId, e.getMessage());
                    }
                }
            } catch (Exception ignored) {
            }
        });
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

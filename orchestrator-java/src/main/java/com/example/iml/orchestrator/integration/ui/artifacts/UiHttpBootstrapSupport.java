package com.example.iml.orchestrator.integration.ui.artifacts;

import com.example.iml.orchestrator.integration.binaryrpc.BinaryRpcSupervisor;
import com.example.iml.orchestrator.integration.camera.CameraSettingsStore;
import com.example.iml.orchestrator.integration.clientapi.ClientApiMount;
import com.example.iml.orchestrator.integration.config.YamlScalars;
import com.example.iml.orchestrator.integration.lighting.LightBrightnessStore;
import com.example.iml.orchestrator.integration.lighting.LightTriggerClient;
import com.example.iml.orchestrator.integration.ui.FrameArchiveService;
import com.example.iml.orchestrator.integration.ui.GeometrySnapshotCache;
import com.example.iml.orchestrator.integration.ui.UiHttpServer;
import org.apache.logging.log4j.Logger;

import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;

/** Bootstrap helpers: UI HTTP server, visuals detector, publish executor. */
public final class UiHttpBootstrapSupport {

    private final Logger log;

    public UiHttpBootstrapSupport(Logger log) {
        this.log = log;
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
}

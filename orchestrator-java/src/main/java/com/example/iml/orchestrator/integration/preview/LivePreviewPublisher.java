package com.example.iml.orchestrator.integration.preview;

import com.example.iml.orchestrator.integration.camera.WorkerProcessSupervisor;
import com.example.iml.orchestrator.integration.clientws.ClientWebSocketServer;
import com.example.iml.orchestrator.integration.config.IntegrationFeatureConfig;
import com.example.iml.orchestrator.integration.config.ReferenceSource;
import com.example.iml.orchestrator.integration.config.YamlScalars;
import com.example.iml.orchestrator.integration.pipeline.reference.PipelineReferenceRegistry;
import com.example.iml.orchestrator.integration.lighting.LightTriggerClient;
import com.example.iml.orchestrator.protocol.BinaryProtocol;
import com.example.iml.orchestrator.integration.ui.UiHttpServer;
import org.apache.logging.log4j.Logger;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Периодический capture + JPEG на ui_http + {@code server.preview_frame} по WebSocket.
 * Работает в {@code NO_REFERENCE} и после эталона; полный пайплайн инспекции не вызывает.
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
    private final AtomicBoolean closed = new AtomicBoolean(false);

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
            ScheduledExecutorService scheduler
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
            IntegrationFeatureConfig.DevAutoTriggerStubConfig devAutoStub
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
                Math.max(1, workersByCamera.size()),
                r -> {
                    Thread t = new Thread(r, "live-preview");
                    t.setDaemon(true);
                    return t;
                }
        );
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
                scheduler
        );
        int intervalMs = cfg.tickIntervalMs();
        for (Map<String, Object> camera : cameras) {
            int cameraId = ((Number) camera.get("id")).intValue();
            WorkerProcessSupervisor worker = workersByCamera.get(cameraId);
            if (worker == null) {
                continue;
            }
            String productType = String.valueOf(camera.getOrDefault("product_type", "camera-" + cameraId));
            String detectorId = String.valueOf(camera.getOrDefault("detector", "v1"));
            scheduler.scheduleAtFixedRate(
                    () -> publisher.tick(cameraId, productType, detectorId, worker),
                    500L,
                    intervalMs,
                    TimeUnit.MILLISECONDS
            );
            publisher.log.info(
                    "live_preview cam={} interval_ms={} (dev_auto_trigger_stub takes over after reference)",
                    cameraId,
                    intervalMs
            );
        }
        return publisher;
    }

    private void tick(int cameraId, String productType, String detectorId, WorkerProcessSupervisor worker) {
        if (closed.get()) {
            return;
        }
        if (devAutoStub.enabled()
                && referenceSource == ReferenceSource.CLIENT
                && referenceRegistry != null
                && referenceRegistry.get(cameraId) != null) {
            return;
        }
        try {
            BinaryProtocol.Message capture;
            synchronized (worker) {
                lightClient.trigger(cameraId, -1L, "preview");
                if (flashLeadMs > 0) {
                    Thread.sleep(flashLeadMs);
                }
                capture = worker.command(Map.of("op", "capture"));
            }
            if (capture == null || capture.header() == null) {
                return;
            }
            Map<String, Object> header = capture.header();
            long frameId = YamlScalars.toLong(header.get("frame_id"), -1L);
            if (frameId < 0) {
                return;
            }
            String shmName = String.valueOf(header.get("shm_name"));
            int width = YamlScalars.toInt(header.get("width"), 0);
            int height = YamlScalars.toInt(header.get("height"), 0);
            int stride = YamlScalars.toInt(header.get("stride"), 0);
            if (shmName.isBlank() || width <= 0 || height <= 0) {
                return;
            }

            PathHolder jpeg = writePreviewJpeg(cameraId, shmName, width, height, stride);
            if (jpeg.path != null && Files.isRegularFile(jpeg.path)) {
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
                        0
                );
            }

            String httpPath = "/api/camera/" + cameraId + "/current.jpg";
            if (clientWs != null) {
                clientWs.notifyPreviewFrame(cameraId, productType, detectorId, header, httpPath);
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        } catch (Exception e) {
            log.debug("live_preview cam={}: {}", cameraId, e.getMessage());
        }
    }

    private PathHolder writePreviewJpeg(int cameraId, String shmName, int width, int height, int stride) {
        int previewMaxW = YamlScalars.toInt(uiCfg.get("client_preview_max_width"), 0);
        int qualPct = YamlScalars.toInt(uiCfg.get("client_preview_jpeg_quality"), 58);
        qualPct = Math.min(100, Math.max(5, qualPct));
        float q = qualPct / 100f;
        UiHttpServer.ClientPreviewArtifact art = UiHttpServer.writeCurrentJpegFromBgrShm(
                shmName, width, height, stride, previewMaxW, q, cameraId);
        return new PathHolder(art.path(), art.width(), art.height());
    }

    private record PathHolder(Path path, int width, int height) {
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

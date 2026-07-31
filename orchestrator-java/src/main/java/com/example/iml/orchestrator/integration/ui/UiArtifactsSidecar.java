package com.example.iml.orchestrator.integration.ui;

import com.example.iml.orchestrator.integration.clientapi.ClientApiMount;
import com.example.iml.orchestrator.integration.clientws.ClientWebSocketServer;
import com.example.iml.orchestrator.integration.lighting.LightTriggerClient;
import com.example.iml.orchestrator.integration.camera.CameraSettingsStore;
import com.example.iml.orchestrator.integration.lighting.LightBrightnessStore;
import com.example.iml.orchestrator.integration.pipeline.InspectionDecision;
import com.example.iml.orchestrator.integration.pipeline.ReferenceSnapshot;
import com.example.iml.orchestrator.integration.pipeline.spi.AfterInspectionSidecar;
import com.example.iml.orchestrator.integration.binaryrpc.BinaryRpcSupervisor;
import com.example.iml.orchestrator.integration.ui.artifacts.FrameArchiveHook;
import com.example.iml.orchestrator.integration.ui.artifacts.HeatmapArtifact;
import com.example.iml.orchestrator.integration.ui.artifacts.HeatmapArtifactProducer;
import com.example.iml.orchestrator.integration.ui.artifacts.InspectionFrameFreezer;
import com.example.iml.orchestrator.integration.ui.artifacts.InspectionPreviewPublisher;
import com.example.iml.orchestrator.integration.ui.artifacts.UiAfterInspectionScheduler;
import com.example.iml.orchestrator.integration.ui.artifacts.UiArtifactFiles;
import com.example.iml.orchestrator.integration.ui.artifacts.UiHttpBootstrapSupport;
import com.example.iml.orchestrator.integration.ui.artifacts.UiPublishScheduler;
import com.example.iml.orchestrator.protocol.BinaryProtocol;
import org.apache.logging.log4j.Logger;

import java.util.Map;
import java.util.concurrent.ExecutorService;

/**
 * Асинхронная подготовка UI-артефактов из результата основной инспекции.
 * Domain work lives in {@code ui.artifacts.*}; this class is the SPI + bind facade.
 */
public final class UiArtifactsSidecar implements AfterInspectionSidecar {

    private final Logger log;
    private final UiHttpBootstrapSupport httpBootstrap;
    private final UiPublishScheduler scheduler;
    private final InspectionFrameFreezer frameFreezer;
    private final HeatmapArtifactProducer heatmaps;
    private final UiArtifactFiles files;
    private final FrameArchiveHook archiveHook;
    private final InspectionPreviewPublisher previewPublisher;
    private final UiAfterInspectionScheduler afterInspectionScheduler;

    private volatile ClientWebSocketServer clientWebSocketServer;
    private volatile FrameArchiveService frameArchiveService;
    private volatile UiHttpServer uiServer;
    private volatile Map<String, Object> uiCfg;
    private volatile BinaryRpcSupervisor uiVisualsPython;
    private volatile ExecutorService uiArtifactsExecutor;

    public UiArtifactsSidecar(Logger log) {
        this.log = log;
        this.httpBootstrap = new UiHttpBootstrapSupport(log);
        this.scheduler = new UiPublishScheduler();
        this.frameFreezer = new InspectionFrameFreezer();
        this.heatmaps = new HeatmapArtifactProducer(log);
        this.files = new UiArtifactFiles(log);
        this.archiveHook = new FrameArchiveHook(() -> frameArchiveService);
        this.previewPublisher = new InspectionPreviewPublisher(
                log,
                scheduler,
                heatmaps,
                files,
                archiveHook,
                () -> frameArchiveService
        );
        this.afterInspectionScheduler = new UiAfterInspectionScheduler(
                log, scheduler, frameFreezer, heatmaps, files, previewPublisher);
    }

    /**
     * Bind UI publish collaborators once at bootstrap (SPI no longer receives UiHttpServer per call).
     */
    public void bindPublishContext(
            UiHttpServer uiServer,
            Map<String, Object> uiCfg,
            BinaryRpcSupervisor uiVisualsPython,
            ExecutorService uiArtifactsExecutor
    ) {
        this.uiServer = uiServer;
        this.uiCfg = uiCfg;
        this.uiVisualsPython = uiVisualsPython;
        this.uiArtifactsExecutor = uiArtifactsExecutor;
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
        return httpBootstrap.startHttpServerIfEnabled(
                uiCfg,
                geometrySnapshotCache,
                clientApiMount,
                lightClient,
                rootYaml,
                cameraSettingsStore,
                lightBrightnessStore,
                frameArchiveService
        );
    }

    /**
     * Heatmap/visuals через тот же FastAPI-пул, что и пайплайн ({@code POST /inspect-shm-visuals}).
     */
    public BinaryRpcSupervisor resolveVisualsDetector(Map<String, Object> uiCfg, BinaryRpcSupervisor pythonHttp) {
        return httpBootstrap.resolveVisualsDetector(uiCfg, pythonHttp);
    }

    /**
     * Пул фоновой публикации артефактов в {@link UiHttpServer}.
     */
    public ExecutorService startUiPublishExecutorIfEnabled(Map<String, Object> uiCfg) {
        return httpBootstrap.startUiPublishExecutorIfEnabled(uiCfg);
    }

    @Override
    public void scheduleAfterInspection(
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
        afterInspectionScheduler.schedule(
                cameraId,
                productType,
                detectorId,
                inspectionId,
                activeReference,
                decision,
                capture,
                pyResp,
                geometry,
                this.uiServer,
                this.uiCfg,
                this.uiVisualsPython,
                this.uiArtifactsExecutor,
                clientWebSocketServer
        );
    }

    @Override
    public void discardInspectionArtifacts(BinaryProtocol.Message pyResp) {
        try {
            HeatmapArtifact heatmap = heatmaps.resolveHeatmapArtifact(
                    pyResp == null ? null : pyResp.header(),
                    null,
                    0,
                    0
            );
            files.deleteTemporaryArtifact(heatmap.path(), "discarded source heatmap");
        } catch (RuntimeException e) {
            log.debug("discarded source heatmap cleanup failed: {}", e.getMessage());
        }
    }

    /** Кадр без эталона: preview/capture-only, не результат инспекции для архива. */
    static boolean isCaptureOnlyDecision(InspectionDecision decision) {
        return FrameArchiveHook.isCaptureOnlyDecision(decision);
    }
}

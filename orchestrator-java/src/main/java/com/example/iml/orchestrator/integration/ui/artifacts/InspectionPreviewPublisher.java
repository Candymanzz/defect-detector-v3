package com.example.iml.orchestrator.integration.ui.artifacts;

import com.example.iml.orchestrator.integration.binaryrpc.BinaryRpcSupervisor;
import com.example.iml.orchestrator.integration.clientws.ClientWebSocketServer;
import com.example.iml.orchestrator.integration.pipeline.InspectionDecision;
import com.example.iml.orchestrator.integration.pipeline.ReferenceSnapshot;
import com.example.iml.orchestrator.integration.ui.FrameArchiveService;
import com.example.iml.orchestrator.integration.ui.UiHttpServer;
import com.example.iml.orchestrator.protocol.BinaryProtocol;
import org.apache.logging.log4j.Logger;

import java.nio.file.Path;
import java.util.Map;
import java.util.function.Supplier;

/**
 * Heavy UI publish work: JPEG/card encode, register, heatmap, WS update, archive.
 */
public final class InspectionPreviewPublisher {

    private final Logger log;
    private final UiPublishScheduler scheduler;
    private final HeatmapArtifactProducer heatmaps;
    private final UiArtifactFiles files;
    private final FrameArchiveHook archiveHook;
    private final Supplier<FrameArchiveService> archiveSupplier;

    public InspectionPreviewPublisher(
            Logger log,
            UiPublishScheduler scheduler,
            HeatmapArtifactProducer heatmaps,
            UiArtifactFiles files,
            FrameArchiveHook archiveHook,
            Supplier<FrameArchiveService> archiveSupplier
    ) {
        this.log = log;
        this.scheduler = scheduler;
        this.heatmaps = heatmaps;
        this.files = files;
        this.archiveHook = archiveHook;
        this.archiveSupplier = archiveSupplier;
    }

    public void publish(
            int cameraId,
            String productType,
            String detectorId,
            long inspectionId,
            ReferenceSnapshot activeReference,
            InspectionDecision decision,
            Map<String, Object> cap,
            BinaryProtocol.Message geometry,
            UiHttpServer uiServer,
            Map<String, Object> uiCfg,
            BinaryRpcSupervisor uiVisualsPython,
            ClientWebSocketServer ws,
            String shmName,
            long frameId,
            int width,
            int height,
            int stride,
            boolean storeCurrent,
            boolean storeHeatmapU8,
            HeatmapArtifact sourceHeatmap,
            FrozenFrame frozenFrame,
            long publishSequence
    ) {
        Object cameraPublishLock = scheduler.lockForCamera(cameraId);
        synchronized (cameraPublishLock) {
            Path generatedHeatmapPreview = null;
            Path temporaryCurrentJpeg = null;
            Path temporaryCardJpeg = null;
            Path currentJpeg = null;
            try {
                InspectionPreviewFrameSupport.FrameReadyResult frame =
                        InspectionPreviewFrameSupport.publishFrameReady(
                                log,
                                cameraId,
                                productType,
                                detectorId,
                                inspectionId,
                                activeReference,
                                decision,
                                cap,
                                uiServer,
                                uiCfg,
                                ws,
                                shmName,
                                frameId,
                                width,
                                height,
                                stride,
                                storeCurrent,
                                frozenFrame
                        );
                temporaryCurrentJpeg = frame.temporaryCurrentJpeg();
                temporaryCardJpeg = frame.temporaryCardJpeg();
                currentJpeg = frame.currentJpeg();

                // A newer inspection may arrive while this task is encoding the JPEG.
                // Keep the frame-ready publication above, but avoid spending detector/CPU
                // capacity on a heatmap that the UI will immediately replace.
                // Archive the frame JPEG immediately so a superseded publish still persists history.
                if (!scheduler.isLatestPublish(cameraId, publishSequence)) {
                    archiveHook.saveImmediately(
                            cameraId,
                            frameId,
                            inspectionId,
                            productType,
                            detectorId,
                            decision,
                            frame.hasCur() ? currentJpeg : null,
                            null,
                            0,
                            0
                    );
                    return;
                }

                InspectionPreviewHeatmapSupport.HeatmapPublishResult heatmap =
                        InspectionPreviewHeatmapSupport.publishHeatmapAndArchive(
                                log,
                                heatmaps,
                                archiveHook,
                                archiveSupplier,
                                cameraId,
                                productType,
                                detectorId,
                                inspectionId,
                                activeReference,
                                decision,
                                cap,
                                geometry,
                                uiServer,
                                uiCfg,
                                uiVisualsPython,
                                ws,
                                shmName,
                                frameId,
                                width,
                                height,
                                stride,
                                storeHeatmapU8,
                                sourceHeatmap,
                                frozenFrame,
                                frame
                        );
                generatedHeatmapPreview = heatmap.generatedHeatmapPreview();
            } catch (Exception e) {
                log.warn(
                        "ui artifact publish failed camera_id={} frame_id={}: {}",
                        cameraId,
                        frameId,
                        e.getMessage()
                );
            } finally {
                if (temporaryCurrentJpeg != null && !temporaryCurrentJpeg.equals(currentJpeg)) {
                    files.deleteTemporaryArtifact(temporaryCurrentJpeg, "temporary inspection jpeg");
                }
                if (temporaryCardJpeg != null) {
                    files.deleteTemporaryArtifact(temporaryCardJpeg, "temporary inspection card jpeg");
                }
                files.deleteTemporaryArtifact(sourceHeatmap.path(), "source heatmap");
                files.deleteTemporaryArtifact(generatedHeatmapPreview, "scaled heatmap");
                files.deleteFrozenFrameIfOwned(frozenFrame, "frozen inspection frame");
            }
        }
    }

    public static String resolveInspectionFrameHttpPath(int cameraId, String bundleId, boolean hasCurrentJpeg) {
        if (bundleId != null && !bundleId.isBlank()) {
            return "/api/inspection-artifacts/" + bundleId + "/frame.jpg";
        }
        return hasCurrentJpeg ? "/api/camera/" + cameraId + "/current.jpg" : null;
    }
}

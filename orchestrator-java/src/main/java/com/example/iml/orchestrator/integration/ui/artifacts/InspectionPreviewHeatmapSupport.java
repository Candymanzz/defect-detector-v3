package com.example.iml.orchestrator.integration.ui.artifacts;

import com.example.iml.orchestrator.integration.binaryrpc.BinaryRpcSupervisor;
import com.example.iml.orchestrator.integration.clientws.ClientWebSocketServer;
import com.example.iml.orchestrator.integration.config.YamlScalars;
import com.example.iml.orchestrator.integration.pipeline.InspectionDecision;
import com.example.iml.orchestrator.integration.pipeline.ReferenceSnapshot;
import com.example.iml.orchestrator.integration.ui.CameraPreviewStore;
import com.example.iml.orchestrator.integration.ui.FrameArchiveService;
import com.example.iml.orchestrator.integration.ui.HeatmapU8PreviewScaler;
import com.example.iml.orchestrator.integration.ui.UiHttpServer;
import com.example.iml.orchestrator.protocol.BinaryProtocol;
import org.apache.logging.log4j.Logger;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import java.util.function.Supplier;

/** Heatmap generate/scale/attach + archive + final WS notify. */
final class InspectionPreviewHeatmapSupport {

    private InspectionPreviewHeatmapSupport() {
    }

    record HeatmapPublishResult(Path generatedHeatmapPreview) {
    }

    static HeatmapPublishResult publishHeatmapAndArchive(
            Logger log,
            HeatmapArtifactProducer heatmaps,
            FrameArchiveHook archiveHook,
            Supplier<FrameArchiveService> archiveSupplier,
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
            boolean storeHeatmapU8,
            HeatmapArtifact sourceHeatmap,
            FrozenFrame frozenFrame,
            InspectionPreviewFrameSupport.FrameReadyResult frame
    ) {
        Path generatedHeatmapPreview = null;
        Path currentJpeg = frame.currentJpeg();
        boolean hasCur = frame.hasCur();
        String bundleId = frame.bundleId();
        CameraPreviewStore.RegisteredInspectionArtifacts registeredArtifacts = frame.registeredArtifacts();

        HeatmapArtifact heatmapSource = sourceHeatmap;
        if (storeHeatmapU8
                && (heatmapSource.path() == null || heatmapSource.width() <= 0 || heatmapSource.height() <= 0)) {
            heatmapSource = heatmaps.generateHeatmapArtifact(
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
                    hasCur ? frame.currentJpegW() : 0,
                    hasCur ? frame.currentJpegH() : 0,
                    hasHm ? heatmapU8 : null,
                    hasHm ? uw : 0,
                    hasHm ? uh : 0,
                    decision
            );
        }
        FrameArchiveService archive = archiveSupplier.get();
        boolean archived = archiveHook.saveImmediately(
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
                String frameHttpPath = archived && archive != null
                        ? archive.frameArtifactHttpPath(cameraId, frameId, "frame.jpg")
                        : InspectionPreviewPublisher.resolveInspectionFrameHttpPath(cameraId, bundleId, hasCur);
                String heatmapArtifactToken = bundleId == null && hasHm
                        ? uiServer.registerHeatmapArtifact(cameraId, heatmapU8)
                        : null;
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
        return new HeatmapPublishResult(generatedHeatmapPreview);
    }
}

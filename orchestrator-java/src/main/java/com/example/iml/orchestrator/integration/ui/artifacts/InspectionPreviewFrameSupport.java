package com.example.iml.orchestrator.integration.ui.artifacts;

import com.example.iml.orchestrator.integration.clientws.ClientWebSocketServer;
import com.example.iml.orchestrator.integration.config.YamlScalars;
import com.example.iml.orchestrator.integration.pipeline.InspectionDecision;
import com.example.iml.orchestrator.integration.pipeline.ReferenceSnapshot;
import com.example.iml.orchestrator.integration.ui.CameraPreviewStore;
import com.example.iml.orchestrator.integration.ui.UiHttpServer;
import org.apache.logging.log4j.Logger;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;

/** JPEG encode + register + frame-ready UI/WS notify. */
final class InspectionPreviewFrameSupport {

    private InspectionPreviewFrameSupport() {
    }

    record FrameReadyResult(
            Path currentJpeg,
            Path temporaryCurrentJpeg,
            Path cardJpeg,
            Path temporaryCardJpeg,
            int currentJpegW,
            int currentJpegH,
            boolean hasCur,
            String bundleId,
            CameraPreviewStore.RegisteredInspectionArtifacts registeredArtifacts
    ) {
    }

    static FrameReadyResult publishFrameReady(
            Logger log,
            int cameraId,
            String productType,
            String detectorId,
            long inspectionId,
            ReferenceSnapshot activeReference,
            InspectionDecision decision,
            Map<String, Object> cap,
            UiHttpServer uiServer,
            Map<String, Object> uiCfg,
            ClientWebSocketServer ws,
            String shmName,
            long frameId,
            int width,
            int height,
            int stride,
            boolean storeCurrent,
            FrozenFrame frozenFrame
    ) {
        Path currentJpeg = null;
        Path temporaryCurrentJpeg = null;
        Path cardJpeg = null;
        Path temporaryCardJpeg = null;
        int currentJpegW = 0;
        int currentJpegH = 0;
        String bundleId = null;
        CameraPreviewStore.RegisteredInspectionArtifacts registeredArtifacts = null;

        if (storeCurrent) {
            String artifactShmName = frozenFrame.shmName();
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
                    String frameHttpPath = InspectionPreviewPublisher.resolveInspectionFrameHttpPath(
                            cameraId, bundleId, hasCur);
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

        return new FrameReadyResult(
                currentJpeg,
                temporaryCurrentJpeg,
                cardJpeg,
                temporaryCardJpeg,
                currentJpegW,
                currentJpegH,
                hasCur,
                bundleId,
                registeredArtifacts
        );
    }
}

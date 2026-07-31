package com.example.iml.orchestrator.integration.preview;

import com.example.iml.orchestrator.integration.config.YamlScalars;
import com.example.iml.orchestrator.integration.ui.UiHttpServer;

import java.nio.file.Path;
import java.util.Map;

final class LivePreviewJpegPublisher {
    private final UiHttpServer uiServer;
    private final Map<String, Object> uiCfg;

    LivePreviewJpegPublisher(UiHttpServer uiServer, Map<String, Object> uiCfg) {
        this.uiServer = uiServer;
        this.uiCfg = uiCfg == null ? Map.of() : uiCfg;
    }

    JpegArtifact writePreviewJpeg(
            int cameraId,
            String shmName,
            int width,
            int height,
            int stride,
            long shmOffset
    ) {
        int previewMaxW = YamlScalars.toInt(uiCfg.get("client_preview_max_width"), 0);
        int qualPct = YamlScalars.toInt(uiCfg.get("client_preview_jpeg_quality"), 58);
        qualPct = Math.min(100, Math.max(5, qualPct));
        UiHttpServer.ClientPreviewArtifact artifact = UiHttpServer.writeCurrentJpegFromBgrShm(
                shmName, width, height, stride, shmOffset, previewMaxW, qualPct / 100f, cameraId);
        return new JpegArtifact(artifact.path(), artifact.width(), artifact.height(), artifact.error());
    }

    void updateUi(
            CameraPreviewTarget target,
            long frameId,
            String shmName,
            int width,
            int height,
            JpegArtifact jpeg
    ) {
        uiServer.update(
                target.cameraId(),
                frameId,
                target.productType(),
                target.detectorId(),
                shmName,
                width,
                height,
                jpeg.path(),
                jpeg.width(),
                jpeg.height(),
                null,
                0,
                0,
                null
        );
    }

    record JpegArtifact(Path path, int width, int height, String error) {
    }
}

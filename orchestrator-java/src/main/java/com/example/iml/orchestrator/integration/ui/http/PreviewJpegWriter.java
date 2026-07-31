package com.example.iml.orchestrator.integration.ui.http;

import com.example.iml.orchestrator.integration.ui.UiHttpServer;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.image.BufferedImage;

/**
 * JPEG preview helpers: чтение BGR SHM → current/inspection JPEG для UI.
 */
public final class PreviewJpegWriter {
    private static final Logger LOG = LogManager.getLogger(PreviewJpegWriter.class);

    private PreviewJpegWriter() {
    }

    public static UiHttpServer.ClientPreviewArtifact writeCurrentJpegFromBgrShm(
            String shmName, int width, int height, int stride, int previewMaxWidth, float quality
    ) {
        return writeCurrentJpegFromBgrShm(shmName, width, height, stride, 0L, previewMaxWidth, quality, -1);
    }

    public static UiHttpServer.ClientPreviewArtifact writeCurrentJpegFromBgrShm(
            String shmName, int width, int height, int stride, int previewMaxWidth, float quality, int cameraId
    ) {
        return writeCurrentJpegFromBgrShm(shmName, width, height, stride, 0L, previewMaxWidth, quality, cameraId);
    }

    public static UiHttpServer.ClientPreviewArtifact writeCurrentJpegFromBgrShm(
            String shmName,
            int width,
            int height,
            int stride,
            long shmOffset,
            int previewMaxWidth,
            float quality,
            int cameraId
    ) {
        BufferedImage source;
        try {
            source = PreviewBgrShmReader.readBgrImageFromShm(shmName, width, height, stride, shmOffset, cameraId);
        } catch (Exception e) {
            return previewJpegFailed(e.getMessage());
        }
        return writePreviewJpeg(source, previewMaxWidth, quality, cameraId);
    }

    public static UiHttpServer.InspectionPreviewArtifacts writeInspectionJpegsFromBgrShm(
            String shmName,
            int width,
            int height,
            int stride,
            long shmOffset,
            int frameMaxWidth,
            float frameQuality,
            int cardMaxWidth,
            float cardQuality
    ) {
        final BufferedImage source;
        try {
            source = PreviewBgrShmReader.readBgrImageFromShm(shmName, width, height, stride, shmOffset, -1);
        } catch (Exception e) {
            UiHttpServer.ClientPreviewArtifact failed = previewJpegFailed(e.getMessage());
            return new UiHttpServer.InspectionPreviewArtifacts(failed, failed);
        }

        return new UiHttpServer.InspectionPreviewArtifacts(
                writePreviewJpeg(source, frameMaxWidth, frameQuality, -1),
                writePreviewJpeg(source, cardMaxWidth, cardQuality, -1)
        );
    }

    private static UiHttpServer.ClientPreviewArtifact writePreviewJpeg(
            BufferedImage source,
            int previewMaxWidth,
            float quality,
            int cameraId
    ) {
        int outW = source.getWidth();
        int outH = source.getHeight();
        BufferedImage output = source;
        if (previewMaxWidth > 0 && outW > previewMaxWidth) {
            outW = previewMaxWidth;
            outH = Math.max(1, (int) Math.round((double) source.getHeight() * previewMaxWidth / source.getWidth()));
            output = new BufferedImage(outW, outH, BufferedImage.TYPE_3BYTE_BGR);
            Graphics2D graphics = output.createGraphics();
            try {
                graphics.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BILINEAR);
                graphics.drawImage(source, 0, 0, outW, outH, null);
            } finally {
                graphics.dispose();
            }
        }
        try {
            var out = cameraId >= 0
                    ? PreviewJpegEncoder.writeStablePreviewJpeg(cameraId, output, quality)
                    : PreviewJpegEncoder.writeTempPreviewJpeg(output, quality);
            if (out == null) {
                return previewJpegFailed("jpeg encode failed cameraId=" + cameraId);
            }
            return UiHttpServer.ClientPreviewArtifact.ok(out, outW, outH);
        } catch (Exception e) {
            return previewJpegFailed("exception: " + e.getMessage());
        }
    }

    private static UiHttpServer.ClientPreviewArtifact previewJpegFailed(String reason) {
        LOG.debug("preview jpeg failed: {}", reason);
        return UiHttpServer.ClientPreviewArtifact.failed(reason);
    }
}

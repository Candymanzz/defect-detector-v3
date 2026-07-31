package com.example.iml.orchestrator.integration.ui;

import com.example.iml.orchestrator.integration.ui.http.PreviewJpegWriter;

/** JPEG write facades used by {@link UiHttpServer}. */
final class UiHttpPreviewArtifacts {

    private UiHttpPreviewArtifacts() {
    }

    static UiHttpServer.ClientPreviewArtifact writeCurrent(
            String shmName, int width, int height, int stride, int previewMaxWidth, float quality
    ) {
        return PreviewJpegWriter.writeCurrentJpegFromBgrShm(shmName, width, height, stride, previewMaxWidth, quality);
    }

    static UiHttpServer.ClientPreviewArtifact writeCurrent(
            String shmName, int width, int height, int stride, int previewMaxWidth, float quality, int cameraId
    ) {
        return PreviewJpegWriter.writeCurrentJpegFromBgrShm(
                shmName, width, height, stride, previewMaxWidth, quality, cameraId
        );
    }

    static UiHttpServer.ClientPreviewArtifact writeCurrent(
            String shmName, int width, int height, int stride, long shmOffset,
            int previewMaxWidth, float quality, int cameraId
    ) {
        return PreviewJpegWriter.writeCurrentJpegFromBgrShm(
                shmName, width, height, stride, shmOffset, previewMaxWidth, quality, cameraId
        );
    }

    static UiHttpServer.InspectionPreviewArtifacts writeInspection(
            String shmName, int width, int height, int stride, long shmOffset,
            int frameMaxWidth, float frameQuality, int cardMaxWidth, float cardQuality
    ) {
        return PreviewJpegWriter.writeInspectionJpegsFromBgrShm(
                shmName, width, height, stride, shmOffset,
                frameMaxWidth, frameQuality, cardMaxWidth, cardQuality
        );
    }
}

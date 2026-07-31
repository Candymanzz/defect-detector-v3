package com.example.iml.orchestrator.integration.preview;

import com.example.iml.orchestrator.integration.pipeline.session.PerCameraInspectionGate;
import com.example.iml.orchestrator.integration.stream.CameraStreamService;

final class LivePreviewTickPolicy {
    private final LivePreviewGate previewGate;
    private final PerCameraInspectionGate inspectionGate;
    private final CameraStreamService cameraStreamService;

    LivePreviewTickPolicy(
            LivePreviewGate previewGate,
            PerCameraInspectionGate inspectionGate,
            CameraStreamService cameraStreamService
    ) {
        this.previewGate = previewGate;
        this.inspectionGate = inspectionGate;
        this.cameraStreamService = cameraStreamService;
    }

    boolean isPreviewPaused() {
        return previewGate != null && previewGate.isPaused();
    }

    boolean areImagesEnabled() {
        return previewGate == null || previewGate.areImagesEnabled();
    }

    boolean hasAnyInspectionInFlight() {
        return inspectionGate != null && inspectionGate.hasAnyInspectionInFlight();
    }

    boolean isInspectionInFlight(int cameraId) {
        return inspectionGate != null && inspectionGate.isInspectionInFlight(cameraId);
    }

    boolean isStreaming(int cameraId) {
        return cameraStreamService != null && cameraStreamService.isStreaming(cameraId);
    }
}

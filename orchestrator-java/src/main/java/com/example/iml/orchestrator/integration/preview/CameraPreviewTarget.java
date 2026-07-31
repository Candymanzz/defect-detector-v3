package com.example.iml.orchestrator.integration.preview;

import com.example.iml.orchestrator.integration.camera.WorkerProcessSupervisor;

record CameraPreviewTarget(
        int cameraId,
        String productType,
        String detectorId,
        WorkerProcessSupervisor worker
) {
}

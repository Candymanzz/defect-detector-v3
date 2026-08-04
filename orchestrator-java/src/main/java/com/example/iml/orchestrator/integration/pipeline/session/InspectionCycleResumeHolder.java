package com.example.iml.orchestrator.integration.pipeline.session;

/**
 * Holder: ClientApi монтируется раньше trigger/bus, сервис resume подключается после bootstrap.
 */
public final class InspectionCycleResumeHolder {

    private volatile InspectionCycleResumeService service;

    public void set(InspectionCycleResumeService service) {
        this.service = service;
    }

    public InspectionCycleResumeService get() {
        return service;
    }

    public void resumeCamera(int cameraId) {
        InspectionCycleResumeService current = service;
        if (current != null) {
            current.resumeCamera(cameraId);
        }
    }
}

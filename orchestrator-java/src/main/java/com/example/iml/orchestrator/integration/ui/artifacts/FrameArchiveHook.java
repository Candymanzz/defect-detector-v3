package com.example.iml.orchestrator.integration.ui.artifacts;

import com.example.iml.orchestrator.integration.ui.FrameArchiveService;
import com.example.iml.orchestrator.integration.pipeline.InspectionDecision;

import java.nio.file.Path;
import java.util.function.Supplier;

/** Immediate frame-archive persistence after inspection publish. */
public final class FrameArchiveHook {

    private final Supplier<FrameArchiveService> archiveSupplier;

    public FrameArchiveHook(Supplier<FrameArchiveService> archiveSupplier) {
        this.archiveSupplier = archiveSupplier;
    }

    public boolean saveImmediately(
            int cameraId,
            long frameId,
            long inspectionId,
            String productType,
            String detectorId,
            InspectionDecision decision,
            Path frameJpeg,
            Path heatmapU8,
            int heatmapWidth,
            int heatmapHeight
    ) {
        FrameArchiveService archive = archiveSupplier.get();
        if (archive == null || !archive.enabled() || frameJpeg == null) {
            return false;
        }
        // После сброса эталона идут capture-only кадры (overallPass=false) — это не брак инспекции.
        if (isCaptureOnlyDecision(decision)) {
            return false;
        }
        return archive.saveImmediately(new FrameArchiveService.SaveRequest(
                cameraId,
                frameId,
                inspectionId,
                productType,
                detectorId,
                decision,
                frameJpeg,
                heatmapU8,
                heatmapWidth,
                heatmapHeight
        ));
    }

    /** Кадр без эталона: preview/capture-only, не результат инспекции для архива. */
    public static boolean isCaptureOnlyDecision(InspectionDecision decision) {
        if (decision == null) {
            return false;
        }
        if ("CAPTURE".equals(decision.action())) {
            return true;
        }
        String pythonStatus = decision.pythonStatus();
        return pythonStatus != null && "NO_REFERENCE".equalsIgnoreCase(pythonStatus.trim());
    }
}

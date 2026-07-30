package com.example.iml.orchestrator.integration.pipeline.spi;

import com.example.iml.orchestrator.integration.pipeline.InspectionDecision;
import com.example.iml.orchestrator.integration.pipeline.ReferenceSnapshot;
import com.example.iml.orchestrator.protocol.BinaryProtocol;

/**
 * Пост-обработка после инспекции (превью UI и т.п.) — узкий контракт без UiHttpServer в сигнатуре.
 */
public interface AfterInspectionSidecar {

    void scheduleAfterInspection(
            int cameraId,
            String productType,
            String detectorId,
            long inspectionId,
            ReferenceSnapshot activeReference,
            InspectionDecision decision,
            BinaryProtocol.Message capture,
            BinaryProtocol.Message python,
            BinaryProtocol.Message geometry
    );

    default void discardInspectionArtifacts(BinaryProtocol.Message python) {
    }
}

package com.example.iml.orchestrator.integration.pipeline.spi;

import com.example.iml.orchestrator.integration.binaryrpc.BinaryRpcSupervisor;
import com.example.iml.orchestrator.integration.pipeline.InspectionDecision;
import com.example.iml.orchestrator.integration.pipeline.ReferenceSnapshot;
import com.example.iml.orchestrator.integration.ui.UiHttpServer;
import com.example.iml.orchestrator.protocol.BinaryProtocol;

import java.util.Map;
import java.util.concurrent.ExecutorService;

/**
 * Пост-обработка после инспекции (превью UI и т.п.) — узкий контракт вместо жёсткой привязки к конкретному sidecar.
 */
public interface AfterInspectionSidecar {

    void scheduleAfterInspection(
            UiHttpServer uiServer,
            Map<String, Object> uiCfg,
            BinaryRpcSupervisor uiVisualsPython,
            ExecutorService uiArtifactsExecutor,
            int cameraId,
            String productType,
            String detectorId,
            ReferenceSnapshot activeReference,
            InspectionDecision decision,
            BinaryProtocol.Message capture,
            BinaryProtocol.Message python,
            BinaryProtocol.Message geometry
    );

    default void discardInspectionArtifacts(BinaryProtocol.Message python) {
    }
}

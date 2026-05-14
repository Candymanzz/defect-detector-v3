package com.example.iml.orchestrator.integration.pipeline.spi;

import com.example.iml.orchestrator.integration.services.ServiceProcessSupervisor;
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
            ServiceProcessSupervisor uiVisualsPython,
            ExecutorService uiArtifactsExecutor,
            int cameraId,
            String productType,
            String detectorId,
            BinaryProtocol.Message capture,
            BinaryProtocol.Message geometry
    );
}

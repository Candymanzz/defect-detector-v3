package com.example.iml.orchestrator.integration.pipeline.decision;

import com.example.iml.orchestrator.integration.pipeline.InspectionDecision;
import com.example.iml.orchestrator.protocol.BinaryProtocol;

/**
 * Политика агрегации ответов сервисов в итоговое решение по кадру (OCP: смена политики без правки пайплайна).
 */
public interface InspectionDecisionPolicy {

    InspectionDecision decide(
            int cameraId,
            BinaryProtocol.Message capture,
            BinaryProtocol.Message pyResp,
            BinaryProtocol.Message geomResp
    );
}

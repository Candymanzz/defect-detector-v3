package com.example.iml.orchestrator.integration.pipeline.fanoutbridge;

import com.example.iml.orchestrator.integration.fanout.FanOutEvent;
import com.example.iml.orchestrator.integration.pipeline.InspectionDecision;
import com.example.iml.orchestrator.integration.pipeline.spi.FanOutEventFactory;

/**
 * Маппинг доменного решения инспекции в контракт публикации fan-out (SRP: формат события в одном месте).
 */
public final class InspectionDecisionToFanOutEvent implements FanOutEventFactory {

    @Override
    public FanOutEvent toFanOut(InspectionDecision decision) {
        return new FanOutEvent(
                decision.cameraId(),
                decision.frameId(),
                decision.overallPass(),
                decision.action(),
                decision.anomalyScore(),
                decision.pythonStatus(),
                decision.geometryStatus(),
                System.currentTimeMillis()
        );
    }
}

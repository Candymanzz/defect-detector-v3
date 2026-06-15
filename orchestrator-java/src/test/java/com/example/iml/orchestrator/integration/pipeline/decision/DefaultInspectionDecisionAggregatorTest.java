package com.example.iml.orchestrator.integration.pipeline.decision;

import com.example.iml.orchestrator.integration.pipeline.InspectionDecision;
import com.example.iml.orchestrator.protocol.BinaryProtocol;
import org.apache.logging.log4j.LogManager;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class DefaultInspectionDecisionAggregatorTest {

    private final DefaultInspectionDecisionAggregator aggregator =
            new DefaultInspectionDecisionAggregator(LogManager.getLogger(getClass()));
    private final BinaryProtocol.Message capture =
            message(BinaryProtocol.MSG_RESPONSE, Map.of("frame_id", 42L));

    @Test
    void passesOnlyWhenPythonAndGeometryExplicitlyPass() {
        InspectionDecision decision = aggregator.decide(
                1,
                capture,
                message(BinaryProtocol.MSG_RESPONSE, Map.of(
                        "ok", true,
                        "status", "PASS",
                        "anomaly_score", 0.1
                )),
                message(BinaryProtocol.MSG_RESPONSE, Map.of(
                        "overallPass", true,
                        "status", "PASS"
                ))
        );

        assertTrue(decision.overallPass());
    }

    @Test
    void rejectsMissingOrAmbiguousStageResponses() {
        assertFalse(aggregator.decide(1, capture, null, null).overallPass());
        assertFalse(aggregator.decide(
                1,
                capture,
                message(BinaryProtocol.MSG_RESPONSE, Map.of("status", "PASS")),
                message(BinaryProtocol.MSG_RESPONSE, Map.of("overallPass", true))
        ).overallPass());
        assertFalse(aggregator.decide(
                1,
                capture,
                message(BinaryProtocol.MSG_RESPONSE, Map.of("ok", true, "status", "PASS")),
                message(BinaryProtocol.MSG_ERROR, Map.of("status", "ERROR"))
        ).overallPass());
    }

    private static BinaryProtocol.Message message(int type, Map<String, Object> header) {
        return new BinaryProtocol.Message(type, header, new byte[0]);
    }
}

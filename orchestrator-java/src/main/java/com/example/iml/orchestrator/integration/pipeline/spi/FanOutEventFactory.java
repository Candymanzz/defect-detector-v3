package com.example.iml.orchestrator.integration.pipeline.spi;

import com.example.iml.orchestrator.integration.fanout.FanOutEvent;
import com.example.iml.orchestrator.integration.pipeline.InspectionDecision;

/** Построение события fan-out из решения инспекции. */
public interface FanOutEventFactory {

    FanOutEvent toFanOut(InspectionDecision decision);
}

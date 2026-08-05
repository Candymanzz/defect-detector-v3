package com.example.iml.orchestrator.integration.trigger;

import com.example.iml.orchestrator.integration.bootstrap.lifecycle.OrchestratorStopSignal;
import com.example.iml.orchestrator.integration.trigger.parse.IoInputDiChange;
import org.apache.logging.log4j.LogManager;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class DiShutdownControllerTest {

    @Test
    void risingDi4RequestsStopOnce() {
        OrchestratorStopSignal stop = new OrchestratorStopSignal();
        DiShutdownController controller = new DiShutdownController(
                LogManager.getLogger(DiShutdownControllerTest.class),
                4,
                stop,
                null
        );

        controller.onDiChange(new IoInputDiChange(4, false));
        assertFalse(stop.isRequested());

        controller.onDiChange(new IoInputDiChange(4, true));
        assertTrue(stop.isRequested());
        assertEquals("di4_shutdown", stop.reason());

        controller.onDiChange(new IoInputDiChange(4, false));
        controller.onDiChange(new IoInputDiChange(4, true));
        assertEquals("di4_shutdown", stop.reason());
    }

    @Test
    void ignoresOtherPorts() {
        OrchestratorStopSignal stop = new OrchestratorStopSignal();
        DiShutdownController controller = new DiShutdownController(
                LogManager.getLogger(DiShutdownControllerTest.class),
                4,
                stop,
                null
        );

        controller.onDiChange(new IoInputDiChange(3, true));
        controller.onDiChange(new IoInputDiChange(1, true));
        assertFalse(stop.isRequested());
    }
}

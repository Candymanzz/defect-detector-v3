package com.example.iml.orchestrator.integration.bootstrap.pipeline;

import com.example.iml.orchestrator.integration.bootstrap.pipeline.api.AbstractBootstrapStage;
import com.example.iml.orchestrator.integration.bootstrap.pipeline.api.BootstrapPipeline;
import com.example.iml.orchestrator.integration.bootstrap.pipeline.api.BootstrapStage;
import com.example.iml.orchestrator.integration.bootstrap.pipeline.api.BootstrapStageResult;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class BootstrapPipelineTest {

    private final Logger log = LogManager.getLogger(getClass());

    @Test
    void chainsTypedStagesAndStopsOnEarlyExit() throws Exception {
        BootstrapStage<Integer, Integer> inc = new AbstractBootstrapStage<>(log, "inc") {
            @Override
            protected BootstrapStageResult<Integer> execute(Integer input) {
                return BootstrapStageResult.proceed(input + 1);
            }
        };
        BootstrapStage<Integer, String> stop = new AbstractBootstrapStage<>(log, "stop") {
            @Override
            protected BootstrapStageResult<String> execute(Integer input) {
                return BootstrapStageResult.stop();
            }
        };
        BootstrapStage<String, Integer> unreachable = new AbstractBootstrapStage<>(log, "unreachable") {
            @Override
            protected BootstrapStageResult<Integer> execute(String input) {
                return BootstrapStageResult.proceed(99);
            }
        };

        BootstrapPipeline<Integer> done = BootstrapPipeline.start(log, 1)
                .then(inc)
                .then(stop)
                .then(unreachable);

        assertFalse(done.isActive());
        assertNull(done.orElseNull());
    }

    @Test
    void proceedsThroughSuccessfulStages() throws Exception {
        BootstrapStage<Integer, String> toString = new AbstractBootstrapStage<>(log, "to-string") {
            @Override
            protected BootstrapStageResult<String> execute(Integer input) {
                return BootstrapStageResult.proceed("n=" + input);
            }
        };

        BootstrapPipeline<String> done = BootstrapPipeline.start(log, 10).then(toString);

        assertTrue(done.isActive());
        assertEquals("n=10", done.requireValue());
    }
}

package com.example.iml.orchestrator.integration.bootstrap.config;

import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;

class IntegrationBootConfigParallelCycleTest {

    @Test
    void readsDedicatedCycleParallelism() {
        IntegrationBootConfig config = IntegrationBootConfig.load(
                Map.of(
                        "camera_parallelism", 10,
                        "python_parallelism", 20,
                        "python_server_pool_size", 2,
                        "stage_queue_size", 40,
                        "inspection_cycle_parallelism", 20
                ),
                10,
                false
        );

        assertEquals(10, config.cameraParallelism());
        assertEquals(20, config.pythonParallelism());
        assertEquals(2, config.pythonServerPoolSize());
        assertEquals(40, config.stageQueueSize());
        assertEquals(20, config.inspectionCycleParallelism());
    }

    @Test
    void defaultsCycleParallelismToCameraParallelism() {
        IntegrationBootConfig config = IntegrationBootConfig.load(
                Map.of("camera_parallelism", 7),
                10,
                false
        );

        assertEquals(7, config.inspectionCycleParallelism());
    }
}

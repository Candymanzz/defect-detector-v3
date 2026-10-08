package com.example.iml.orchestrator.integration.bootstrap.config;

import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;

class IntegrationBootConfigTest {

    @Test
    void requestParallelismIsIndependentFromServerPoolSize() {
        IntegrationBootConfig config = IntegrationBootConfig.load(
                Map.of(
                        "camera_parallelism", 10,
                        "python_parallelism", 20,
                        "python_server_pool_size", 2,
                        "python_request_parallelism", 4
                ),
                10,
                true
        );

        assertEquals(2, config.pythonServerPoolSize());
        assertEquals(4, config.pythonRequestParallelism());
    }

    @Test
    void requestParallelismCannotExceedCameraParallelism() {
        IntegrationBootConfig config = IntegrationBootConfig.load(
                Map.of(
                        "camera_parallelism", 3,
                        "python_server_pool_size", 1,
                        "python_request_parallelism", 20
                ),
                10,
                true
        );

        assertEquals(3, config.pythonRequestParallelism());
    }
}

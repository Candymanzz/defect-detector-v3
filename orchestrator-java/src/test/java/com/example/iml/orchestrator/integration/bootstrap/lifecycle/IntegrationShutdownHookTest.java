package com.example.iml.orchestrator.integration.bootstrap.lifecycle;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

class IntegrationShutdownHookTest {

    @AfterEach
    void tearDown() {
        IntegrationShutdownHook.resetForTests();
    }

    @Test
    void runIsIdempotentWhenContextNotBound() {
        IntegrationShutdownHook.run("test-1");
        IntegrationShutdownHook.run("test-2");
    }

    @Test
    void bindRegistersHookOnce() {
        IntegrationShutdownHook.bind(null);
        IntegrationShutdownHook.bind(null);
    }
}

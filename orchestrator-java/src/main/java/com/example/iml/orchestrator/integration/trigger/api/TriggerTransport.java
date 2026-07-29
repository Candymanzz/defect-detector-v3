package com.example.iml.orchestrator.integration.trigger.api;

public interface TriggerTransport extends AutoCloseable {

    void start();

    @Override
    void close();
}

package com.example.iml.orchestrator.integration.trigger.transport;

public interface TriggerTransport extends AutoCloseable {

    void start();

    @Override
    void close();
}

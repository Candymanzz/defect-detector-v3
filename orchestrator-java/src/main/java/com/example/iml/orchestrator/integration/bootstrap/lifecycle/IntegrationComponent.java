package com.example.iml.orchestrator.integration.bootstrap.lifecycle;

/**
 * Управляемый компонент интеграции: старт и закрытие в порядке Composite.
 */
public interface IntegrationComponent extends AutoCloseable {

    void start() throws Exception;

    @Override
    void close();
}

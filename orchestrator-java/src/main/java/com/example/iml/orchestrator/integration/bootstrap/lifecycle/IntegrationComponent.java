package com.example.iml.orchestrator.integration.bootstrap.lifecycle;

import com.example.iml.orchestrator.integration.bootstrap.BootstrapException;

/**
 * Управляемый компонент интеграции: старт и закрытие в порядке Composite.
 */
public interface IntegrationComponent extends AutoCloseable {

    void start() throws BootstrapException;

    @Override
    void close();
}

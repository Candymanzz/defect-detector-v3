package com.example.iml.orchestrator.integration.bootstrap.context;

import com.example.iml.orchestrator.integration.bootstrap.factory.IntegrationServicePoolFactory;
import com.example.iml.orchestrator.integration.services.ServicePoolLifecycle;
import org.apache.logging.log4j.Logger;

import java.nio.file.Path;
import java.util.Map;
import java.util.Objects;

/**
 * Неизменяемое зерно bootstrap: конфиг, пути, фабрики пулов.
 */
public record BootstrapEnvironment(
        Logger log,
        Map<String, Object> root,
        Path projectRoot,
        boolean windows,
        ServicePoolLifecycle servicePools,
        IntegrationServicePoolFactory poolFactory
) {
    public BootstrapEnvironment {
        Objects.requireNonNull(log, "log");
        Objects.requireNonNull(root, "root");
        Objects.requireNonNull(projectRoot, "projectRoot");
        Objects.requireNonNull(servicePools, "servicePools");
        Objects.requireNonNull(poolFactory, "poolFactory");
    }
}

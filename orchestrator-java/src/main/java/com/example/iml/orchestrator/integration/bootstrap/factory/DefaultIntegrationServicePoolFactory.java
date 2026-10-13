package com.example.iml.orchestrator.integration.bootstrap.factory;

import com.example.iml.orchestrator.integration.binaryrpc.BinaryRpcSupervisor;
import com.example.iml.orchestrator.integration.bootstrap.config.IntegrationBootConfig;
import com.example.iml.orchestrator.integration.services.ServicePoolLifecycle;
import com.example.iml.orchestrator.integration.services.ServiceProcessSupervisor;
import org.apache.logging.log4j.Logger;

import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutorService;

/**
 * Default Abstract Factory: делегирует в {@link ServicePoolLifecycle}.
 */
public final class DefaultIntegrationServicePoolFactory implements IntegrationServicePoolFactory {

    private final Logger log;
    private final ServicePoolLifecycle servicePools;

    public DefaultIntegrationServicePoolFactory(Logger log, ServicePoolLifecycle servicePools) {
        this.log = log;
        this.servicePools = servicePools;
    }

    public ServicePoolLifecycle servicePools() {
        return servicePools;
    }

    @Override
    public List<BinaryRpcSupervisor> createPythonHttpPool(
            List<String> serverBaseUrls,
            IntegrationBootConfig cfg
    ) {
        return servicePools.startAnalisSurfaceHttpPool(
                serverBaseUrls,
                cfg.pythonParallelism(),
                cfg.serviceCommandTimeoutMs()
        );
    }

    @Override
    public List<ServiceProcessSupervisor> createGeometryPool(
            List<String> geometryCommand,
            Path projectRoot,
            IntegrationBootConfig cfg
    ) {
        return servicePools.startOptionalPool(
                geometryCommand,
                projectRoot,
                "java-geometry",
                cfg.serviceCommandTimeoutMs(),
                cfg.geometryPoolSize()
        );
    }

    @Override
    public List<ServiceProcessSupervisor> createPositioningPool(
            Map<String, Object> root,
            Map<String, Object> integration,
            List<String> positioningCommand,
            Path projectRoot,
            IntegrationBootConfig cfg
    ) {
        // Translation alignment now runs in analisSurface. Never start the old
        // positioning pool, including when an older local config says enabled=true.
        log.info("java-positioning disabled; bucket translation is aligned in analisSurface");
        return List.of();
    }

    @Override
    public ExecutorService createStageExecutor(String name, int parallelism, int queueSize) {
        return servicePools.newStageExecutor(name, parallelism, queueSize);
    }
}

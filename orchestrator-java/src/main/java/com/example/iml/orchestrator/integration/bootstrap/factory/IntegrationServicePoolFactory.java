package com.example.iml.orchestrator.integration.bootstrap.factory;

import com.example.iml.orchestrator.integration.binaryrpc.BinaryRpcSupervisor;
import com.example.iml.orchestrator.integration.bootstrap.config.IntegrationBootConfig;
import com.example.iml.orchestrator.integration.services.ServiceProcessSupervisor;

import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutorService;

/**
 * Abstract Factory пулов RPC/HTTP-сервисов и stage-executor'ов пайплайна.
 */
public interface IntegrationServicePoolFactory {

    List<BinaryRpcSupervisor> createPythonHttpPool(
            List<String> serverBaseUrls,
            IntegrationBootConfig cfg
    );

    List<ServiceProcessSupervisor> createGeometryPool(
            List<String> geometryCommand,
            Path projectRoot,
            IntegrationBootConfig cfg
    );

    List<ServiceProcessSupervisor> createPositioningPool(
            Map<String, Object> root,
            Map<String, Object> integration,
            List<String> positioningCommand,
            Path projectRoot,
            IntegrationBootConfig cfg
    );

    ExecutorService createStageExecutor(String name, int parallelism, int queueSize);
}

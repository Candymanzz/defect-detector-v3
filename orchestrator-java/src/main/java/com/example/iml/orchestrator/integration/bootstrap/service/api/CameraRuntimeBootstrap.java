package com.example.iml.orchestrator.integration.bootstrap.service.api;

import com.example.iml.orchestrator.integration.bootstrap.BootstrapException;

import com.example.iml.orchestrator.integration.bootstrap.context.CameraRuntimeContext;
import com.example.iml.orchestrator.integration.bootstrap.factory.IntegrationServicePoolFactory;
import com.example.iml.orchestrator.integration.bootstrap.lifecycle.IntegrationLifecycleComposite;

/**
 * Координатор camera-runtime: последовательность узких bootstrap-портов.
 */
public interface CameraRuntimeBootstrap {

    /**
     * @return {@code false} если workers не стартовали
     */
    boolean runBlocking(
            CameraRuntimeContext runtime,
            IntegrationServicePoolFactory poolFactory,
            IntegrationLifecycleComposite lifecycle
    ) throws BootstrapException;
}

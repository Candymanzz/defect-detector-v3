package com.example.iml.orchestrator.integration.bootstrap.pipeline.impl;

import com.example.iml.orchestrator.integration.bootstrap.pipeline.api.AbstractBootstrapStage;
import com.example.iml.orchestrator.integration.bootstrap.pipeline.api.BootstrapStageResult;

import com.example.iml.orchestrator.integration.bootstrap.context.CameraRuntimeContext;
import com.example.iml.orchestrator.integration.bootstrap.context.IntegrationRuntimeContext;
import com.example.iml.orchestrator.integration.bootstrap.lifecycle.IntegrationLifecycleComposite;
import com.example.iml.orchestrator.integration.bootstrap.service.api.CameraRuntimeBootstrap;
import org.apache.logging.log4j.Logger;

/**
 * Stage: blocking camera runtime (workers, triggers, inspection loops).
 */
public final class CameraRuntimeBootstrapStageImpl
        extends AbstractBootstrapStage<IntegrationRuntimeContext, IntegrationRuntimeContext> {

    private final CameraRuntimeBootstrap cameraRuntime;
    private final IntegrationLifecycleComposite lifecycle;

    public CameraRuntimeBootstrapStageImpl(
            Logger log,
            CameraRuntimeBootstrap cameraRuntime,
            IntegrationLifecycleComposite lifecycle
    ) {
        super(log, "camera-runtime");
        this.cameraRuntime = cameraRuntime;
        this.lifecycle = lifecycle;
    }

    @Override
    protected BootstrapStageResult<IntegrationRuntimeContext> execute(IntegrationRuntimeContext session)
            throws Exception {
        CameraRuntimeContext runtime = session.beginCameraRuntime();
        if (!cameraRuntime.runBlocking(runtime, session.environment().poolFactory(), lifecycle)) {
            return BootstrapStageResult.stop();
        }
        return BootstrapStageResult.proceed(session);
    }
}

package com.example.iml.orchestrator.integration.bootstrap;

import com.example.iml.orchestrator.integration.bootstrap.context.BootstrapEnvironment;
import com.example.iml.orchestrator.integration.bootstrap.context.IntegrationRuntimeContext;
import com.example.iml.orchestrator.integration.bootstrap.factory.DefaultIntegrationServicePoolFactory;
import com.example.iml.orchestrator.integration.bootstrap.lifecycle.IntegrationLifecycleComposite;
import com.example.iml.orchestrator.integration.bootstrap.lifecycle.IntegrationShutdownCoordinator;
import com.example.iml.orchestrator.integration.bootstrap.lifecycle.OrchestratorStopSignal;
import com.example.iml.orchestrator.integration.bootstrap.pipeline.api.BootstrapPipeline;
import com.example.iml.orchestrator.integration.bootstrap.pipeline.impl.CameraRuntimeBootstrapStageImpl;
import com.example.iml.orchestrator.integration.bootstrap.pipeline.impl.ChildProcessBootstrapStageImpl;
import com.example.iml.orchestrator.integration.bootstrap.pipeline.impl.CoreCollaboratorsBootstrapStageImpl;
import com.example.iml.orchestrator.integration.bootstrap.pipeline.impl.InspectionPipelineGraphBootstrapStageImpl;
import com.example.iml.orchestrator.integration.bootstrap.pipeline.impl.LightingEngageBootstrapStageImpl;
import com.example.iml.orchestrator.integration.bootstrap.pipeline.impl.PreflightBootstrapStageImpl;
import com.example.iml.orchestrator.integration.bootstrap.pipeline.impl.UiRuntimeBootstrapStageImpl;
import com.example.iml.orchestrator.integration.bootstrap.service.impl.ChildProcessStartupImpl;
import com.example.iml.orchestrator.integration.bootstrap.service.impl.CoreCollaboratorsBootstrapImpl;
import com.example.iml.orchestrator.integration.bootstrap.service.impl.InspectionPipelineGraphBootstrapImpl;
import com.example.iml.orchestrator.integration.bootstrap.service.impl.PreflightBootstrapImpl;
import com.example.iml.orchestrator.integration.bootstrap.service.impl.LightingEngageBootstrapImpl;
import com.example.iml.orchestrator.integration.bootstrap.service.impl.CameraRuntimeBootstrapImpl;
import com.example.iml.orchestrator.integration.bootstrap.service.impl.UiRuntimeBootstrapImpl;
import com.example.iml.orchestrator.integration.lighting.LightsShutdown;
import com.example.iml.orchestrator.integration.services.ServicePoolLifecycle;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import java.nio.file.Path;
import java.util.Map;

/**
 * Bootstrap pipeline (одна обязанность на stage/сервис):
 * preflight → core-collaborators → child-processes → pipeline-graph → lighting → ui → camera-runtime.
 */
public final class IntegrationBootstrap {

    private static final Logger log = LogManager.getLogger(IntegrationBootstrap.class);

    private final PreflightBootstrapImpl preflight = new PreflightBootstrapImpl(log);
    private final CoreCollaboratorsBootstrapImpl collaborators = new CoreCollaboratorsBootstrapImpl(log);
    private final ChildProcessStartupImpl childProcesses = new ChildProcessStartupImpl(log);
    private final InspectionPipelineGraphBootstrapImpl pipelineGraph =
            new InspectionPipelineGraphBootstrapImpl(log);
    private final LightingEngageBootstrapImpl lighting = new LightingEngageBootstrapImpl(log);
    private final UiRuntimeBootstrapImpl ui = new UiRuntimeBootstrapImpl(log);
    private final CameraRuntimeBootstrapImpl cameraRuntime = new CameraRuntimeBootstrapImpl(log);

    public void start(Map<String, Object> root, Path projectRoot) {
        boolean isWindows = System.getProperty("os.name", "").toLowerCase().contains("win");
        ServicePoolLifecycle servicePools = new ServicePoolLifecycle(log);
        DefaultIntegrationServicePoolFactory poolFactory =
                new DefaultIntegrationServicePoolFactory(log, servicePools);
        BootstrapEnvironment env = new BootstrapEnvironment(
                log,
                root,
                projectRoot,
                isWindows,
                servicePools,
                poolFactory
        );
        IntegrationLifecycleComposite lifecycle = new IntegrationLifecycleComposite(log);

        IntegrationRuntimeContext session = null;
        boolean resourcesStarted = false;
        try {
            BootstrapPipeline<IntegrationRuntimeContext> pipeline = BootstrapPipeline.start(log, env)
                    .then(new PreflightBootstrapStageImpl(log, preflight));
            session = pipeline.orElseNull();
            if (session == null) {
                return;
            }

            pipeline = pipeline
                    .then(new CoreCollaboratorsBootstrapStageImpl(log, collaborators))
                    .then(new ChildProcessBootstrapStageImpl(log, childProcesses));
            if (!pipeline.isActive()) {
                return;
            }
            session = pipeline.requireValue();
            resourcesStarted = true;

            pipeline = pipeline
                    .then(new InspectionPipelineGraphBootstrapStageImpl(log, pipelineGraph))
                    .then(new LightingEngageBootstrapStageImpl(log, lighting))
                    .then(new UiRuntimeBootstrapStageImpl(log, ui))
                    .then(new CameraRuntimeBootstrapStageImpl(log, cameraRuntime, lifecycle));
            if (pipeline.isActive()) {
                session = pipeline.requireValue();
            }
        } catch (Exception e) {
            log.error("Integration bootstrap failed", e);
            if (session != null) {
                session.fanOut().ifPresent(fanOut -> fanOut.signalVisionFault(true));
            }
        } finally {
            boolean exitJvm = false;
            String exitReason = null;
            if (resourcesStarted && session != null) {
                OrchestratorStopSignal stop = session.stopSignal().orElse(null);
                exitJvm = stop != null && stop.isRequested();
                exitReason = stop == null ? null : stop.reason();
                LightsShutdown.run("bootstrap-finally");
                lifecycle.close();
                IntegrationShutdownCoordinator.shutdownAll(session.toShutdownResources());
            }
            if (exitJvm) {
                log.warn("orchestrator process exit reason={}", exitReason == null ? "stop" : exitReason);
                System.exit(0);
            }
        }
    }
}

package com.example.iml.orchestrator.integration.bootstrap;

import com.example.iml.orchestrator.integration.bootstrap.context.BootstrapEnvironment;
import com.example.iml.orchestrator.integration.bootstrap.context.IntegrationRuntimeContext;
import com.example.iml.orchestrator.integration.bootstrap.BootstrapException;
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
import com.example.iml.orchestrator.integration.bootstrap.service.api.CameraRuntimeBootstrap;
import com.example.iml.orchestrator.integration.bootstrap.service.api.ChildProcessStartup;
import com.example.iml.orchestrator.integration.bootstrap.service.api.CoreCollaboratorsBootstrap;
import com.example.iml.orchestrator.integration.bootstrap.service.api.InspectionPipelineGraphBootstrap;
import com.example.iml.orchestrator.integration.bootstrap.service.api.LightingEngageBootstrap;
import com.example.iml.orchestrator.integration.bootstrap.service.api.PreflightBootstrap;
import com.example.iml.orchestrator.integration.bootstrap.service.api.UiRuntimeBootstrap;
import com.example.iml.orchestrator.integration.bootstrap.service.impl.CameraRuntimeBootstrapImpl;
import com.example.iml.orchestrator.integration.bootstrap.service.impl.ChildProcessStartupImpl;
import com.example.iml.orchestrator.integration.bootstrap.service.impl.CoreCollaboratorsBootstrapImpl;
import com.example.iml.orchestrator.integration.bootstrap.service.impl.InspectionPipelineGraphBootstrapImpl;
import com.example.iml.orchestrator.integration.bootstrap.service.impl.LightingEngageBootstrapImpl;
import com.example.iml.orchestrator.integration.bootstrap.service.impl.PreflightBootstrapImpl;
import com.example.iml.orchestrator.integration.bootstrap.service.impl.UiRuntimeBootstrapImpl;
import com.example.iml.orchestrator.integration.lighting.LightsShutdown;
import com.example.iml.orchestrator.integration.services.ServicePoolLifecycle;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import java.nio.file.Path;
import java.util.Map;
import java.util.Objects;

/**
 * Bootstrap pipeline (одна обязанность на stage/сервис):
 * preflight → core-collaborators → child-processes → pipeline-graph → lighting → ui → camera-runtime.
 * Composition root: depends on bootstrap APIs; {@code *Impl} is the default wiring.
 */
public final class IntegrationBootstrap {

    private static final Logger log = LogManager.getLogger(IntegrationBootstrap.class);

    private final PreflightBootstrap preflight;
    private final CoreCollaboratorsBootstrap collaborators;
    private final ChildProcessStartup childProcesses;
    private final InspectionPipelineGraphBootstrap pipelineGraph;
    private final LightingEngageBootstrap lighting;
    private final UiRuntimeBootstrap ui;
    private final CameraRuntimeBootstrap cameraRuntime;

    public IntegrationBootstrap() {
        this(
                new PreflightBootstrapImpl(log),
                new CoreCollaboratorsBootstrapImpl(log),
                new ChildProcessStartupImpl(log),
                new InspectionPipelineGraphBootstrapImpl(log),
                new LightingEngageBootstrapImpl(log),
                new UiRuntimeBootstrapImpl(log),
                new CameraRuntimeBootstrapImpl(log)
        );
    }

    public IntegrationBootstrap(
            PreflightBootstrap preflight,
            CoreCollaboratorsBootstrap collaborators,
            ChildProcessStartup childProcesses,
            InspectionPipelineGraphBootstrap pipelineGraph,
            LightingEngageBootstrap lighting,
            UiRuntimeBootstrap ui,
            CameraRuntimeBootstrap cameraRuntime
    ) {
        this.preflight = Objects.requireNonNull(preflight, "preflight");
        this.collaborators = Objects.requireNonNull(collaborators, "collaborators");
        this.childProcesses = Objects.requireNonNull(childProcesses, "childProcesses");
        this.pipelineGraph = Objects.requireNonNull(pipelineGraph, "pipelineGraph");
        this.lighting = Objects.requireNonNull(lighting, "lighting");
        this.ui = Objects.requireNonNull(ui, "ui");
        this.cameraRuntime = Objects.requireNonNull(cameraRuntime, "cameraRuntime");
    }

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
        } catch (BootstrapException e) {
            log.error("Integration bootstrap failed", e);
            if (session != null) {
                session.fanOut().ifPresent(fanOut -> fanOut.signalVisionFault(true));
            }
            throw e;
        } catch (RuntimeException e) {
            log.error("Integration bootstrap failed", e);
            if (session != null) {
                session.fanOut().ifPresent(fanOut -> fanOut.signalVisionFault(true));
            }
            throw new BootstrapException("Integration bootstrap failed", e);
        } catch (Exception e) {
            log.error("Integration bootstrap failed", e);
            if (session != null) {
                session.fanOut().ifPresent(fanOut -> fanOut.signalVisionFault(true));
            }
            throw new BootstrapException("Integration bootstrap failed", e);
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

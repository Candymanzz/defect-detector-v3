package com.example.iml.orchestrator.integration.bootstrap;

import com.example.iml.orchestrator.integration.bootstrap.context.IntegrationRuntimeContext;
import com.example.iml.orchestrator.integration.bootstrap.factory.DefaultIntegrationServicePoolFactory;
import com.example.iml.orchestrator.integration.bootstrap.lifecycle.IntegrationLifecycleComposite;
import com.example.iml.orchestrator.integration.bootstrap.lifecycle.IntegrationShutdownCoordinator;
import com.example.iml.orchestrator.integration.bootstrap.service.ChildProcessStartupService;
import com.example.iml.orchestrator.integration.bootstrap.service.IntegrationPreflightService;
import com.example.iml.orchestrator.integration.bootstrap.service.LightingBootstrapService;
import com.example.iml.orchestrator.integration.bootstrap.service.PipelineCameraRuntimeService;
import com.example.iml.orchestrator.integration.bootstrap.service.UiRuntimeBootstrapService;
import com.example.iml.orchestrator.integration.lighting.LightsShutdown;
import com.example.iml.orchestrator.integration.services.ServicePoolLifecycle;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import java.nio.file.Path;
import java.util.Map;

/**
 * Тонкий оркестратор интеграции: делегирует старт доменным сервисам, shutdown — coordinator + Composite.
 */
public final class IntegrationBootstrap {

    private static final Logger log = LogManager.getLogger(IntegrationBootstrap.class);

    private final IntegrationPreflightService preflight = new IntegrationPreflightService(log);
    private final ChildProcessStartupService childProcesses = new ChildProcessStartupService(log);
    private final LightingBootstrapService lighting = new LightingBootstrapService(log);
    private final UiRuntimeBootstrapService ui = new UiRuntimeBootstrapService(log);
    private final PipelineCameraRuntimeService cameraRuntime = new PipelineCameraRuntimeService(log);

    public void start(Map<String, Object> root, Path projectRoot) {
        boolean isWindows = System.getProperty("os.name", "").toLowerCase().contains("win");
        IntegrationRuntimeContext ctx = new IntegrationRuntimeContext(log, root, projectRoot, isWindows);
        ServicePoolLifecycle servicePools = new ServicePoolLifecycle(log);
        ctx.setServicePools(servicePools);
        DefaultIntegrationServicePoolFactory poolFactory =
                new DefaultIntegrationServicePoolFactory(log, servicePools);
        IntegrationLifecycleComposite lifecycle = new IntegrationLifecycleComposite(log);

        boolean resourcesStarted = false;
        try {
            if (!preflight.run(ctx)) {
                return;
            }
            if (!childProcesses.start(ctx, poolFactory)) {
                return;
            }
            resourcesStarted = true;
            lighting.assemblePipelineAndEngageLights(ctx);
            ui.bootstrap(ctx);
            cameraRuntime.runBlocking(ctx, poolFactory, lifecycle);
        } catch (Exception e) {
            log.error("Integration bootstrap failed", e);
            if (ctx.fanOut() != null) {
                ctx.fanOut().signalVisionFault(true);
            }
        } finally {
            if (resourcesStarted) {
                // Off + kill LightServer здесь и в JVM hook (Ctrl+C), идемпотентно.
                LightsShutdown.run("bootstrap-finally");
                lifecycle.close();
                IntegrationShutdownCoordinator.shutdownAll(ctx.toShutdownResources());
            }
        }
    }
}

package com.example.iml.orchestrator.integration.bootstrap.service.impl;

import com.example.iml.orchestrator.integration.bootstrap.context.ChildProcessesContext;
import com.example.iml.orchestrator.integration.bootstrap.factory.IntegrationServicePoolFactory;
import com.example.iml.orchestrator.integration.config.CameraWorkerPaths;
import com.example.iml.orchestrator.integration.lighting.LightServerLauncher;
import com.example.iml.orchestrator.integration.lighting.LightServersConfig;
import com.example.iml.orchestrator.integration.services.ServiceProcessSupervisor;
import com.example.iml.orchestrator.integration.subprocess.ExternalServiceProcess;
import com.example.iml.orchestrator.integration.subprocess.IntegrationExternalProcessLauncher;
import com.example.iml.orchestrator.integration.trigger.config.InspectionTriggerConfig;
import org.apache.logging.log4j.Logger;

import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.ExecutorService;

/** Parallel child-process futures + join helpers for {@link ChildProcessStartupImpl}. */
final class ChildProcessParallelLaunchSupport {

    private ChildProcessParallelLaunchSupport() {
    }

    record ExternalLaunchFutures(
            CompletableFuture<ExternalServiceProcess> light,
            CompletableFuture<ExternalServiceProcess> io,
            CompletableFuture<ExternalServiceProcess> frontend
    ) {
    }

    static ExternalLaunchFutures startExternalFutures(
            ChildProcessesContext processes,
            LightServerLauncher lightServerLauncher,
            IntegrationExternalProcessLauncher externalProcessLauncher,
            ExecutorService parallel,
            Logger log
    ) {
        var env = processes.env();
        var preflight = processes.preflight();
        LightServersConfig lightServersCfg = LightServersConfig.fromRootYaml(env.root());
        InspectionTriggerConfig inspectionTriggerConfig = InspectionTriggerConfig.parse(preflight.integration());

        CompletableFuture<ExternalServiceProcess> lightFuture = CompletableFuture.supplyAsync(() -> {
            if (!lightServersCfg.enabled()) {
                log.info("light_servers.enabled=false — LightServer не запускается, COM-вспышки отключены");
                return null;
            }
            return lightServerLauncher.startIfConfigured(
                    preflight.integration(),
                    env.projectRoot(),
                    env.windows(),
                    preflight.bootConfig().lightStartupDelayMs());
        }, parallel);

        CompletableFuture<ExternalServiceProcess> ioFuture = CompletableFuture.supplyAsync(() -> {
            if (!inspectionTriggerConfig.usesIoInputMonitor()) {
                return null;
            }
            return externalProcessLauncher.startIfConfigured(
                    preflight.integration(),
                    env.projectRoot(),
                    env.windows(),
                    "io_input_monitor_autostart",
                    "io_input_monitor_command_windows",
                    "io_input_monitor_command_linux",
                    "io-input-monitor",
                    "."
            );
        }, parallel);

        CompletableFuture<ExternalServiceProcess> frontendFuture = CompletableFuture.supplyAsync(() -> {
            if (!shouldAutostartFrontend()) {
                return null;
            }
            return externalProcessLauncher.startIfConfigured(
                    preflight.integration(),
                    env.projectRoot(),
                    env.windows(),
                    "frontend_autostart",
                    "frontend_command_windows",
                    "frontend_command_linux",
                    "frontend",
                    "front-end"
            );
        }, parallel);

        return new ExternalLaunchFutures(lightFuture, ioFuture, frontendFuture);
    }

    static void startGeometryAndPositioning(
            ChildProcessesContext processes,
            IntegrationServicePoolFactory poolFactory,
            ExecutorService parallel,
            Logger log
    ) {
        var env = processes.env();
        var preflight = processes.preflight();
        List<String> positioningCommand = CameraWorkerPaths.pickIntegrationCommandList(
                preflight.integration(), env.windows(), "positioning_command_windows", "positioning_command_linux");

        CompletableFuture<List<ServiceProcessSupervisor>> geometryFuture = CompletableFuture.supplyAsync(
                () -> poolFactory.createGeometryPool(
                        preflight.bootConfig().geometryCommand(),
                        env.projectRoot(),
                        preflight.bootConfig()
                ),
                parallel
        );
        CompletableFuture<List<ServiceProcessSupervisor>> positioningFuture = CompletableFuture.supplyAsync(
                () -> poolFactory.createPositioningPool(
                        env.root(),
                        preflight.integration(),
                        positioningCommand,
                        env.projectRoot(),
                        preflight.bootConfig()
                ),
                parallel
        );

        processes.setGeometryPool(joinPool(geometryFuture, "geometry", log));
        processes.setPositioningPool(joinPool(positioningFuture, "positioning", log));
    }

    static List<ServiceProcessSupervisor> joinPool(
            CompletableFuture<List<ServiceProcessSupervisor>> future,
            String label,
            Logger log
    ) {
        try {
            List<ServiceProcessSupervisor> pool = future.join();
            return pool == null ? List.of() : pool;
        } catch (CompletionException e) {
            Throwable cause = e.getCause() != null ? e.getCause() : e;
            log.warn("failed to start {} pool in parallel: {}", label, cause.getMessage());
            return List.of();
        }
    }

    static ExternalServiceProcess joinExternal(
            CompletableFuture<ExternalServiceProcess> future,
            String label,
            Logger log
    ) {
        try {
            return future.join();
        } catch (CompletionException e) {
            Throwable cause = e.getCause() != null ? e.getCause() : e;
            log.warn("failed to start {} in parallel: {}", label, cause.getMessage());
            return null;
        }
    }

    static void closeQuietly(ExternalServiceProcess process) {
        if (process != null) {
            process.close();
        }
    }

    /** {@code IML_FRONTEND_AUTOSTART=false} — отключить UI при {@code run.ps1 -NoFrontend}. */
    static boolean shouldAutostartFrontend() {
        String raw = System.getenv("IML_FRONTEND_AUTOSTART");
        return raw == null || !raw.equalsIgnoreCase("false");
    }
}

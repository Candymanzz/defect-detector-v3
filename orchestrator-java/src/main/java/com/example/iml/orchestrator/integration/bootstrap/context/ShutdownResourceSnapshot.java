package com.example.iml.orchestrator.integration.bootstrap.context;

import com.example.iml.orchestrator.integration.binaryrpc.BinaryRpcSupervisor;
import com.example.iml.orchestrator.integration.bootstrap.lifecycle.IntegrationShutdownCoordinator.ShutdownResources;
import com.example.iml.orchestrator.integration.camera.WorkerProcessSupervisor;
import com.example.iml.orchestrator.integration.clientws.ClientWebSocketServer;
import com.example.iml.orchestrator.integration.fanout.FanOutCoordinator;
import com.example.iml.orchestrator.integration.lighting.LightTriggerClient;
import com.example.iml.orchestrator.integration.logging.PipelineStagesLog;
import com.example.iml.orchestrator.integration.services.ServicePoolLifecycle;
import com.example.iml.orchestrator.integration.subprocess.ExternalServiceProcess;
import com.example.iml.orchestrator.integration.ui.FrameArchiveService;
import com.example.iml.orchestrator.integration.ui.UiHttpServer;
import org.apache.logging.log4j.Logger;

import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.ScheduledExecutorService;

/**
 * Промежуточный снимок ресурсов для shutdown; маппится в {@link ShutdownResources}.
 */
public record ShutdownResourceSnapshot(
        ExecutorsSlice executors,
        WorkersSlice workers,
        ProcessesSlice processes,
        UiSlice ui,
        FanOutCoordinator fanOut,
        ServicePoolLifecycle servicePools,
        Logger log
) {

    public record ExecutorsSlice(
            PipelineStagesLog pipelineStagesLog,
            ExecutorService cameraExecutor,
            ExecutorService captureStageExecutor,
            ExecutorService pythonStageExecutor,
            ExecutorService geometryStageExecutor,
            ExecutorService decisionStageExecutor,
            ScheduledExecutorService shmJanitorScheduler
    ) {
        static ExecutorsSlice empty() {
            return new ExecutorsSlice(null, null, null, null, null, null, null);
        }
    }

    public record WorkersSlice(Map<Integer, WorkerProcessSupervisor> workersByCamera) {
        static WorkersSlice empty() {
            return new WorkersSlice(Map.of());
        }
    }

    public record ProcessesSlice(
            List<? extends BinaryRpcSupervisor> pythonPool,
            List<? extends BinaryRpcSupervisor> geometryPool,
            List<? extends BinaryRpcSupervisor> positioningPool,
            ExternalServiceProcess lightServerProcess,
            ExternalServiceProcess ioInputMonitorProcess,
            ExternalServiceProcess frontendProcess,
            List<ExternalServiceProcess> analisSurfaceProcesses,
            LightTriggerClient lightTriggerClient
    ) {
    }

    public record UiSlice(
            BinaryRpcSupervisor uiVisualsPython,
            ExecutorService uiArtifactsExecutor,
            ClientWebSocketServer clientWebSocketServer,
            UiHttpServer uiServer,
            FrameArchiveService frameArchiveService
    ) {
        static UiSlice empty() {
            return new UiSlice(null, null, null, null, null);
        }
    }

    public ShutdownResources toShutdownResources() {
        return new ShutdownResources(
                executors.pipelineStagesLog(),
                executors.cameraExecutor(),
                executors.captureStageExecutor(),
                executors.pythonStageExecutor(),
                executors.geometryStageExecutor(),
                executors.decisionStageExecutor(),
                executors.shmJanitorScheduler(),
                workers.workersByCamera(),
                processes.pythonPool(),
                processes.geometryPool(),
                processes.positioningPool(),
                processes.lightServerProcess(),
                processes.ioInputMonitorProcess(),
                processes.frontendProcess(),
                processes.analisSurfaceProcesses(),
                processes.lightTriggerClient(),
                ui.uiVisualsPython(),
                ui.uiArtifactsExecutor(),
                fanOut,
                ui.clientWebSocketServer(),
                ui.uiServer(),
                ui.frameArchiveService(),
                servicePools,
                log
        );
    }

    public static ShutdownResourceSnapshot from(CameraRuntimeContext runtime) {
        Objects.requireNonNull(runtime, "runtime");
        ChildProcessesContext p = runtime.processes();
        UiRuntimeContext uiCtx = runtime.ui();
        return new ShutdownResourceSnapshot(
                new ExecutorsSlice(
                        runtime.stages().pipelineStagesLog(),
                        runtime.stages().cameraExecutor(),
                        runtime.stages().captureStageExecutor(),
                        runtime.stages().pythonStageExecutor(),
                        runtime.stages().geometryStageExecutor(),
                        runtime.stages().decisionStageExecutor(),
                        runtime.stages().shmJanitorScheduler()
                ),
                new WorkersSlice(runtime.workers().workersByCamera()),
                new ProcessesSlice(
                        p.pythonPool(),
                        p.geometryPool(),
                        p.positioningPool(),
                        p.lightServerProcess(),
                        p.ioInputMonitorProcess(),
                        p.frontendProcess(),
                        p.analisSurfaceProcesses(),
                        runtime.pipeline().lightClient()
                ),
                new UiSlice(
                        uiCtx.uiVisualsPython(),
                        uiCtx.uiArtifactsExecutor(),
                        uiCtx.clientWsServer(),
                        uiCtx.uiServer(),
                        uiCtx.frameArchiveService()
                ),
                runtime.health().fanOut(),
                runtime.env().servicePools(),
                runtime.env().log()
        );
    }

    public static ShutdownResourceSnapshot beforeCameraRuntime(
            ChildProcessesContext processes,
            Optional<PipelineAssemblyContext> pipeline,
            Optional<UiRuntimeContext> ui,
            BootstrapEnvironment env
    ) {
        Objects.requireNonNull(processes, "processes");
        Objects.requireNonNull(env, "env");
        Optional<UiRuntimeContext> uiOpt = ui == null ? Optional.empty() : ui;
        Optional<PipelineAssemblyContext> pipelineOpt = pipeline == null ? Optional.empty() : pipeline;
        return new ShutdownResourceSnapshot(
                ExecutorsSlice.empty(),
                WorkersSlice.empty(),
                new ProcessesSlice(
                        processes.pythonPool(),
                        processes.geometryPool(),
                        processes.positioningPool(),
                        processes.lightServerProcess(),
                        processes.ioInputMonitorProcess(),
                        processes.frontendProcess(),
                        processes.analisSurfaceProcesses(),
                        pipelineOpt.map(PipelineAssemblyContext::lightClient).orElse(null)
                ),
                uiOpt.map(u -> new UiSlice(
                        u.uiVisualsPython(),
                        u.uiArtifactsExecutor(),
                        u.clientWsServer(),
                        u.uiServer(),
                        u.frameArchiveService()
                )).orElseGet(UiSlice::empty),
                null,
                env.servicePools(),
                env.log()
        );
    }
}

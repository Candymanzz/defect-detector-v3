package com.example.iml.orchestrator.integration.bootstrap.context;

import com.example.iml.orchestrator.integration.bootstrap.lifecycle.IntegrationShutdownCoordinator;
import com.example.iml.orchestrator.integration.fanout.FanOutCoordinator;

import java.util.Objects;
import java.util.Optional;

/**
 * Session bootstrap: накопление typed-слоёв и lifecycle.
 * Необязательные поздние слои — через {@link Optional}, без каскада {@code == null}.
 */
public final class IntegrationRuntimeContext {

    private final BootstrapEnvironment env;
    private PreflightContext preflight;
    private ChildProcessesContext processes;
    private PipelineAssemblyContext pipelineAssembly;
    private UiRuntimeContext uiRuntime;
    private CameraRuntimeContext cameraRuntime;

    public IntegrationRuntimeContext(BootstrapEnvironment env) {
        this.env = Objects.requireNonNull(env, "env");
    }

    public BootstrapEnvironment environment() {
        return env;
    }

    public PreflightContext beginPreflight() {
        this.preflight = new PreflightContext(env);
        return preflight;
    }

    public PreflightContext preflightContext() {
        return require(preflight, "preflight");
    }

    public ChildProcessesContext beginChildProcesses() {
        this.processes = new ChildProcessesContext(require(preflight, "preflight"));
        return processes;
    }

    public ChildProcessesContext childProcessesContext() {
        return require(processes, "child-processes");
    }

    public PipelineAssemblyContext beginPipelineAssembly() {
        this.pipelineAssembly = new PipelineAssemblyContext(require(processes, "child-processes"));
        return pipelineAssembly;
    }

    public PipelineAssemblyContext pipelineAssemblyContext() {
        return require(pipelineAssembly, "pipeline-assembly");
    }

    public UiRuntimeContext beginUiRuntime() {
        this.uiRuntime = new UiRuntimeContext(require(pipelineAssembly, "pipeline-assembly"));
        return uiRuntime;
    }

    public UiRuntimeContext uiRuntimeContext() {
        return require(uiRuntime, "ui-runtime");
    }

    public CameraRuntimeContext beginCameraRuntime() {
        this.cameraRuntime = new CameraRuntimeContext(require(uiRuntime, "ui-runtime"));
        return cameraRuntime;
    }

    public CameraRuntimeContext cameraRuntimeContext() {
        return require(cameraRuntime, "camera-runtime");
    }

    public Optional<FanOutCoordinator> fanOut() {
        return Optional.ofNullable(cameraRuntime).map(c -> c.health().fanOut());
    }

    public IntegrationShutdownCoordinator.ShutdownResources toShutdownResources() {
        return Optional.ofNullable(cameraRuntime)
                .map(CameraRuntimeContext::toShutdownResources)
                .orElseGet(this::shutdownBeforeCameraRuntime);
    }

    private IntegrationShutdownCoordinator.ShutdownResources shutdownBeforeCameraRuntime() {
        return ShutdownResourceSnapshot.beforeCameraRuntime(
                require(processes, "child-processes"),
                Optional.ofNullable(pipelineAssembly),
                Optional.ofNullable(uiRuntime),
                env
        ).toShutdownResources();
    }

    private static <T> T require(T value, String name) {
        return Objects.requireNonNull(value, () -> name + " context is not initialized");
    }
}

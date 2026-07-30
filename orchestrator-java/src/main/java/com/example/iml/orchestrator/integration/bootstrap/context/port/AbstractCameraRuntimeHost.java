package com.example.iml.orchestrator.integration.bootstrap.context.port;

import com.example.iml.orchestrator.integration.bootstrap.config.IntegrationBootConfig;
import com.example.iml.orchestrator.integration.bootstrap.context.BootstrapEnvironment;
import com.example.iml.orchestrator.integration.bootstrap.context.CameraRuntimeContext;
import com.example.iml.orchestrator.integration.bootstrap.context.ChildProcessesContext;
import com.example.iml.orchestrator.integration.bootstrap.context.PipelineAssemblyContext;
import com.example.iml.orchestrator.integration.bootstrap.context.PreflightContext;
import com.example.iml.orchestrator.integration.bootstrap.context.UiRuntimeContext;
import com.example.iml.orchestrator.integration.bootstrap.context.state.CameraHealthControlState;
import com.example.iml.orchestrator.integration.bootstrap.context.state.CameraPreviewState;
import com.example.iml.orchestrator.integration.bootstrap.context.state.CameraStageRuntimeState;
import com.example.iml.orchestrator.integration.bootstrap.context.state.CameraTriggerState;
import com.example.iml.orchestrator.integration.bootstrap.context.state.CameraWorkersState;

import java.nio.file.Path;
import java.util.Map;
import java.util.Objects;

/**
 * Общая база Host-реализаций: доступ к слоям {@link CameraRuntimeContext} без копипаста.
 */
public abstract class AbstractCameraRuntimeHost {

    protected final CameraRuntimeContext runtime;

    protected AbstractCameraRuntimeHost(CameraRuntimeContext runtime) {
        this.runtime = Objects.requireNonNull(runtime, "runtime");
    }

    protected final BootstrapEnvironment env() {
        return runtime.env();
    }

    protected final PreflightContext preflight() {
        return runtime.preflight();
    }

    protected final ChildProcessesContext processes() {
        return runtime.processes();
    }

    protected final PipelineAssemblyContext pipeline() {
        return runtime.pipeline();
    }

    protected final UiRuntimeContext ui() {
        return runtime.ui();
    }

    protected final CameraWorkersState workers() {
        return runtime.workers();
    }

    protected final CameraHealthControlState health() {
        return runtime.health();
    }

    protected final CameraTriggerState triggers() {
        return runtime.triggers();
    }

    protected final CameraPreviewState preview() {
        return runtime.preview();
    }

    protected final CameraStageRuntimeState stages() {
        return runtime.stages();
    }

    protected final Map<String, Object> rootMap() {
        return env().root();
    }

    protected final Path projectRootPath() {
        return env().projectRoot();
    }

    protected final IntegrationBootConfig bootCfg() {
        return preflight().bootConfig();
    }
}

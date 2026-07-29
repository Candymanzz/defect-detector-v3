package com.example.iml.orchestrator.integration.bootstrap.context;

import com.example.iml.orchestrator.integration.bootstrap.context.state.CameraHealthControlState;
import com.example.iml.orchestrator.integration.bootstrap.context.state.CameraPreviewState;
import com.example.iml.orchestrator.integration.bootstrap.context.state.CameraStageRuntimeState;
import com.example.iml.orchestrator.integration.bootstrap.context.state.CameraTriggerState;
import com.example.iml.orchestrator.integration.bootstrap.context.state.CameraWorkersState;
import com.example.iml.orchestrator.integration.bootstrap.lifecycle.IntegrationComponent;
import com.example.iml.orchestrator.integration.bootstrap.lifecycle.IntegrationShutdownCoordinator;

import java.util.List;
import java.util.Objects;

/**
 * Composition root camera-runtime: держит только ссылку на UI-слой и узкие state-срезы.
 * Поля и мутации — в {@code state/*}; порты для bootstrap — в {@code adapter/*}.
 */
public final class CameraRuntimeContext {

    private final UiRuntimeContext ui;
    private final CameraWorkersState workers = new CameraWorkersState();
    private final CameraHealthControlState health = new CameraHealthControlState();
    private final CameraTriggerState triggers = new CameraTriggerState();
    private final CameraPreviewState preview = new CameraPreviewState();
    private final CameraStageRuntimeState stages = new CameraStageRuntimeState();

    public CameraRuntimeContext(UiRuntimeContext ui) {
        this.ui = Objects.requireNonNull(ui, "ui");
    }

    public UiRuntimeContext ui() {
        return ui;
    }

    public PipelineAssemblyContext pipeline() {
        return ui.pipeline();
    }

    public ChildProcessesContext processes() {
        return ui.processes();
    }

    public PreflightContext preflight() {
        return ui.preflight();
    }

    public BootstrapEnvironment env() {
        return ui.env();
    }

    public CameraWorkersState workers() {
        return workers;
    }

    public CameraHealthControlState health() {
        return health;
    }

    public CameraTriggerState triggers() {
        return triggers;
    }

    public CameraPreviewState preview() {
        return preview;
    }

    public CameraStageRuntimeState stages() {
        return stages;
    }

    public List<IntegrationComponent> managedRuntimeComponents() {
        return ManagedRuntimeComponents.from(this).toLifecycleComponents();
    }

    public IntegrationShutdownCoordinator.ShutdownResources toShutdownResources() {
        return ShutdownResourceSnapshot.from(this).toShutdownResources();
    }
}

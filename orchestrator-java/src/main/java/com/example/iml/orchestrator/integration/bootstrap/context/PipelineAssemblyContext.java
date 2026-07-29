package com.example.iml.orchestrator.integration.bootstrap.context;

import com.example.iml.orchestrator.integration.lighting.LightBrightnessStore;
import com.example.iml.orchestrator.integration.lighting.LightTriggerClient;
import com.example.iml.orchestrator.integration.pipeline.InspectionPipeline;
import com.example.iml.orchestrator.integration.pipeline.reference.PipelineReferenceRegistry;

import java.util.Map;
import java.util.Objects;

/**
 * Результат сборки {@link InspectionPipeline} + engage освещения.
 */
public final class PipelineAssemblyContext {

    private final ChildProcessesContext processes;

    private InspectionPipeline inspectionPipeline;
    private PipelineReferenceRegistry pipelineReferenceRegistry;
    private Map<Integer, String> detectorByCamera = Map.of();
    private LightBrightnessStore lightBrightnessStore;
    private LightTriggerClient lightClient;
    private int flashLeadMs;

    public PipelineAssemblyContext(ChildProcessesContext processes) {
        this.processes = Objects.requireNonNull(processes, "processes");
    }

    public ChildProcessesContext processes() {
        return processes;
    }

    public PreflightContext preflight() {
        return processes.preflight();
    }

    public BootstrapEnvironment env() {
        return processes.env();
    }

    public InspectionPipeline inspectionPipeline() {
        return inspectionPipeline;
    }

    public void setInspectionPipeline(InspectionPipeline inspectionPipeline) {
        this.inspectionPipeline = inspectionPipeline;
    }

    public PipelineReferenceRegistry pipelineReferenceRegistry() {
        return pipelineReferenceRegistry;
    }

    public void setPipelineReferenceRegistry(PipelineReferenceRegistry pipelineReferenceRegistry) {
        this.pipelineReferenceRegistry = pipelineReferenceRegistry;
    }

    public Map<Integer, String> detectorByCamera() {
        return detectorByCamera;
    }

    public void setDetectorByCamera(Map<Integer, String> detectorByCamera) {
        this.detectorByCamera = detectorByCamera == null ? Map.of() : detectorByCamera;
    }

    public LightBrightnessStore lightBrightnessStore() {
        return lightBrightnessStore;
    }

    public void setLightBrightnessStore(LightBrightnessStore lightBrightnessStore) {
        this.lightBrightnessStore = lightBrightnessStore;
    }

    public LightTriggerClient lightClient() {
        return lightClient;
    }

    public void setLightClient(LightTriggerClient lightClient) {
        this.lightClient = lightClient;
    }

    public int flashLeadMs() {
        return flashLeadMs;
    }

    public void setFlashLeadMs(int flashLeadMs) {
        this.flashLeadMs = flashLeadMs;
    }
}

package com.example.iml.orchestrator.integration.bootstrap.context;

import com.example.iml.orchestrator.integration.bootstrap.config.IntegrationBootConfig;

import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * Результат preflight: камеры, worker paths, boot config.
 */
public final class PreflightContext {

    private final BootstrapEnvironment env;
    private Map<String, Object> integration;
    private List<Map<String, Object>> cameras = List.of();
    private IntegrationBootConfig bootConfig;
    private Path workerBin;
    private Path workerConfigPath;

    public PreflightContext(BootstrapEnvironment env) {
        this.env = Objects.requireNonNull(env, "env");
    }

    public BootstrapEnvironment env() {
        return env;
    }

    public Map<String, Object> integration() {
        return integration;
    }

    public void setIntegration(Map<String, Object> integration) {
        this.integration = integration;
    }

    public List<Map<String, Object>> cameras() {
        return cameras;
    }

    public void setCameras(List<Map<String, Object>> cameras) {
        this.cameras = cameras == null ? List.of() : cameras;
    }

    public IntegrationBootConfig bootConfig() {
        return bootConfig;
    }

    public void setBootConfig(IntegrationBootConfig bootConfig) {
        this.bootConfig = bootConfig;
    }

    public Path workerBin() {
        return workerBin;
    }

    public void setWorkerBin(Path workerBin) {
        this.workerBin = workerBin;
    }

    public Path workerConfigPath() {
        return workerConfigPath;
    }

    public void setWorkerConfigPath(Path workerConfigPath) {
        this.workerConfigPath = workerConfigPath;
    }
}

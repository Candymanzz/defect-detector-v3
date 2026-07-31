package com.example.iml.orchestrator.integration.bootstrap.context;

import java.util.Map;

/** YAML config slices attached during child-process bootstrap. */
public final class IntegrationConfigSlices {

    private Map<String, Object> pythonCfg;
    private Map<String, Object> geometryCfg;
    private Map<String, Object> positioningCfg;
    private Map<String, Object> uiCfg;

    public Map<String, Object> pythonCfg() {
        return pythonCfg;
    }

    public void setPythonCfg(Map<String, Object> pythonCfg) {
        this.pythonCfg = pythonCfg;
    }

    public Map<String, Object> geometryCfg() {
        return geometryCfg;
    }

    public void setGeometryCfg(Map<String, Object> geometryCfg) {
        this.geometryCfg = geometryCfg;
    }

    public Map<String, Object> positioningCfg() {
        return positioningCfg;
    }

    public void setPositioningCfg(Map<String, Object> positioningCfg) {
        this.positioningCfg = positioningCfg;
    }

    public Map<String, Object> uiCfg() {
        return uiCfg;
    }

    public void setUiCfg(Map<String, Object> uiCfg) {
        this.uiCfg = uiCfg;
    }
}

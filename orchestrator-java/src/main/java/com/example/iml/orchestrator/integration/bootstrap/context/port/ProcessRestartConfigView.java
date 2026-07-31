package com.example.iml.orchestrator.integration.bootstrap.context.port;

import com.example.iml.orchestrator.integration.bootstrap.config.IntegrationBootConfig;

import java.nio.file.Path;
import java.util.Map;

/** Config slices needed to restart critical external processes. */
public interface ProcessRestartConfigView {

    Map<String, Object> integration();

    Path projectRoot();

    boolean windows();

    IntegrationBootConfig bootConfig();

    Map<String, Object> pythonCfg();
}

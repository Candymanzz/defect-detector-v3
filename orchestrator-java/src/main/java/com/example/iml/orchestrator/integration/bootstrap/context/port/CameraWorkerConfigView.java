package com.example.iml.orchestrator.integration.bootstrap.context.port;

import com.example.iml.orchestrator.integration.bootstrap.config.IntegrationBootConfig;

import java.nio.file.Path;
import java.util.List;
import java.util.Map;

/** Config slices needed to start camera workers / client stream. */
public interface CameraWorkerConfigView {

    Map<String, Object> root();

    Path projectRoot();

    Map<String, Object> integration();

    IntegrationBootConfig bootConfig();

    List<Map<String, Object>> cameras();

    Path workerBin();

    Path workerConfigPath();

    Map<String, Object> uiCfg();
}

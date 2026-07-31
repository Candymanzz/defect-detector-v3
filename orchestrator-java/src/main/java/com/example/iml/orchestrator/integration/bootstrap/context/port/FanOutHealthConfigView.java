package com.example.iml.orchestrator.integration.bootstrap.context.port;

import com.example.iml.orchestrator.integration.bootstrap.config.IntegrationBootConfig;

import java.nio.file.Path;
import java.util.Map;

/** Config slices needed to wire fan-out / health / stop-signal. */
public interface FanOutHealthConfigView {

    Map<String, Object> root();

    Path projectRoot();

    IntegrationBootConfig bootConfig();
}

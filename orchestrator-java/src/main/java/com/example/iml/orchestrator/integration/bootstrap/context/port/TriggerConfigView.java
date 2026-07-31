package com.example.iml.orchestrator.integration.bootstrap.context.port;

import com.example.iml.orchestrator.integration.bootstrap.config.IntegrationBootConfig;

import java.util.Map;

/** Config slices needed to wire trigger runtime. */
public interface TriggerConfigView {

    Map<String, Object> root();

    Map<String, Object> integration();

    Map<String, Object> geometryCfg();

    IntegrationBootConfig bootConfig();
}

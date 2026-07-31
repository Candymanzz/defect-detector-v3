package com.example.iml.orchestrator.integration.bootstrap.context.port;

import com.example.iml.orchestrator.integration.bootstrap.config.IntegrationBootConfig;

import java.util.Map;

/** Config slices needed to start live preview. */
public interface LivePreviewConfigView {

    Map<String, Object> root();

    Map<String, Object> integration();

    int flashLeadMs();

    Map<String, Object> uiCfg();

    IntegrationBootConfig bootConfig();
}

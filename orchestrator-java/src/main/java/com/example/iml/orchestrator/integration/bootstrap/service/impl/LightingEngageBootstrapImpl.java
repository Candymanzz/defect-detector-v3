package com.example.iml.orchestrator.integration.bootstrap.service.impl;

import com.example.iml.orchestrator.integration.bootstrap.service.api.LightingEngageBootstrap;

import com.example.iml.orchestrator.integration.bootstrap.service.api.AbstractBootstrapService;

import com.example.iml.orchestrator.integration.bootstrap.context.PipelineAssemblyContext;
import com.example.iml.orchestrator.integration.lighting.LightBrightnessStore;
import com.example.iml.orchestrator.integration.lighting.LightBrightnessUpdate;
import com.example.iml.orchestrator.integration.lighting.LightServersConfig;
import com.example.iml.orchestrator.integration.lighting.LightTriggerClient;
import com.example.iml.orchestrator.integration.lighting.LightsShutdown;
import org.apache.logging.log4j.Logger;

import java.net.URI;

/**
 * Только освещение: store яркости, LightTriggerClient, engage LightServer.
 */
public final class LightingEngageBootstrapImpl extends AbstractBootstrapService implements LightingEngageBootstrap {

    public LightingEngageBootstrapImpl(Logger log) {
        super(log);
    }

    @Override
    public void engage(PipelineAssemblyContext assembly) {
        var processes = assembly.processes();
        var env = assembly.env();

        int flashLeadMs = LightServersConfig.flashLeadMsFromRoot(env.root());
        assembly.setFlashLeadMs(flashLeadMs);
        if (flashLeadMs > 0) {
            log.info("light_servers flash_lead_ms={} (пауза после старта POST вспышки, перед capture)", flashLeadMs);
        }

        LightBrightnessStore lightBrightnessStore = openOptionalStoreUnderProject(
                env.projectRoot(),
                "config/data/light_brightness_settings.json",
                "light brightness store",
                LightBrightnessStore::open
        );
        assembly.setLightBrightnessStore(lightBrightnessStore);
        LightTriggerClient lightClient = LightTriggerClient.fromRootYaml(env.root());
        assembly.setLightClient(lightClient);
        applyPersistedLightBrightness(lightClient, lightBrightnessStore);

        LightServersConfig lightServersCfg = LightServersConfig.fromRootYaml(env.root());
        LightsShutdown.bind(log, lightClient, processes.lightServerProcess(), lightHttpPort(lightServersCfg));
        if (lightClient.isEnabled()) {
            log.info("waiting for LightServer COM bank (GET /api/com/light)...");
            lightClient.awaitEndpointsReady();
            if (lightBrightnessStore != null && lightBrightnessStore.constantFlashMode()) {
                lightClient.setConstantFlashMode(true);
            }
            lightClient.startupEngage();
            if (lightClient.isHoldMode()) {
                log.info("light_servers hold_mode=true — постоянная подсветка, без On/Off на каждый кадр");
            }
        }
    }

    private void applyPersistedLightBrightness(
            LightTriggerClient lightClient,
            LightBrightnessStore lightBrightnessStore
    ) {
        if (lightClient == null || lightBrightnessStore == null) {
            return;
        }
        LightBrightnessUpdate update = lightBrightnessStore.toUpdate();
        if (update.isEmpty()) {
            return;
        }
        try {
            lightClient.applyBrightnessUpdate(update);
            log.info(
                    "light persisted brightness applied default={} endpoints={}",
                    update.globalPercent(),
                    update.perEndpoint().size()
            );
        } catch (Exception e) {
            log.warn("light persisted brightness apply failed: {}", e.getMessage());
        }
    }

    private static int lightHttpPort(LightServersConfig cfg) {
        if (cfg == null) {
            return 5080;
        }
        try {
            String base = cfg.upstreamBaseUrl();
            URI uri = URI.create(base);
            int port = uri.getPort();
            return port > 0 ? port : 5080;
        } catch (Exception e) {
            return 5080;
        }
    }
}

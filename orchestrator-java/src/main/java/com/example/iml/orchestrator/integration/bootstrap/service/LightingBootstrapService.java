package com.example.iml.orchestrator.integration.bootstrap.service;

import com.example.iml.orchestrator.integration.bootstrap.context.IntegrationRuntimeContext;
import com.example.iml.orchestrator.integration.config.ConfiguredCameras;
import com.example.iml.orchestrator.integration.config.ReferenceSource;
import com.example.iml.orchestrator.integration.lighting.LightBrightnessStore;
import com.example.iml.orchestrator.integration.lighting.LightBrightnessUpdate;
import com.example.iml.orchestrator.integration.lighting.LightServersConfig;
import com.example.iml.orchestrator.integration.lighting.LightTriggerClient;
import com.example.iml.orchestrator.integration.lighting.LightsShutdown;
import com.example.iml.orchestrator.integration.pipeline.InspectionPipeline;
import com.example.iml.orchestrator.integration.pipeline.InspectionPipelineServices;
import com.example.iml.orchestrator.integration.pipeline.decision.DefaultInspectionDecisionAggregator;
import com.example.iml.orchestrator.integration.pipeline.reference.PipelineReferenceRegistry;
import com.example.iml.orchestrator.integration.pipeline.reference.ReferenceSnapshotBootstrap;
import com.example.iml.orchestrator.integration.pipeline.stages.InspectGeometryExecutor;
import com.example.iml.orchestrator.integration.pipeline.stages.InspectPositioningExecutor;
import com.example.iml.orchestrator.integration.pipeline.stages.InspectPythonExecutor;
import com.example.iml.orchestrator.integration.pipeline.telemetry.PipelineInspectionTelemetry;
import org.apache.logging.log4j.Logger;

import java.io.IOException;
import java.net.URI;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.Semaphore;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Сборка графа {@link InspectionPipeline} и engage освещения.
 */
public final class LightingBootstrapService {

    private final Logger log;

    public LightingBootstrapService(Logger log) {
        this.log = log;
    }

    public void assemblePipelineAndEngageLights(IntegrationRuntimeContext ctx) {
        Semaphore positioningSlots = new Semaphore(Math.max(1, ctx.positioningPool().size()));
        AtomicInteger positioningRoundRobin = new AtomicInteger();
        InspectPositioningExecutor positioningExecutor = new InspectPositioningExecutor(
                log,
                ctx.positioningPool(),
                positioningSlots,
                positioningRoundRobin,
                ctx.positioningCfg()
        );
        PipelineInspectionTelemetry pipelineTelemetry = new PipelineInspectionTelemetry();
        ReferenceSnapshotBootstrap referenceBootstrap =
                new ReferenceSnapshotBootstrap(log, ctx.captureCoordinator(), pipelineTelemetry);
        Set<Integer> geometryDisabled = ConfiguredCameras.geometryDisabledCameraIds(ctx.root());
        if (!geometryDisabled.isEmpty()) {
            log.info("geometry disabled for cameras={}", geometryDisabled);
        }
        InspectionPipelineServices pipelineServices = new InspectionPipelineServices(
                log,
                new DefaultInspectionDecisionAggregator(log),
                pipelineTelemetry,
                new InspectGeometryExecutor(
                        log,
                        ctx.geometrySnapshotCache(),
                        ctx.geometryRuntimeConfig(),
                        positioningExecutor,
                        geometryDisabled),
                new InspectPythonExecutor(log, ctx.geometryRuntimeConfig()),
                ctx.captureCoordinator(),
                referenceBootstrap,
                ctx.uiSidecar()
        );
        ctx.setInspectionPipeline(new InspectionPipeline(pipelineServices));

        int flashLeadMs = LightServersConfig.flashLeadMsFromRoot(ctx.root());
        ctx.setFlashLeadMs(flashLeadMs);
        if (flashLeadMs > 0) {
            log.info("light_servers flash_lead_ms={} (пауза после старта POST вспышки, перед capture)", flashLeadMs);
        }

        LightBrightnessStore lightBrightnessStore = openLightBrightnessStore(ctx.projectRoot());
        ctx.setLightBrightnessStore(lightBrightnessStore);
        LightTriggerClient lightClient = LightTriggerClient.fromRootYaml(ctx.root());
        ctx.setLightClient(lightClient);
        applyPersistedLightBrightness(lightClient, lightBrightnessStore);

        LightServersConfig lightServersCfg = LightServersConfig.fromRootYaml(ctx.root());
        LightsShutdown.bind(log, lightClient, ctx.lightServerProcess(), lightHttpPort(lightServersCfg));
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

        PipelineReferenceRegistry pipelineReferenceRegistry = new PipelineReferenceRegistry();
        ctx.setPipelineReferenceRegistry(pipelineReferenceRegistry);
        Map<Integer, String> detectorByCamera = new LinkedHashMap<>();
        Map<Integer, String> analysisProfileByCamera = new LinkedHashMap<>();
        for (Map<String, Object> camera : ctx.cameras()) {
            int cameraId = ((Number) camera.get("id")).intValue();
            detectorByCamera.put(cameraId, String.valueOf(camera.getOrDefault("detector", "v1")));
            analysisProfileByCamera.put(cameraId, ConfiguredCameras.analysisProfileForCamera(camera, cameraId));
        }
        ctx.setDetectorByCamera(detectorByCamera);
        com.example.iml.orchestrator.integration.clientapi.AnalisSurfaceHttpBinaryRpcSupervisor.setAnalysisProfilesByCamera(
                analysisProfileByCamera
        );
        if (ctx.bootConfig().referenceSource() == ReferenceSource.CLIENT) {
            log.info("integration.reference_source=client — эталон только через client.reference_bundle (WebSocket)");
        }
    }

    private LightBrightnessStore openLightBrightnessStore(Path projectRoot) {
        Path storagePath = projectRoot.resolve("config/data/light_brightness_settings.json");
        try {
            return LightBrightnessStore.open(storagePath);
        } catch (IOException e) {
            log.warn("light brightness store unavailable path={}: {}", storagePath.toAbsolutePath(), e.getMessage());
            return null;
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

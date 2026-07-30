package com.example.iml.orchestrator.integration.bootstrap.service.impl;

import com.example.iml.orchestrator.integration.bootstrap.service.api.PreflightBootstrap;

import com.example.iml.orchestrator.integration.bootstrap.service.api.AbstractBootstrapService;

import com.example.iml.orchestrator.integration.bootstrap.config.IntegrationBootConfig;
import com.example.iml.orchestrator.integration.bootstrap.config.IntegrationBootConfigMapper;
import com.example.iml.orchestrator.integration.bootstrap.context.PreflightContext;
import com.example.iml.orchestrator.integration.capture.ImlShmJanitor;
import com.example.iml.orchestrator.integration.config.CameraWorkerPaths;
import com.example.iml.orchestrator.integration.config.ConfiguredCameras;
import com.example.iml.orchestrator.integration.config.YamlMaps;
import com.example.iml.orchestrator.integration.config.YamlScalars;
import org.apache.logging.log4j.Logger;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Pre-flight: камеры, SHM purge, бинарник worker, загрузка {@link IntegrationBootConfig}.
 */
public final class PreflightBootstrapImpl extends AbstractBootstrapService implements PreflightBootstrap {

    public PreflightBootstrapImpl(Logger log) {
        super(log);
    }

    @Override
    public boolean run(PreflightContext preflight) {
        var env = preflight.env();
        List<Map<String, Object>> cameras = enabledCameras(YamlMaps.listOfStringObjectMaps(env.root().get("cameras")));
        if (cameras.isEmpty()) {
            log.warn("No enabled cameras in config; integration pipeline skipped");
            return false;
        }
        preflight.setCameras(cameras);
        log.info("configured cameras: {}", ConfiguredCameras.enabledIds(env.root()));
        ImlShmJanitor.purgeStaleFiles(log);

        Path workerBin = CameraWorkerPaths.resolveCameraWorkerExecutable(env.projectRoot());
        preflight.setWorkerBin(workerBin);
        Map<String, Object> integration = YamlMaps.stringObjectMapOrNull(env.root().get("integration"));
        preflight.setIntegration(integration);

        if (!Files.isRegularFile(workerBin)) {
            log.error(
                    "camera-worker binary not found at {}. Build the worker or place camera_worker (exe) under camera-worker/build/; integration pipeline not started.",
                    workerBin.toAbsolutePath()
            );
            return false;
        }

        List<String> geometryCommand = CameraWorkerPaths.pickIntegrationCommandList(
                integration, env.windows(), "geometry_command_windows", "geometry_command_linux");
        IntegrationBootConfig cfg = IntegrationBootConfigMapper.withPoolCommands(
                IntegrationBootConfigMapper.fromYaml(integration, cameras.size(), env.windows()),
                List.of(),
                geometryCommand
        );
        preflight.setBootConfig(cfg);

        Path workerConfigPath = CameraWorkerPaths.resolveWorkerConfigPath(env.projectRoot(), integration);
        preflight.setWorkerConfigPath(workerConfigPath);
        if (!Files.isRegularFile(workerConfigPath)) {
            log.error("Файл конфигурации camera-worker не найден: {} (integration.worker_config_json)", workerConfigPath.toAbsolutePath());
        } else {
            log.info("camera_worker config={}", workerConfigPath.toAbsolutePath());
        }
        return true;
    }

    private static List<Map<String, Object>> enabledCameras(List<Map<String, Object>> cameras) {
        if (cameras == null || cameras.isEmpty()) {
            return List.of();
        }
        List<Map<String, Object>> out = new ArrayList<>();
        for (Map<String, Object> camera : cameras) {
            if (YamlScalars.toBool(camera.get("enabled"), true)) {
                out.add(camera);
            }
        }
        return out;
    }
}

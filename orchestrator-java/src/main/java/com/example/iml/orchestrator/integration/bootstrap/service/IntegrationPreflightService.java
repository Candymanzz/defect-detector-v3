package com.example.iml.orchestrator.integration.bootstrap.service;

import com.example.iml.orchestrator.integration.bootstrap.config.IntegrationBootConfig;
import com.example.iml.orchestrator.integration.bootstrap.context.IntegrationRuntimeContext;
import com.example.iml.orchestrator.integration.capture.ImlShmJanitor;
import com.example.iml.orchestrator.integration.config.CameraWorkerPaths;
import com.example.iml.orchestrator.integration.config.ConfiguredCameras;
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
public final class IntegrationPreflightService {

    private final Logger log;

    public IntegrationPreflightService(Logger log) {
        this.log = log;
    }

    /**
     * @return {@code false} при early-exit (нет камер / нет worker binary)
     */
    @SuppressWarnings("unchecked")
    public boolean run(IntegrationRuntimeContext ctx) {
        List<Map<String, Object>> cameras = enabledCameras((List<Map<String, Object>>) ctx.root().get("cameras"));
        if (cameras.isEmpty()) {
            log.warn("No enabled cameras in config; integration pipeline skipped");
            return false;
        }
        ctx.setCameras(cameras);
        log.info("configured cameras: {}", ConfiguredCameras.enabledIds(ctx.root()));
        ImlShmJanitor.purgeStaleFiles(log);

        Path workerBin = CameraWorkerPaths.resolveCameraWorkerExecutable(ctx.projectRoot());
        ctx.setWorkerBin(workerBin);
        Map<String, Object> integration = (Map<String, Object>) ctx.root().get("integration");
        ctx.setIntegration(integration);

        if (!Files.isRegularFile(workerBin)) {
            log.error(
                    "camera-worker binary not found at {}. Build the worker or place camera_worker (exe) under camera-worker/build/; integration pipeline not started.",
                    workerBin.toAbsolutePath()
            );
            return false;
        }

        List<String> geometryCommand = CameraWorkerPaths.pickIntegrationCommandList(
                integration, ctx.windows(), "geometry_command_windows", "geometry_command_linux");
        IntegrationBootConfig cfg = IntegrationBootConfig.load(integration, cameras.size(), ctx.windows())
                .withPoolCommands(List.of(), geometryCommand);
        ctx.setBootConfig(cfg);

        Path workerConfigPath = CameraWorkerPaths.resolveWorkerConfigPath(ctx.projectRoot(), integration);
        ctx.setWorkerConfigPath(workerConfigPath);
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
        return List.copyOf(out);
    }
}

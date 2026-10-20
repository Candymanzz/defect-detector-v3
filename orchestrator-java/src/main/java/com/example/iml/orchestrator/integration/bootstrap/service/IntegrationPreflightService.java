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
 * Pre-flight: камеры, SHM purge, JNI library, загрузка {@link IntegrationBootConfig}.
 */
public final class IntegrationPreflightService {

    private final Logger log;

    public IntegrationPreflightService(Logger log) {
        this.log = log;
    }

    /**
     * @return {@code false} при early-exit (нет камер / нет JNI library)
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

        Map<String, Object> integration = (Map<String, Object>) ctx.root().get("integration");
        ctx.setIntegration(integration);
        Path cameraLibrary = CameraWorkerPaths.resolveCameraNativeLibrary(ctx.projectRoot(), integration);
        ctx.setCameraNativeLibrary(cameraLibrary);

        if (!Files.isRegularFile(cameraLibrary)) {
            log.error(
                    "Camera JNI library not found at {}. Build camera-worker with CAMERA_BUILD_JNI=ON (target camera_jni); integration pipeline not started.",
                    cameraLibrary
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
            return false;
        } else {
            log.info("camera backend=jni library={} config={}", cameraLibrary, workerConfigPath.toAbsolutePath());
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

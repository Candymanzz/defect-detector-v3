package com.example.iml.orchestrator.integration.config;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Пути к бинарнику camera-worker и к его JSON-конфигу относительно корня репозитория.
 */
public final class CameraWorkerPaths {

    private CameraWorkerPaths() {
    }

    public static Path resolveCameraWorkerExecutable(Path projectRoot) {
        Path base = projectRoot.resolve("camera-worker").resolve("build");
        String[] candidates = {
                "camera_worker",
                "camera_worker.exe",
                "Release/camera_worker.exe",
                "Debug/camera_worker.exe",
                "RelWithDebInfo/camera_worker.exe",
        };
        for (String c : candidates) {
            Path p = base.resolve(c);
            if (Files.isRegularFile(p)) {
                return p;
            }
        }
        return base.resolve("camera_worker");
    }

    /** Второй аргумент командной строки camera-worker: JSON с настройками захвата. */
    public static Path resolveWorkerConfigPath(Path projectRoot, Map<String, Object> integration) {
        String rel = "config/config.json";
        if (integration != null && integration.get("worker_config_json") != null) {
            String s = String.valueOf(integration.get("worker_config_json")).trim();
            if (!s.isEmpty()) {
                rel = s;
            }
        }
        return projectRoot.resolve(rel).normalize();
    }

    public static List<String> pickIntegrationCommandList(
            Map<String, Object> integration,
            boolean isWindows,
            String windowsKey,
            String linuxKey
    ) {
        if (integration == null) {
            return List.of();
        }
        Object raw = null;
        if (isWindows) {
            raw = integration.get(windowsKey);
        }
        if (!(raw instanceof List<?> list) || list.isEmpty()) {
            raw = integration.get(linuxKey);
        }
        if (!(raw instanceof List<?> list2) || list2.isEmpty()) {
            return List.of();
        }
        List<String> command = new ArrayList<>();
        for (Object e : list2) {
            command.add(String.valueOf(e));
        }
        return command;
    }
}

package com.example.iml.orchestrator.integration.config;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.LinkedHashMap;

/**
 * Пути к бинарнику camera-worker и к его JSON-конфигу относительно корня репозитория.
 */
public final class CameraWorkerPaths {

    private CameraWorkerPaths() {
    }

    /** Optional explicit path, otherwise resolve the CMake JNI artifact. */
    public static Path resolveCameraNativeLibrary(Path projectRoot, Map<String, Object> integration) {
        String configured = System.getProperty("iml.camera.native.library", "").trim();
        if (configured.isEmpty() && integration != null && integration.get("camera_native_library") != null)
            configured = String.valueOf(integration.get("camera_native_library")).trim();
        if (!configured.isEmpty()) return projectRoot.resolve(configured).toAbsolutePath().normalize();
        String filename = System.mapLibraryName("camera_jni");
        for (String directory : List.of("camera-worker/build", "camera-worker/build/Release",
                "camera-worker/build/RelWithDebInfo", "camera-worker/build/Debug", "camera-worker/build-linux")) {
            Path candidate = projectRoot.resolve(directory).resolve(filename);
            if (Files.isRegularFile(candidate)) return candidate.toAbsolutePath().normalize();
        }
        return projectRoot.resolve("camera-worker/build").resolve(filename).toAbsolutePath().normalize();
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

    /** Internal native input; the editable configuration is YAML. */
    public static Path resolveWorkerConfigPath(Path projectRoot, Map<String, Object> integration) {
        String rel = "config/.camera-worker.generated.json";
        if (integration != null && integration.get("worker_config_json") != null) {
            String s = String.valueOf(integration.get("worker_config_json")).trim();
            if (!s.isEmpty()) {
                rel = s;
            }
        }
        return projectRoot.resolve(rel).normalize();
    }

    /** Export the already merged and resolved YAML, without another set of camera defaults. */
    @SuppressWarnings("unchecked")
    public static Path prepareWorkerConfig(Path projectRoot, Map<String, Object> root) throws IOException {
        Map<String, Object> integration = (Map<String, Object>) root.get("integration");
        Path destination = resolveWorkerConfigPath(projectRoot, integration);
        // Explicit legacy JSON inputs remain supported for older installations and native tests.
        if (integration != null && integration.get("worker_config_json") instanceof String legacy
                && !legacy.isBlank()) return destination;
        Map<String, Object> capture = new LinkedHashMap<>();
        for (String key : List.of("version", "frame", "cameras", "capture_source", "capture_trigger_mode",
                "line0_trigger_activation", "line0_line_inverter", "frame_timeout_ms", "detector",
                "gige_switch_count", "gige_cameras_per_switch", "gige_switch_buffer_kb",
                "gige_inter_packet_delay", "gige_frame_transfer_delay_step", "gige_ftd_cameras_per_link")) {
            if (root.containsKey(key)) capture.put(key, root.get(key));
        }
        Files.createDirectories(destination.toAbsolutePath().getParent());
        Path temporary = Files.createTempFile(destination.toAbsolutePath().getParent(), ".camera-worker-", ".tmp");
        try {
            new ObjectMapper().writerWithDefaultPrettyPrinter().writeValue(temporary.toFile(), capture);
            try {
                Files.move(temporary, destination, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
            } catch (AtomicMoveNotSupportedException ex) {
                Files.move(temporary, destination, StandardCopyOption.REPLACE_EXISTING);
            }
        } finally {
            Files.deleteIfExists(temporary);
        }
        return destination;
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

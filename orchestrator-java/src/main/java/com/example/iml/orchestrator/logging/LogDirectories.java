package com.example.iml.orchestrator.logging;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Locale;

/**
 * Единый корень логов репозитория: {@code <projectRoot>/logs/<service>/}.
 * Убирает размазанные {@code orchestrator-java/logs}, {@code user.dir/logs} и т.п.
 */
public final class LogDirectories {

    public static final String PROP_PROJECT_ROOT = "iml.project.root";
    public static final String PROP_LOG_DIR = "iml.log.dir";
    public static final String ENV_PROJECT_ROOT = "IML_PROJECT_ROOT";

    public static final String ORCHESTRATOR = "orchestrator";
    public static final String GEOMETRY = "geometry";
    public static final String POSITIONING = "positioning";
    public static final String SERVICES = "services";
    public static final String ANALIS_SURFACE = "analisSurface";
    public static final String LIGHTSERVER = "lightserver";
    public static final String CAMERA_WORKER = "camera-worker";
    public static final String FRONTEND = "frontend";
    public static final String IO_INPUT_MONITOR = "io-input-monitor";

    private LogDirectories() {
    }

    /** Настроить {@link #PROP_LOG_DIR} до инициализации Log4j2 в оркестраторе. */
    public static void configureOrchestratorLogging() {
        Path dir = ensureServiceDir(ORCHESTRATOR);
        System.setProperty(PROP_LOG_DIR, dir.toAbsolutePath().normalize().toString());
        System.setProperty(PROP_PROJECT_ROOT, projectRoot().toAbsolutePath().normalize().toString());
    }

    public static Path projectRoot() {
        String fromProp = System.getProperty(PROP_PROJECT_ROOT, "").trim();
        if (!fromProp.isEmpty()) {
            return Path.of(fromProp).toAbsolutePath().normalize();
        }
        String fromEnv = System.getenv(ENV_PROJECT_ROOT);
        if (fromEnv != null && !fromEnv.isBlank()) {
            return Path.of(fromEnv.trim()).toAbsolutePath().normalize();
        }
        Path found = findProjectRoot(Path.of(System.getProperty("user.dir", ".")).toAbsolutePath().normalize());
        if (found != null) {
            return found;
        }
        return Path.of(System.getProperty("user.dir", ".")).toAbsolutePath().normalize();
    }

    public static Path logsRoot() {
        return projectRoot().resolve("logs");
    }

    public static Path serviceDir(String service) {
        String safe = sanitize(service);
        return logsRoot().resolve(safe);
    }

    public static Path ensureServiceDir(String service) {
        Path dir = serviceDir(service);
        try {
            Files.createDirectories(dir);
        } catch (Exception ignored) {
            // Log4j / child process still get the path; mkdir may fail on read-only FS.
        }
        return dir;
    }

    /**
     * JVM-аргумент {@code -Diml.log.dir=...} для geometry/positioning и др. Java-детей.
     */
    public static String jvmLogDirArg(String service) {
        Path dir = ensureServiceDir(service);
        return "-D" + PROP_LOG_DIR + "=" + dir.toAbsolutePath().normalize();
    }

    public static String serviceBucketForName(String serviceName) {
        if (serviceName == null || serviceName.isBlank()) {
            return SERVICES;
        }
        String lower = serviceName.toLowerCase(Locale.ROOT);
        if (lower.contains("geometry")) {
            return GEOMETRY;
        }
        if (lower.contains("positioning") || lower.contains("position")) {
            return POSITIONING;
        }
        if (lower.contains("analis") || lower.contains("python")) {
            return ANALIS_SURFACE;
        }
        if (lower.contains("light")) {
            return LIGHTSERVER;
        }
        if (lower.contains("camera") || lower.contains("worker")) {
            return CAMERA_WORKER;
        }
        if (lower.contains("frontend") || lower.contains("electron") || lower.contains("ui")) {
            return FRONTEND;
        }
        if (lower.contains("io-input") || lower.contains("io_input") || lower.contains("plc")) {
            return IO_INPUT_MONITOR;
        }
        return SERVICES;
    }

    static Path findProjectRoot(Path start) {
        Path dir = start;
        for (int i = 0; i < 12 && dir != null; i++) {
            if (looksLikeProjectRoot(dir)) {
                return dir;
            }
            dir = dir.getParent();
        }
        return null;
    }

    private static boolean looksLikeProjectRoot(Path dir) {
        return Files.isDirectory(dir.resolve("orchestrator-java"))
                && Files.isDirectory(dir.resolve("analisSurface"));
    }

    private static String sanitize(String service) {
        String raw = service == null ? "misc" : service.trim();
        if (raw.isEmpty()) {
            return "misc";
        }
        return raw.replaceAll("[^a-zA-Z0-9._-]", "_");
    }
}

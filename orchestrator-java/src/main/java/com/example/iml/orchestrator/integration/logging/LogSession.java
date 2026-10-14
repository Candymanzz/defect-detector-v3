package com.example.iml.orchestrator.integration.logging;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.Map;

/**
 * Единая папка сессии логов: {@code <корень>/logs/yyyy-MM-dd_HH-mm-ss/}.
 * <p>
 * Папку создаёт тот, кто стартует первым (supervisor или оркестратор); остальные сервисы
 * (python, geometry, positioning, frontend через оркестратор) получают путь через
 * переменную окружения {@value #ENV_SESSION_DIR} и пишут в свои подпапки.
 * <p>
 * Класс не использует log4j: его нужно вызывать до первого {@code LogManager.getLogger}.
 */
public final class LogSession {

    public static final String ENV_SESSION_DIR = "IML_LOG_SESSION_DIR";
    public static final String SYS_LOG_DIR = "iml.log.dir";

    private static final DateTimeFormatter STAMP = DateTimeFormatter.ofPattern("yyyy-MM-dd_HH-mm-ss");
    private static volatile Path sessionDir;

    private LogSession() {
    }

    /**
     * Определяет папку сессии (из окружения или создаёт новую в {@code logsRoot}) и выставляет
     * {@code -Diml.log.dir} для log4j2. Идемпотентно.
     */
    public static synchronized Path init(Path logsRoot) {
        if (sessionDir != null) {
            return sessionDir;
        }
        String fromEnv = System.getenv(ENV_SESSION_DIR);
        Path dir;
        if (fromEnv != null && !fromEnv.isBlank()) {
            dir = Path.of(fromEnv.trim()).toAbsolutePath().normalize();
        } else {
            dir = logsRoot.toAbsolutePath().normalize().resolve(LocalDateTime.now().format(STAMP));
        }
        try {
            Files.createDirectories(dir);
        } catch (IOException e) {
            System.err.println("LogSession: cannot create " + dir + ": " + e.getMessage());
        }
        sessionDir = dir;
        System.setProperty(SYS_LOG_DIR, dir.toString());
        return dir;
    }

    /** Корень логов по пути к config.yaml: {@code <проект>/logs}. */
    public static Path logsRootForConfig(Path configPath) {
        Path abs = configPath.toAbsolutePath();
        Path configDir = abs.getParent();
        Path projectRoot = configDir != null && configDir.getParent() != null ? configDir.getParent() : Path.of(".").toAbsolutePath();
        return projectRoot.resolve("logs");
    }

    /** Папка сессии или {@code null}, если {@link #init} ещё не вызывали. */
    public static Path dirOrNull() {
        return sessionDir;
    }

    /** Подпапка сервиса внутри сессии (создаётся). Если сессии нет — {@code fallback}. */
    public static Path serviceDir(String service, Path fallback) {
        Path base = sessionDir;
        if (base == null) {
            return fallback;
        }
        Path dir = base.resolve(service);
        try {
            Files.createDirectories(dir);
        } catch (IOException ignored) {
            // писатель сам создаст каталог или сообщит об ошибке
        }
        return dir;
    }

    /** Переменные окружения, которые нужно передать дочернему процессу. */
    public static Map<String, String> childEnv() {
        Path dir = sessionDir;
        return dir == null ? Map.of() : Map.of(ENV_SESSION_DIR, dir.toString());
    }
}

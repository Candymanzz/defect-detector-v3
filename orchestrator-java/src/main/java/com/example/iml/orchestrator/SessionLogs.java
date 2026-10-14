package com.example.iml.orchestrator;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.Map;

/** One directory per stack launch, inherited by all managed services and restarts. */
public final class SessionLogs {
    private SessionLogs() {}

    public static synchronized String initialize() {
        String existing = System.getProperty("iml.session.log.dir");
        if (existing != null) return existing;
        String inherited = System.getenv("IML_LOG_SESSION_DIR");
        Path directory = inherited == null || inherited.isBlank()
                ? Path.of("logs", LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyy-MM-dd_HH-mm-ss-SSS"))
                    + "-" + ProcessHandle.current().pid()).toAbsolutePath()
                : Path.of(inherited).toAbsolutePath();
        try {
            Files.createDirectories(directory);
        } catch (java.io.IOException e) {
            throw new IllegalStateException("Cannot create session log directory " + directory, e);
        }
        System.setProperty("iml.session.log.dir", directory.toString());
        System.setProperty("iml.log.dir", directory.toString());
        return directory.toString();
    }

    public static void inherit(Map<String, String> environment) {
        environment.put("IML_LOG_SESSION_DIR", initialize());
    }
}

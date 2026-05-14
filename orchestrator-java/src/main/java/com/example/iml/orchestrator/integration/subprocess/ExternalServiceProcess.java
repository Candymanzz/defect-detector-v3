package com.example.iml.orchestrator.integration.subprocess;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import java.io.IOException;
import java.nio.file.Path;
import java.util.List;

/**
 * Запуск внешнего процесса (отдельный OS-процесс) из командной строки, без управления протоколом IML.
 */
public final class ExternalServiceProcess implements AutoCloseable {
    private static final Logger log = LogManager.getLogger(ExternalServiceProcess.class);

    private final String name;
    private final Process process;

    private ExternalServiceProcess(String name, Process process) {
        this.name = name;
        this.process = process;
    }

    public static ExternalServiceProcess start(String name, List<String> command, Path workingDir) throws IOException {
        ProcessBuilder pb = new ProcessBuilder(command);
        pb.directory(workingDir.toFile());
        pb.redirectError(ProcessBuilder.Redirect.INHERIT);
        pb.redirectOutput(ProcessBuilder.Redirect.INHERIT);
        Process process = pb.start();
        log.info("started external service {} with command={}", name, command);
        return new ExternalServiceProcess(name, process);
    }

    public boolean isAlive() {
        return process.isAlive();
    }

    @Override
    public void close() {
        try {
            if (process.isAlive()) {
                process.destroy();
            }
        } catch (Exception e) {
            log.warn("failed to stop external service {}: {}", name, e.getMessage());
        }
    }
}

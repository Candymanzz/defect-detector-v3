package com.example.iml.orchestrator.integration.subprocess;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import java.io.IOException;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Запуск внешнего процесса (отдельный OS-процесс) из командной строки, без управления протоколом IML.
 */
public final class ExternalServiceProcess implements AutoCloseable {
    private static final Logger log = LogManager.getLogger(ExternalServiceProcess.class);

    private final String name;
    private final Process process;
    private final AtomicBoolean closing = new AtomicBoolean(false);
    private volatile Runnable unexpectedExitListener;

    private ExternalServiceProcess(String name, Process process) {
        this.name = name;
        this.process = process;
        process.onExit().thenRun(this::handleProcessExit);
    }

    public static ExternalServiceProcess start(String name, List<String> command, Path workingDir) throws IOException {
        return start(name, command, workingDir, Map.of());
    }

    public static ExternalServiceProcess start(
            String name,
            List<String> command,
            Path workingDir,
            Map<String, String> extraEnv
    ) throws IOException {
        ProcessBuilder pb = new ProcessBuilder(command);
        pb.directory(workingDir.toFile());
        if (extraEnv != null) {
            for (Map.Entry<String, String> entry : extraEnv.entrySet()) {
                if (entry.getKey() != null && entry.getValue() != null) {
                    pb.environment().put(entry.getKey(), entry.getValue());
                }
            }
        }
        pb.redirectError(ProcessBuilder.Redirect.INHERIT);
        pb.redirectOutput(ProcessBuilder.Redirect.INHERIT);
        Process process = pb.start();
        log.info("started external service {} pid={} command={}", name, process.pid(), command);
        return new ExternalServiceProcess(name, process);
    }

    public boolean isAlive() {
        return process.isAlive();
    }

    public long pid() {
        try {
            return process.pid();
        } catch (Exception e) {
            return -1L;
        }
    }

    public String name() {
        return name;
    }

    /**
     * Колбэк при неожиданном выходе процесса (не после {@link #close()}).
     */
    public void onUnexpectedExit(Runnable listener) {
        this.unexpectedExitListener = listener;
        if (!process.isAlive() && !closing.get()) {
            handleProcessExit();
        }
    }

    public boolean isClosing() {
        return closing.get();
    }

    private void handleProcessExit() {
        if (closing.get()) {
            return;
        }
        Runnable listener = unexpectedExitListener;
        if (listener == null) {
            return;
        }
        try {
            listener.run();
        } catch (Exception e) {
            log.warn("unexpected-exit listener for {} failed: {}", name, e.getMessage());
        }
    }

    @Override
    public void close() {
        ExternalServiceProcessLifecycle.close(name, process, closing, this::pid, log);
    }

    /**
     * Запасной путь: убить всё, что слушает порт (сиротский LightServer после Ctrl+C).
     */
    public static void killListenersOnPort(int port, org.apache.logging.log4j.Logger logger) {
        ExternalServicePortKiller.killListenersOnPort(port, logger);
    }
}

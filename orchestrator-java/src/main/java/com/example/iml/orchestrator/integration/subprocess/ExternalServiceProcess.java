package com.example.iml.orchestrator.integration.subprocess;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import java.io.IOException;
import java.nio.file.Path;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.TimeUnit;
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
        int exitCode = -1;
        try {
            exitCode = process.exitValue();
        } catch (IllegalThreadStateException ignored) {
        }
        log.warn("external service {} pid={} exited unexpectedly code={}", name, pid(), exitCode);
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
        closing.set(true);
        try {
            if (!process.isAlive()) {
                return;
            }
            long pid = pid();
            log.info("stopping external service {} pid={}", name, pid);
            // Сначала мягко по дереву (dotnet часто оставляет дочерние процессы).
            destroyProcessTree(false);
            if (!process.waitFor(5, TimeUnit.SECONDS)) {
                log.warn("external service {} pid={} did not exit in 5s, forcing tree kill", name, pid);
                destroyProcessTree(true);
                if (!process.waitFor(3, TimeUnit.SECONDS) && isWindows() && pid > 0) {
                    taskkillWindows(pid, true);
                    process.waitFor(2, TimeUnit.SECONDS);
                }
            }
            if (process.isAlive()) {
                log.warn("external service {} pid={} still alive after force kill", name, pid);
            } else {
                log.info("external service {} stopped", name);
            }
        } catch (Exception e) {
            log.warn("failed to stop external service {}: {}", name, e.getMessage());
        }
    }

    private void destroyProcessTree(boolean forcibly) {
        try {
            ProcessHandle handle = process.toHandle();
            handle.descendants().forEach(child -> {
                try {
                    if (forcibly) {
                        child.destroyForcibly();
                    } else {
                        child.destroy();
                    }
                } catch (Exception ignored) {
                }
            });
            if (forcibly) {
                handle.destroyForcibly();
            } else {
                handle.destroy();
            }
        } catch (Exception e) {
            if (forcibly) {
                process.destroyForcibly();
            } else {
                process.destroy();
            }
        }
    }

    /**
     * Запасной путь: убить всё, что слушает порт (сиротский LightServer после Ctrl+C).
     */
    public static void killListenersOnPort(int port, org.apache.logging.log4j.Logger logger) {
        if (port <= 0) {
            return;
        }
        try {
            if (isWindows()) {
                killWindowsPortListeners(port, logger);
            } else {
                killUnixPortListeners(port, logger);
            }
        } catch (Exception e) {
            if (logger != null) {
                logger.warn("killListenersOnPort {}: {}", port, e.getMessage());
            }
        }
    }

    private static void killWindowsPortListeners(int port, org.apache.logging.log4j.Logger logger) throws Exception {
        Process netstat = new ProcessBuilder("cmd.exe", "/c", "netstat -ano | findstr :" + port)
                .redirectErrorStream(true)
                .start();
        String out = new String(netstat.getInputStream().readAllBytes());
        netstat.waitFor(3, TimeUnit.SECONDS);
        for (String line : out.split("\\R")) {
            String trimmed = line.trim();
            if (!trimmed.contains("LISTENING")) {
                continue;
            }
            String[] parts = trimmed.split("\\s+");
            if (parts.length < 5) {
                continue;
            }
            String pidStr = parts[parts.length - 1];
            long pid;
            try {
                pid = Long.parseLong(pidStr);
            } catch (NumberFormatException e) {
                continue;
            }
            if (pid <= 0) {
                continue;
            }
            if (logger != null) {
                logger.info("killing orphan listener pid={} on port {}", pid, port);
            }
            taskkillWindows(pid, true);
        }
    }

    private static void killUnixPortListeners(int port, org.apache.logging.log4j.Logger logger) throws Exception {
        Process lsof = new ProcessBuilder("sh", "-c", "lsof -t -iTCP:" + port + " -sTCP:LISTEN || true")
                .redirectErrorStream(true)
                .start();
        String out = new String(lsof.getInputStream().readAllBytes()).trim();
        lsof.waitFor(3, TimeUnit.SECONDS);
        if (out.isEmpty()) {
            return;
        }
        for (String pidStr : out.split("\\s+")) {
            long pid;
            try {
                pid = Long.parseLong(pidStr.trim());
            } catch (NumberFormatException e) {
                continue;
            }
            if (logger != null) {
                logger.info("killing orphan listener pid={} on port {}", pid, port);
            }
            new ProcessBuilder("kill", "-9", String.valueOf(pid)).start().waitFor(2, TimeUnit.SECONDS);
        }
    }

    private static void taskkillWindows(long pid, boolean force) {
        try {
            List<String> cmd = force
                    ? List.of("taskkill", "/PID", String.valueOf(pid), "/T", "/F")
                    : List.of("taskkill", "/PID", String.valueOf(pid), "/T");
            new ProcessBuilder(cmd).redirectErrorStream(true).start().waitFor(5, TimeUnit.SECONDS);
        } catch (Exception ignored) {
        }
    }

    private static boolean isWindows() {
        String os = System.getProperty("os.name", "").toLowerCase(Locale.ROOT);
        return os.contains("win");
    }
}

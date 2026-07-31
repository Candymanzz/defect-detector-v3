package com.example.iml.orchestrator.integration.subprocess;

import java.io.IOException;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.TimeUnit;

/** Kill OS listeners on a TCP port (orphan cleanup after Ctrl+C). */
public final class ExternalServicePortKiller {

    private ExternalServicePortKiller() {
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

    static void killWindowsPortListeners(int port, org.apache.logging.log4j.Logger logger)
            throws IOException, InterruptedException {
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

    static void killUnixPortListeners(int port, org.apache.logging.log4j.Logger logger)
            throws IOException, InterruptedException {
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

    static void taskkillWindows(long pid, boolean force) {
        try {
            List<String> cmd = force
                    ? List.of("taskkill", "/PID", String.valueOf(pid), "/T", "/F")
                    : List.of("taskkill", "/PID", String.valueOf(pid), "/T");
            new ProcessBuilder(cmd).redirectErrorStream(true).start().waitFor(5, TimeUnit.SECONDS);
        } catch (Exception ignored) {
        }
    }

    static boolean isWindows() {
        String os = System.getProperty("os.name", "").toLowerCase(Locale.ROOT);
        return os.contains("win");
    }
}

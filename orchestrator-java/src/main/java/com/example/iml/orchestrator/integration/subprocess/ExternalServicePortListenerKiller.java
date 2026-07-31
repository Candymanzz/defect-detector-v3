package com.example.iml.orchestrator.integration.subprocess;

import org.apache.logging.log4j.Logger;

import java.util.List;
import java.util.Locale;
import java.util.concurrent.TimeUnit;

final class ExternalServicePortListenerKiller {
    private ExternalServicePortListenerKiller() {
    }

    static void killListenersOnPort(int port, Logger logger) {
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

    private static void killWindowsPortListeners(int port, Logger logger) throws Exception {
        Process netstat = new ProcessBuilder("cmd.exe", "/c", "netstat -ano | findstr :" + port)
                .redirectErrorStream(true).start();
        String out = new String(netstat.getInputStream().readAllBytes());
        netstat.waitFor(3, TimeUnit.SECONDS);
        for (String line : out.split("\\R")) {
            String[] parts = line.trim().split("\\s+");
            if (!line.contains("LISTENING") || parts.length < 5) {
                continue;
            }
            try {
                long pid = Long.parseLong(parts[parts.length - 1]);
                if (pid > 0) {
                    logAndKillWindows(pid, port, logger);
                }
            } catch (NumberFormatException ignored) {
            }
        }
    }

    private static void killUnixPortListeners(int port, Logger logger) throws Exception {
        Process lsof = new ProcessBuilder("sh", "-c", "lsof -t -iTCP:" + port + " -sTCP:LISTEN || true")
                .redirectErrorStream(true).start();
        String out = new String(lsof.getInputStream().readAllBytes()).trim();
        lsof.waitFor(3, TimeUnit.SECONDS);
        for (String pidStr : out.split("\\s+")) {
            try {
                long pid = Long.parseLong(pidStr.trim());
                if (logger != null) {
                    logger.info("killing orphan listener pid={} on port {}", pid, port);
                }
                new ProcessBuilder("kill", "-9", String.valueOf(pid)).start().waitFor(2, TimeUnit.SECONDS);
            } catch (NumberFormatException ignored) {
            }
        }
    }

    private static void logAndKillWindows(long pid, int port, Logger logger) {
        if (logger != null) {
            logger.info("killing orphan listener pid={} on port {}", pid, port);
        }
        taskkillWindows(pid, true);
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
        return System.getProperty("os.name", "").toLowerCase(Locale.ROOT).contains("win");
    }
}

package com.example.iml.orchestrator.supervisor;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import java.net.URI;
import java.util.ArrayList;
import java.util.List;

/**
 * Эскалация: перезагрузка Windows после серии неудачных recovery-циклов supervisor.
 */
final class WindowsRebootEscalation {

    private static final Logger log = LogManager.getLogger(WindowsRebootEscalation.class);

    private WindowsRebootEscalation() {
    }

    static boolean enabledByDefault() {
        return isWindows();
    }

    static boolean scheduleReboot(int delaySec, String reason) {
        if (!isWindows()) {
            log.warn("Windows reboot skipped — not running on Windows ({})", reason);
            return false;
        }
        int delay = Math.max(15, delaySec);
        String message = "IML stack supervisor: " + reason;
        log.error("scheduling Windows reboot in {}s — {}", delay, message);
        try {
            ProcessBuilder pb = new ProcessBuilder(
                    "shutdown",
                    "/r",
                    "/t",
                    String.valueOf(delay),
                    "/c",
                    message
            );
            pb.redirectErrorStream(true);
            Process process = pb.start();
            if (!process.waitFor(15, java.util.concurrent.TimeUnit.SECONDS)) {
                log.error("shutdown /r did not finish in 15s");
                return false;
            }
            int code = process.exitValue();
            if (code != 0) {
                log.error("shutdown /r failed exit={} (run supervisor as Administrator?)", code);
                return false;
            }
            log.error("Windows reboot scheduled in {}s", delay);
            return true;
        } catch (Exception e) {
            log.error("Windows reboot failed: {}", e.getMessage());
            return false;
        }
    }

    private static boolean isWindows() {
        return System.getProperty("os.name", "").toLowerCase().contains("win");
    }
}

package com.example.iml.orchestrator.integration.subprocess;

import org.apache.logging.log4j.Logger;

import java.util.Locale;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Останов IoInputMonitor при любом выходе JVM (Ctrl+C, kill, finally).
 * Идемпотентно — hook и {@code finally} могут вызвать дважды.
 * <p>
 * Не полагается на daemon-потоки: при выходе JVM демоны убиваются без cleanup;
 * поэтому закрытие — в shutdown hook / {@link #run(String)}.
 */
public final class IoInputMonitorShutdown {

    private static final AtomicBoolean DONE = new AtomicBoolean(false);
    private static final AtomicReference<Logger> LOG = new AtomicReference<>();
    private static final AtomicReference<ExternalServiceProcess> PROCESS = new AtomicReference<>();
    private static volatile boolean hookRegistered;

    private IoInputMonitorShutdown() {
    }

    public static void bind(Logger log, ExternalServiceProcess process) {
        LOG.set(log);
        PROCESS.set(process);
        ensureHook();
    }

    /** Сменить ref процесса без полного shutdown (рестарт watchdog). */
    public static void replaceProcess(ExternalServiceProcess process) {
        PROCESS.set(process);
    }

    public static void clearProcessRefOnly() {
        PROCESS.set(null);
    }

    private static synchronized void ensureHook() {
        if (hookRegistered) {
            return;
        }
        hookRegistered = true;
        Runtime.getRuntime().addShutdownHook(new Thread(() -> {
            try {
                run("jvm-shutdown-hook");
            } catch (Throwable t) {
                System.err.println("io-input-monitor shutdown hook: " + t.getMessage());
            }
        }, "io-input-monitor-shutdown"));
    }

    /** Вызвать из finally / shutdown coordinator. */
    public static void run(String reason) {
        if (!DONE.compareAndSet(false, true)) {
            return;
        }
        Logger log = LOG.get();
        if (log != null) {
            log.info("io-input-monitor shutdown ({})", reason);
        }
        ExternalServiceProcess process = PROCESS.getAndSet(null);
        if (process != null) {
            try {
                process.close();
            } catch (Exception e) {
                warn(log, "io-input-monitor process close: " + e.getMessage());
            }
        }
        killOrphanIoInputMonitors(log);
    }

    /**
     * Убрать сирот после crash / прошлого запуска (COM3 Access Denied).
     * Безопасно вызывать до старта нового IoInputMonitor.
     */
    public static void killOrphans(Logger log) {
        killOrphanIoInputMonitors(log);
    }

    private static void killOrphanIoInputMonitors(Logger log) {
        try {
            if (isWindows()) {
                killWindowsOrphans(log);
            } else {
                killUnixOrphans(log);
            }
        } catch (Exception e) {
            warn(log, "io-input-monitor orphan kill: " + e.getMessage());
        }
    }

    private static void killWindowsOrphans(Logger log) throws Exception {
        // Без вложенных кавычек — ProcessBuilder часто ломал прежний -like '*IoInputMonitor*'.
        String ps = "Get-CimInstance Win32_Process "
                + "| Where-Object { $_.CommandLine -match 'IoInputMonitor' } "
                + "| ForEach-Object { Stop-Process -Id $_.ProcessId -Force -ErrorAction SilentlyContinue; "
                + "$_.ProcessId }";
        Process proc = new ProcessBuilder("powershell.exe", "-NoProfile", "-NonInteractive", "-Command", ps)
                .redirectErrorStream(true)
                .start();
        String out = new String(proc.getInputStream().readAllBytes()).trim();
        boolean finished = proc.waitFor(8, TimeUnit.SECONDS);
        if (!finished) {
            proc.destroyForcibly();
            warn(log, "io-input-monitor orphan kill timed out");
            return;
        }
        if (!out.isEmpty() && log != null) {
            for (String line : out.split("\\R")) {
                String pid = line.trim();
                if (!pid.isEmpty() && pid.chars().allMatch(Character::isDigit)) {
                    log.info("killed orphan IoInputMonitor pid={}", pid);
                }
            }
        }
    }

    private static void killUnixOrphans(Logger log) throws Exception {
        Process proc = new ProcessBuilder(
                "sh", "-c",
                "pkill -f '[Ii]o[Ii]nput[Mm]onitor' 2>/dev/null || true"
        )
                .redirectErrorStream(true)
                .start();
        proc.waitFor(5, TimeUnit.SECONDS);
        if (log != null) {
            log.info("io-input-monitor orphan pkill attempted");
        }
    }

    private static boolean isWindows() {
        return System.getProperty("os.name", "").toLowerCase(Locale.ROOT).contains("win");
    }

    private static void warn(Logger log, String message) {
        if (log != null) {
            log.warn(message);
        } else {
            System.err.println(message);
        }
    }
}

package com.example.iml.orchestrator.supervisor;

import com.example.iml.orchestrator.integration.subprocess.ExternalServiceProcess;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import java.io.IOException;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Внешний supervisor: мониторит оркестратор и перезапускает стек после краша.
 * После серии неудачных recovery (health не восстанавливается) — перезагрузка Windows.
 */
public final class StackSupervisorMain {

    private static final Logger log = LogManager.getLogger(StackSupervisorMain.class);

    static final int[] DEV_STACK_PORTS = {
            8000, 8001, 8002, 8003, 8004, 8005, 8006, 8007, 8008, 8009,
            8099, 8765, 5173, 5079, 5080, 8088, 9101
    };

    private final Path orchestratorJar;
    private final Path configPath;
    private final URI healthUri;
    private final List<URI> stackHealthUris;
    private final long healthIntervalMs;
    private final long restartDelayMs;
    private final int healthFailThreshold;
    private final int maxRestarts;
    private final long initialAttachPid;
    private final long startupHealthTimeoutMs;
    private final long stableHealthyMs;
    private final boolean rebootEnabled;
    private final int rebootAfterFailures;
    private final int rebootDelaySec;
    private final AtomicBoolean stopRequested = new AtomicBoolean(false);

    StackSupervisorMain(
            Path orchestratorJar,
            Path configPath,
            URI healthUri,
            List<URI> stackHealthUris,
            long healthIntervalMs,
            long restartDelayMs,
            int healthFailThreshold,
            int maxRestarts,
            long initialAttachPid,
            long startupHealthTimeoutMs,
            long stableHealthyMs,
            boolean rebootEnabled,
            int rebootAfterFailures,
            int rebootDelaySec
    ) {
        this.orchestratorJar = orchestratorJar;
        this.configPath = configPath;
        this.healthUri = healthUri;
        this.stackHealthUris = stackHealthUris == null || stackHealthUris.isEmpty()
                ? List.of(healthUri)
                : List.copyOf(stackHealthUris);
        this.healthIntervalMs = healthIntervalMs;
        this.restartDelayMs = restartDelayMs;
        this.healthFailThreshold = Math.max(1, healthFailThreshold);
        this.maxRestarts = maxRestarts;
        this.initialAttachPid = initialAttachPid;
        this.startupHealthTimeoutMs = Math.max(10_000L, startupHealthTimeoutMs);
        this.stableHealthyMs = Math.max(60_000L, stableHealthyMs);
        this.rebootEnabled = rebootEnabled;
        this.rebootAfterFailures = Math.max(1, rebootAfterFailures);
        this.rebootDelaySec = Math.max(15, rebootDelaySec);
    }

    public static void main(String[] args) {
        if (args.length == 0) {
            System.err.println(
                    "Usage: StackSupervisorMain <config.yaml> [--jar orchestrator.jar] [--attach-pid PID]"
            );
            System.exit(1);
        }
        Path configPath = Path.of(args[0]);
        Path jarPath = resolveJarPath(args);
        long attachPid = resolveAttachPid(args);
        if (!Files.isRegularFile(configPath)) {
            System.err.println("Config not found: " + configPath.toAbsolutePath());
            System.exit(1);
        }
        if (!Files.isRegularFile(jarPath)) {
            System.err.println("Orchestrator JAR not found: " + jarPath.toAbsolutePath());
            System.exit(1);
        }

        URI healthUri = URI.create(env("IML_ORCHESTRATOR_HEALTH_URL", "http://127.0.0.1:8099/health"));
        long healthIntervalMs = envLong("IML_SUPERVISOR_HEALTH_INTERVAL_MS", 5000L);
        long restartDelayMs = envLong("IML_SUPERVISOR_RESTART_DELAY_MS", 5000L);
        int healthFailThreshold = envInt("IML_SUPERVISOR_HEALTH_FAIL_THRESHOLD", 3);
        int maxRestarts = envInt("IML_SUPERVISOR_MAX_RESTARTS", 0);
        long startupHealthTimeoutMs = envLong("IML_SUPERVISOR_STARTUP_HEALTH_TIMEOUT_MS", 180_000L);
        long stableHealthyMs = envLong("IML_SUPERVISOR_STABLE_HEALTH_MS", 300_000L);
        boolean rebootEnabled = envBoolean("IML_SUPERVISOR_REBOOT_ENABLED", WindowsRebootEscalation.enabledByDefault());
        int rebootAfterFailures = envInt("IML_SUPERVISOR_REBOOT_AFTER_FAILURES", 3);
        int rebootDelaySec = envInt("IML_SUPERVISOR_REBOOT_DELAY_SEC", 90);

        StackSupervisorMain supervisor = new StackSupervisorMain(
                jarPath,
                configPath,
                healthUri,
                resolveStackHealthUris(healthUri),
                healthIntervalMs,
                restartDelayMs,
                healthFailThreshold,
                maxRestarts,
                attachPid,
                startupHealthTimeoutMs,
                stableHealthyMs,
                rebootEnabled,
                rebootAfterFailures,
                rebootDelaySec
        );
        Runtime.getRuntime().addShutdownHook(new Thread(() -> {
            supervisor.stopRequested.set(true);
            log.info("stack supervisor stop requested (shutdown hook)");
        }, "stack-supervisor-stop"));

        int exitCode = supervisor.runLoop();
        System.exit(exitCode);
    }

    int runLoop() {
        int restartCount = 0;
        int failedRecoveryCount = 0;
        long attachPid = initialAttachPid;
        log.info(
                "stack supervisor started jar={} config={} health_uris={} interval_ms={} restart_delay_ms={} "
                        + "fail_threshold={} max_restarts={} attach_pid={} startup_health_timeout_ms={} "
                        + "stable_health_ms={} reboot_enabled={} reboot_after_failures={} reboot_delay_sec={}",
                orchestratorJar.toAbsolutePath(),
                configPath.toAbsolutePath(),
                stackHealthUris,
                healthIntervalMs,
                restartDelayMs,
                healthFailThreshold,
                maxRestarts == 0 ? "unlimited" : maxRestarts,
                attachPid > 0 ? attachPid : "none",
                startupHealthTimeoutMs,
                stableHealthyMs,
                rebootEnabled,
                rebootAfterFailures,
                rebootDelaySec
        );

        while (!stopRequested.get()) {
            if (maxRestarts > 0 && restartCount >= maxRestarts) {
                log.error("stack supervisor max restarts reached ({})", maxRestarts);
                maybeRebootWindows(failedRecoveryCount, "max restarts reached");
                return 3;
            }
            if (restartCount > 0) {
                log.warn("stack supervisor restart attempt #{}", restartCount);
            }

            Process child = null;
            long externalPid = -1L;
            boolean attachedToLauncher = false;
            boolean recoveryFailed = true;
            try {
                if (attachPid > 0 && isProcessAlive(attachPid) && probeStackHealth(3000)) {
                    externalPid = attachPid;
                    attachedToLauncher = true;
                    log.info(
                            "stack supervisor attach mode — monitoring launcher orchestrator pid={} (no spawn, no port cleanup)",
                            externalPid
                    );
                    attachPid = -1L;
                } else {
                    if (attachPid > 0) {
                        log.warn(
                                "stack supervisor attach pid={} unavailable or unhealthy — taking over spawn",
                                attachPid
                        );
                        attachPid = -1L;
                    }
                    cleanupOrphanPorts("pre-start");
                    child = startOrchestrator();
                    if (!awaitStackHealth(startupHealthTimeoutMs)) {
                        log.error(
                                "stack supervisor startup health timeout {}ms — services not healthy after restart",
                                startupHealthTimeoutMs
                        );
                    } else {
                        MonitorResult result = monitorUntilUnhealthy(child, -1L);
                        if (stopRequested.get()) {
                            log.info("stack supervisor stopping spawned orchestrator (user request)");
                            destroyProcessTree(child, false);
                            return 0;
                        }
                        recoveryFailed = result.stableHealthyMs() < stableHealthyMs;
                        log.warn(
                                "stack supervisor orchestrator unhealthy reason={} pid={} exit={} stable_healthy_ms={}",
                                result.reason(),
                                child.pid(),
                                result.exitCode(),
                                result.stableHealthyMs()
                        );
                    }
                }

                if (attachedToLauncher) {
                    MonitorResult result = monitorUntilUnhealthy(null, externalPid);
                    if (stopRequested.get()) {
                        log.info("stack supervisor stop — leaving launcher orchestrator pid={} running", externalPid);
                        return 0;
                    }
                    recoveryFailed = result.stableHealthyMs() < stableHealthyMs;
                    log.warn(
                            "stack supervisor attach monitor ended reason={} pid={} stable_healthy_ms={}",
                            result.reason(),
                            externalPid,
                            result.stableHealthyMs()
                    );
                }
            } catch (IOException e) {
                log.error("stack supervisor failed to start orchestrator: {}", e.getMessage());
                recoveryFailed = true;
            } finally {
                if (child != null) {
                    destroyProcessTree(child, true);
                } else if (!attachedToLauncher && externalPid > 0) {
                    destroyProcessTreeByPid(externalPid, true);
                }
                cleanupOrphanPorts("post-crash");
            }

            if (recoveryFailed) {
                failedRecoveryCount++;
                log.warn(
                        "stack supervisor failed recovery count={}/{} (reboot after {})",
                        failedRecoveryCount,
                        rebootAfterFailures,
                        rebootEnabled ? rebootAfterFailures : "disabled"
                );
                if (rebootEnabled && failedRecoveryCount >= rebootAfterFailures) {
                    boolean rebootScheduled = WindowsRebootEscalation.scheduleReboot(
                            rebootDelaySec,
                            failedRecoveryCount + " failed recoveries — stack health not restored"
                    );
                    return rebootScheduled ? 4 : 5;
                }
            } else {
                failedRecoveryCount = 0;
            }

            restartCount++;
            if (stopRequested.get()) {
                return 0;
            }
            sleepQuietly(restartDelayMs);
        }
        return 0;
    }

    private void maybeRebootWindows(int failedRecoveryCount, String reason) {
        if (rebootEnabled && failedRecoveryCount >= rebootAfterFailures) {
            WindowsRebootEscalation.scheduleReboot(rebootDelaySec, reason);
        }
    }

    private boolean awaitStackHealth(long timeoutMs) {
        long deadline = System.currentTimeMillis() + timeoutMs;
        while (!stopRequested.get() && System.currentTimeMillis() < deadline) {
            if (probeStackHealth(3000)) {
                log.info("stack supervisor startup health OK");
                return true;
            }
            sleepQuietly(Math.min(healthIntervalMs, 2000L));
        }
        return false;
    }

    boolean probeStackHealth(int timeoutMs) {
        for (URI uri : stackHealthUris) {
            if (!probeHealth(uri, timeoutMs)) {
                return false;
            }
        }
        return true;
    }

    private Process startOrchestrator() throws IOException {
        List<String> command = List.of(
                resolveJavaBinary(),
                "-jar",
                orchestratorJar.toAbsolutePath().toString(),
                configPath.toAbsolutePath().toString()
        );
        ProcessBuilder pb = new ProcessBuilder(command);
        pb.inheritIO();
        pb.directory(configPath.getParent() == null ? Path.of(".").toFile() : configPath.getParent().toFile());
        Process process = pb.start();
        log.info("stack supervisor spawned orchestrator pid={}", process.pid());
        return process;
    }

    private MonitorResult monitorUntilUnhealthy(Process child, long externalPid) {
        int consecutiveHealthFails = 0;
        long nextHealthProbeMs = 0L;
        long healthySinceMs = -1L;
        while (!stopRequested.get()) {
            boolean alive = child != null ? child.isAlive() : isProcessAlive(externalPid);
            if (!alive) {
                int exit = child != null ? exitCodeQuiet(child) : -1;
                return new MonitorResult("process-exited", exit, stableHealthyDurationMs(healthySinceMs));
            }
            long now = System.currentTimeMillis();
            if (now >= nextHealthProbeMs) {
                nextHealthProbeMs = now + healthIntervalMs;
                if (probeStackHealth(3000)) {
                    consecutiveHealthFails = 0;
                    if (healthySinceMs < 0) {
                        healthySinceMs = now;
                    }
                } else {
                    consecutiveHealthFails++;
                    healthySinceMs = -1L;
                    log.warn(
                            "stack supervisor health probe failed ({}/{}) uris={}",
                            consecutiveHealthFails,
                            healthFailThreshold,
                            stackHealthUris
                    );
                    if (consecutiveHealthFails >= healthFailThreshold) {
                        return new MonitorResult("health-timeout", -1, stableHealthyDurationMs(healthySinceMs));
                    }
                }
            }
            sleepQuietly(500L);
        }
        return new MonitorResult("stop-requested", -1, stableHealthyDurationMs(healthySinceMs));
    }

    private long stableHealthyDurationMs(long healthySinceMs) {
        if (healthySinceMs < 0) {
            return 0L;
        }
        return Math.max(0L, System.currentTimeMillis() - healthySinceMs);
    }

    static boolean isProcessAlive(long pid) {
        if (pid <= 0) {
            return false;
        }
        return ProcessHandle.of(pid).map(ProcessHandle::isAlive).orElse(false);
    }

    static boolean probeHealth(URI uri, int timeoutMs) {
        HttpURLConnection conn = null;
        try {
            conn = (HttpURLConnection) uri.toURL().openConnection();
            conn.setRequestMethod("GET");
            conn.setConnectTimeout(timeoutMs);
            conn.setReadTimeout(timeoutMs);
            int code = conn.getResponseCode();
            if (code != 200) {
                return false;
            }
            try (InputStream in = conn.getInputStream()) {
                byte[] body = in.readAllBytes();
                String text = new String(body).trim().toLowerCase();
                return text.isEmpty() || text.contains("ok");
            }
        } catch (Exception e) {
            return false;
        } finally {
            if (conn != null) {
                conn.disconnect();
            }
        }
    }

    void cleanupOrphanPorts(String phase) {
        log.info("stack supervisor port cleanup phase={}", phase);
        for (int port : DEV_STACK_PORTS) {
            ExternalServiceProcess.killListenersOnPort(port, log);
        }
    }

    static void destroyProcessTree(Process process, boolean force) {
        if (process == null) {
            return;
        }
        destroyProcessTreeByPid(process.pid(), force);
    }

    static void destroyProcessTreeByPid(long pid, boolean force) {
        if (pid <= 0) {
            return;
        }
        Optional<ProcessHandle> handleOpt = ProcessHandle.of(pid);
        if (handleOpt.isEmpty()) {
            return;
        }
        ProcessHandle handle = handleOpt.get();
        try {
            handle.descendants().forEach(child -> {
                try {
                    if (force) {
                        child.destroyForcibly();
                    } else {
                        child.destroy();
                    }
                } catch (Exception ignored) {
                }
            });
            if (force) {
                handle.destroyForcibly();
            } else {
                handle.destroy();
            }
            if (force) {
                sleepQuietStatic(5000L);
                if (handle.isAlive()) {
                    handle.destroyForcibly();
                }
            } else {
                sleepQuietStatic(10000L);
            }
        } catch (Exception e) {
            handle.destroyForcibly();
        }
    }

    static List<URI> resolveStackHealthUris(URI orchestratorHealth) {
        List<URI> uris = new ArrayList<>();
        uris.add(orchestratorHealth);
        if (envBoolean("IML_SUPERVISOR_PROBE_PYTHON", true)) {
            uris.add(URI.create(env("IML_PYTHON_HEALTH_URL", "http://127.0.0.1:8000/detector/health")));
        }
        if (envBoolean("IML_SUPERVISOR_PROBE_LIGHT", false)) {
            uris.add(URI.create(env("IML_LIGHT_HEALTH_URL", "http://127.0.0.1:5080/")));
        }
        return uris;
    }

    private static int exitCodeQuiet(Process process) {
        try {
            return process.exitValue();
        } catch (IllegalThreadStateException e) {
            return -1;
        }
    }

    private static void sleepQuietly(long ms) {
        sleepQuietStatic(ms);
    }

    private static void sleepQuietStatic(long ms) {
        try {
            Thread.sleep(ms);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    private static Path resolveJarPath(String[] args) {
        for (int i = 1; i < args.length - 1; i++) {
            if ("--jar".equals(args[i])) {
                return Path.of(args[i + 1]);
            }
        }
        String envJar = System.getenv("IML_ORCHESTRATOR_JAR");
        if (envJar != null && !envJar.isBlank()) {
            return Path.of(envJar);
        }
        return Path.of("orchestrator-java/target/orchestrator-0.1.0-SNAPSHOT.jar");
    }

    private static long resolveAttachPid(String[] args) {
        for (int i = 1; i < args.length - 1; i++) {
            if ("--attach-pid".equals(args[i])) {
                try {
                    return Long.parseLong(args[i + 1].trim());
                } catch (NumberFormatException e) {
                    return -1L;
                }
            }
        }
        String envPid = System.getenv("IML_SUPERVISOR_ATTACH_PID");
        if (envPid != null && !envPid.isBlank()) {
            try {
                return Long.parseLong(envPid.trim());
            } catch (NumberFormatException ignored) {
            }
        }
        return -1L;
    }

    private static String resolveJavaBinary() {
        String home = System.getenv("JAVA_HOME");
        if (home != null && !home.isBlank()) {
            Path bin = Path.of(home, "bin", isWindows() ? "java.exe" : "java");
            if (Files.isRegularFile(bin)) {
                return bin.toAbsolutePath().toString();
            }
        }
        return "java";
    }

    private static boolean isWindows() {
        return System.getProperty("os.name", "").toLowerCase().contains("win");
    }

    private static String env(String key, String defaultValue) {
        String value = System.getenv(key);
        return value == null || value.isBlank() ? defaultValue : value.trim();
    }

    private static long envLong(String key, long defaultValue) {
        String raw = System.getenv(key);
        if (raw == null || raw.isBlank()) {
            return defaultValue;
        }
        try {
            return Long.parseLong(raw.trim());
        } catch (NumberFormatException e) {
            return defaultValue;
        }
    }

    private static int envInt(String key, int defaultValue) {
        String raw = System.getenv(key);
        if (raw == null || raw.isBlank()) {
            return defaultValue;
        }
        try {
            return Integer.parseInt(raw.trim());
        } catch (NumberFormatException e) {
            return defaultValue;
        }
    }

    private static boolean envBoolean(String key, boolean defaultValue) {
        String raw = System.getenv(key);
        if (raw == null || raw.isBlank()) {
            return defaultValue;
        }
        return switch (raw.trim().toLowerCase()) {
            case "1", "true", "yes", "on" -> true;
            case "0", "false", "no", "off" -> false;
            default -> defaultValue;
        };
    }

    record MonitorResult(String reason, int exitCode, long stableHealthyMs) {
    }
}

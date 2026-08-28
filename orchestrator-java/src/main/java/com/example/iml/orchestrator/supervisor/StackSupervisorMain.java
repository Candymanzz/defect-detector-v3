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
import java.util.List;
import java.util.Optional;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Внешний supervisor: мониторит оркестратор и перезапускает стек после краша.
 * <p>
 * Режим {@code --attach-pid}: не трогает уже запущенный оркестратор (например из DefectDetector.exe),
 * только наблюдает; при падении — cleanup и spawn нового процесса.
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
    private final long healthIntervalMs;
    private final long restartDelayMs;
    private final int healthFailThreshold;
    private final int maxRestarts;
    private final long initialAttachPid;
    private final AtomicBoolean stopRequested = new AtomicBoolean(false);

    StackSupervisorMain(
            Path orchestratorJar,
            Path configPath,
            URI healthUri,
            long healthIntervalMs,
            long restartDelayMs,
            int healthFailThreshold,
            int maxRestarts,
            long initialAttachPid
    ) {
        this.orchestratorJar = orchestratorJar;
        this.configPath = configPath;
        this.healthUri = healthUri;
        this.healthIntervalMs = healthIntervalMs;
        this.restartDelayMs = restartDelayMs;
        this.healthFailThreshold = Math.max(1, healthFailThreshold);
        this.maxRestarts = maxRestarts;
        this.initialAttachPid = initialAttachPid;
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

        StackSupervisorMain supervisor = new StackSupervisorMain(
                jarPath,
                configPath,
                healthUri,
                healthIntervalMs,
                restartDelayMs,
                healthFailThreshold,
                maxRestarts,
                attachPid
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
        long attachPid = initialAttachPid;
        log.info(
                "stack supervisor started jar={} config={} health={} interval_ms={} restart_delay_ms={} fail_threshold={} max_restarts={} attach_pid={}",
                orchestratorJar.toAbsolutePath(),
                configPath.toAbsolutePath(),
                healthUri,
                healthIntervalMs,
                restartDelayMs,
                healthFailThreshold,
                maxRestarts == 0 ? "unlimited" : maxRestarts,
                attachPid > 0 ? attachPid : "none"
        );

        while (!stopRequested.get()) {
            if (maxRestarts > 0 && restartCount >= maxRestarts) {
                log.error("stack supervisor max restarts reached ({})", maxRestarts);
                return 3;
            }
            if (restartCount > 0) {
                log.warn("stack supervisor restart attempt #{}", restartCount);
            }

            Process child = null;
            long externalPid = -1L;
            boolean attachedToLauncher = false;
            try {
                if (attachPid > 0 && isProcessAlive(attachPid) && probeHealth(healthUri, 3000)) {
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
                }

                MonitorResult result = child != null
                        ? monitorUntilUnhealthy(child, -1L, false)
                        : monitorUntilUnhealthy(null, externalPid, true);

                if (stopRequested.get()) {
                    if (child != null) {
                        log.info("stack supervisor stopping spawned orchestrator (user request)");
                        destroyProcessTree(child, false);
                    } else {
                        log.info("stack supervisor stop — leaving launcher orchestrator pid={} running", externalPid);
                    }
                    return 0;
                }
                log.warn(
                        "stack supervisor orchestrator unhealthy reason={} pid={} exit={}",
                        result.reason(),
                        child != null ? child.pid() : externalPid,
                        result.exitCode()
                );
            } catch (IOException e) {
                log.error("stack supervisor failed to start orchestrator: {}", e.getMessage());
            } finally {
                if (child != null) {
                    destroyProcessTree(child, true);
                } else if (!attachedToLauncher && externalPid > 0) {
                    destroyProcessTreeByPid(externalPid, true);
                }
                cleanupOrphanPorts("post-crash");
            }
            restartCount++;
            if (stopRequested.get()) {
                return 0;
            }
            sleepQuietly(restartDelayMs);
        }
        return 0;
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

    private MonitorResult monitorUntilUnhealthy(Process child, long externalPid, boolean attachMode) {
        int consecutiveHealthFails = 0;
        long nextHealthProbeMs = 0L;
        while (!stopRequested.get()) {
            boolean alive = child != null ? child.isAlive() : isProcessAlive(externalPid);
            if (!alive) {
                int exit = child != null ? exitCodeQuiet(child) : -1;
                return new MonitorResult("process-exited", exit);
            }
            long now = System.currentTimeMillis();
            if (now >= nextHealthProbeMs) {
                nextHealthProbeMs = now + healthIntervalMs;
                if (probeHealth(healthUri, 3000)) {
                    consecutiveHealthFails = 0;
                } else {
                    consecutiveHealthFails++;
                    log.warn(
                            "stack supervisor health probe failed ({}/{}) url={}",
                            consecutiveHealthFails,
                            healthFailThreshold,
                            healthUri
                    );
                    if (consecutiveHealthFails >= healthFailThreshold) {
                        return new MonitorResult("health-timeout", -1);
                    }
                }
            }
            sleepQuietly(500L);
        }
        return new MonitorResult("stop-requested", -1);
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

    record MonitorResult(String reason, int exitCode) {
    }
}

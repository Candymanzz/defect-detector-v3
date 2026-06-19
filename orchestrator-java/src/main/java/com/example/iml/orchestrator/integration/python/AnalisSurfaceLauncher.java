package com.example.iml.orchestrator.integration.python;

import com.example.iml.orchestrator.integration.config.CameraWorkerPaths;
import com.example.iml.orchestrator.integration.config.YamlScalars;
import com.example.iml.orchestrator.integration.subprocess.ExternalServiceProcess;
import org.apache.logging.log4j.Logger;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Автозапуск пула FastAPI analisSurface (uvicorn) из {@code integration.analis_surface_command_*}.
 * Размер пула совпадает с {@code integration.python_parallelism}: отдельный OS-процесс и порт на инстанс.
 */
public final class AnalisSurfaceLauncher {

    private static final String PROCESS_LABEL = "analis-surface";
    private static final String DEFAULT_BACKEND_DIR = "analisSurface/backend";
    private static final String DEFAULT_HOST = "127.0.0.1";
    private static final int DEFAULT_PORT = 8000;

    private final Logger log;

    public AnalisSurfaceLauncher(Logger log) {
        this.log = log;
    }

    public record AutostartSettings(
            boolean enabled,
            Path workingDir,
            int startupTimeoutMs,
            String healthPath,
            int inspectWorkers
    ) {
    }

    public record PoolStartResult(
            List<ExternalServiceProcess> processes,
            List<String> baseUrls,
            boolean autostartEnabled
    ) {
    }

    /**
     * Поднимает {@code poolSize} uvicorn-процессов на портах base_port..base_port+poolSize-1
     * (host и base_port из {@code python_detector.base_url}).
     */
    public PoolStartResult startPoolIfConfigured(
            Map<String, Object> integration,
            Path projectRoot,
            boolean isWindows,
            String pythonDetectorBaseUrl,
            int poolSize,
            int startupStaggerMs
    ) {
        int size = Math.max(1, poolSize);
        AutostartSettings settings = parseSettings(integration, projectRoot);
        List<String> baseUrls = buildBaseUrlPool(pythonDetectorBaseUrl, size);
        if (!settings.enabled()) {
            log.info(
                    "analisSurface autostart disabled — HTTP pool size={} expects external servers at {}",
                    size,
                    baseUrls
            );
            return new PoolStartResult(List.of(), baseUrls, false);
        }

        String cmdKey = isWindows ? "analis_surface_command_windows" : "analis_surface_command_linux";
        List<String> commandTemplate = CameraWorkerPaths.pickIntegrationCommandList(integration, isWindows, cmdKey, cmdKey);
        if (commandTemplate.isEmpty()) {
            commandTemplate = defaultCommand(projectRoot, isWindows);
        }
        if (commandTemplate.isEmpty()) {
            log.warn("analisSurface autostart: команда не задана и venv не найден ({})", DEFAULT_BACKEND_DIR);
            return new PoolStartResult(List.of(), baseUrls, true);
        }
        if (!Files.isDirectory(settings.workingDir())) {
            log.warn(
                    "analisSurface working_dir не найден: {} — проверьте integration.analis_surface_autostart.working_dir",
                    settings.workingDir().toAbsolutePath()
            );
            return new PoolStartResult(List.of(), baseUrls, true);
        }

        List<String> resolvedTemplate = resolveCommandPaths(commandTemplate, projectRoot);
        List<ExternalServiceProcess> processes = new ArrayList<>(size);
        ParsedBase parsed = parseDetectorBaseUrl(pythonDetectorBaseUrl);
        String healthPath = settings.healthPath().startsWith("/")
                ? settings.healthPath()
                : "/" + settings.healthPath();

        for (int i = 0; i < size; i++) {
            if (i > 0 && startupStaggerMs > 0) {
                try {
                    Thread.sleep(startupStaggerMs);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    break;
                }
            }
            int port = parsed.port() + i;
            String baseUrl = formatBaseUrl(parsed.host(), port);
            String healthUrl = baseUrl + healthPath;
            List<String> command = withPort(resolvedTemplate, port);
            String processName = size == 1 ? PROCESS_LABEL : (PROCESS_LABEL + "-" + i);
            try {
                ExternalServiceProcess process = ExternalServiceProcess.start(
                        processName,
                        command,
                        settings.workingDir(),
                        Map.of("ANALIS_INSPECT_WORKERS", String.valueOf(settings.inspectWorkers()))
                );
                waitForHealth(healthUrl, settings.startupTimeoutMs(), process);
                processes.add(process);
                log.info(
                        "analisSurface ready index={} url={} inspect_workers={} command={} cwd={}",
                        i,
                        baseUrl,
                        settings.inspectWorkers(),
                        command,
                        settings.workingDir().toAbsolutePath()
                );
            } catch (Exception e) {
                log.warn("analisSurface autostart failed index={} port={} command={}: {}", i, port, command, e.getMessage());
                for (ExternalServiceProcess started : processes) {
                    started.close();
                }
                return new PoolStartResult(List.of(), baseUrls, true);
            }
        }
        log.info("analisSurface pool ready size={} ports={}..{}", size, parsed.port(), parsed.port() + size - 1);
        return new PoolStartResult(List.copyOf(processes), baseUrls, true);
    }

    public static AutostartSettings parseSettings(Map<String, Object> integration, Path projectRoot) {
        boolean enabled = true;
        String workingDirRel = DEFAULT_BACKEND_DIR;
        int timeoutMs = 180_000;
        String healthPath = "/health";
        int inspectWorkers = 10;

        if (integration != null) {
            Object raw = integration.get("analis_surface_autostart");
            if (raw instanceof Map<?, ?> m) {
                enabled = YamlScalars.toBool(m.get("enabled"), true);
                if (m.get("working_dir") != null) {
                    workingDirRel = String.valueOf(m.get("working_dir")).trim();
                }
                timeoutMs = Math.max(5_000, YamlScalars.toInt(m.get("startup_timeout_ms"), timeoutMs));
                if (m.get("health_path") != null) {
                    healthPath = String.valueOf(m.get("health_path")).trim();
                }
                inspectWorkers = Math.max(1, YamlScalars.toInt(m.get("inspect_workers"), inspectWorkers));
            }
            Object cmdWin = integration.get("analis_surface_command_windows");
            Object cmdLinux = integration.get("analis_surface_command_linux");
            boolean hasCommand = cmdWin instanceof List<?> lw && !lw.isEmpty()
                    || cmdLinux instanceof List<?> ll && !ll.isEmpty();
            if (!hasCommand && raw == null) {
                enabled = false;
            }
        }

        Path workingDir = projectRoot.resolve(workingDirRel).normalize();
        return new AutostartSettings(enabled, workingDir, timeoutMs, healthPath, inspectWorkers);
    }

    static List<String> buildBaseUrlPool(String pythonDetectorBaseUrl, int poolSize) {
        int size = Math.max(1, poolSize);
        ParsedBase parsed = parseDetectorBaseUrl(pythonDetectorBaseUrl);
        List<String> out = new ArrayList<>(size);
        for (int i = 0; i < size; i++) {
            out.add(formatBaseUrl(parsed.host(), parsed.port() + i));
        }
        return List.copyOf(out);
    }

    static List<String> withPort(List<String> command, int port) {
        List<String> out = new ArrayList<>(command.size() + 2);
        for (int i = 0; i < command.size(); i++) {
            if ("--port".equals(command.get(i)) && i + 1 < command.size()) {
                out.add("--port");
                out.add(String.valueOf(port));
                i++;
                continue;
            }
            out.add(command.get(i));
        }
        if (!out.contains("--port")) {
            out.add("--port");
            out.add(String.valueOf(port));
        }
        return List.copyOf(out);
    }

    private record ParsedBase(String host, int port) {
    }

    private static ParsedBase parseDetectorBaseUrl(String pythonDetectorBaseUrl) {
        String base = pythonDetectorBaseUrl == null || pythonDetectorBaseUrl.isBlank()
                ? "http://" + DEFAULT_HOST + ":" + DEFAULT_PORT
                : pythonDetectorBaseUrl.trim();
        if (base.endsWith("/")) {
            base = base.substring(0, base.length() - 1);
        }
        try {
            URI uri = URI.create(base);
            String host = uri.getHost();
            int port = uri.getPort();
            if (host == null || host.isBlank()) {
                host = DEFAULT_HOST;
            }
            if (port <= 0) {
                port = DEFAULT_PORT;
            }
            return new ParsedBase(host, port);
        } catch (Exception ignored) {
            return new ParsedBase(DEFAULT_HOST, DEFAULT_PORT);
        }
    }

    private static String formatBaseUrl(String host, int port) {
        return "http://" + host + ":" + port;
    }

    private static List<String> defaultCommand(Path projectRoot, boolean isWindows) {
        Path backend = projectRoot.resolve(DEFAULT_BACKEND_DIR);
        Path venvPython = isWindows
                ? backend.resolve(".venv/Scripts/python.exe")
                : backend.resolve(".venv/bin/python");
        if (Files.isRegularFile(venvPython)) {
            return List.of(
                    venvPython.toAbsolutePath().toString(),
                    "-m",
                    "uvicorn",
                    "app.main:app",
                    "--host",
                    DEFAULT_HOST,
                    "--port",
                    String.valueOf(DEFAULT_PORT)
            );
        }
        return List.of(
                isWindows ? "python" : "python3",
                "-m",
                "uvicorn",
                "app.main:app",
                "--host",
                DEFAULT_HOST,
                "--port",
                String.valueOf(DEFAULT_PORT)
        );
    }

    private static List<String> resolveCommandPaths(List<String> command, Path projectRoot) {
        if (command.isEmpty()) {
            return command;
        }
        List<String> out = new ArrayList<>(command.size());
        String first = command.get(0);
        Path p = Path.of(first);
        if (!p.isAbsolute() && (first.contains("/") || first.contains("\\"))) {
            out.add(projectRoot.resolve(first).normalize().toString());
        } else {
            out.add(first);
        }
        for (int i = 1; i < command.size(); i++) {
            out.add(command.get(i));
        }
        return out;
    }

    private void waitForHealth(String healthUrl, int timeoutMs, ExternalServiceProcess process) throws InterruptedException {
        HttpClient client = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(5))
                .build();
        HttpRequest request = HttpRequest.newBuilder(URI.create(healthUrl))
                .timeout(Duration.ofSeconds(5))
                .GET()
                .build();
        long deadline = System.currentTimeMillis() + timeoutMs;
        while (System.currentTimeMillis() < deadline) {
            if (!process.isAlive()) {
                throw new IllegalStateException("analisSurface process exited before health check OK");
            }
            try {
                HttpResponse<Void> resp = client.send(request, HttpResponse.BodyHandlers.discarding());
                if (resp.statusCode() >= 200 && resp.statusCode() < 300) {
                    return;
                }
            } catch (Exception ignored) {
            }
            Thread.sleep(500L);
        }
        throw new IllegalStateException("analisSurface health timeout: " + healthUrl);
    }
}

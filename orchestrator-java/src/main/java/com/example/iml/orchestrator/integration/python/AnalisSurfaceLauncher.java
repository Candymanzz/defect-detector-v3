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
 * Автозапуск FastAPI analisSurface (uvicorn) из {@code integration.analis_surface_command_*}.
 */
public final class AnalisSurfaceLauncher {

    private static final String PROCESS_LABEL = "analis-surface";
    private static final String DEFAULT_BACKEND_DIR = "analisSurface/backend";

    private final Logger log;

    public AnalisSurfaceLauncher(Logger log) {
        this.log = log;
    }

    public record AutostartSettings(
            boolean enabled,
            Path workingDir,
            String healthUrl,
            int startupTimeoutMs
    ) {
    }

    public ExternalServiceProcess startIfConfigured(
            Map<String, Object> integration,
            Path projectRoot,
            boolean isWindows,
            String pythonDetectorBaseUrl
    ) {
        AutostartSettings settings = parseSettings(integration, projectRoot, pythonDetectorBaseUrl);
        if (!settings.enabled()) {
            log.info("analisSurface autostart disabled — ожидается внешний uvicorn на {}", settings.healthUrl());
            return null;
        }

        String cmdKey = isWindows ? "analis_surface_command_windows" : "analis_surface_command_linux";
        List<String> command = CameraWorkerPaths.pickIntegrationCommandList(integration, isWindows, cmdKey, cmdKey);
        if (command.isEmpty()) {
            command = defaultCommand(projectRoot, isWindows);
        }
        if (command.isEmpty()) {
            log.warn("analisSurface autostart: команда не задана и venv не найден ({})", DEFAULT_BACKEND_DIR);
            return null;
        }

        List<String> resolvedCommand = resolveCommandPaths(command, projectRoot);
        if (!Files.isDirectory(settings.workingDir())) {
            log.warn(
                    "analisSurface working_dir не найден: {} — проверьте integration.analis_surface_autostart.working_dir",
                    settings.workingDir().toAbsolutePath()
            );
            return null;
        }

        try {
            ExternalServiceProcess process = ExternalServiceProcess.start(
                    PROCESS_LABEL,
                    resolvedCommand,
                    settings.workingDir()
            );
            waitForHealth(settings.healthUrl(), settings.startupTimeoutMs(), process);
            log.info(
                    "analisSurface ready url={} command={} cwd={}",
                    settings.healthUrl(),
                    resolvedCommand,
                    settings.workingDir().toAbsolutePath()
            );
            return process;
        } catch (Exception e) {
            log.warn("analisSurface autostart failed command={}: {}", resolvedCommand, e.getMessage());
            return null;
        }
    }

    public static AutostartSettings parseSettings(
            Map<String, Object> integration,
            Path projectRoot,
            String pythonDetectorBaseUrl
    ) {
        boolean enabled = true;
        String workingDirRel = DEFAULT_BACKEND_DIR;
        int timeoutMs = 180_000;
        String healthPath = "/health";

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
            }
            Object cmdWin = integration.get("analis_surface_command_windows");
            Object cmdLinux = integration.get("analis_surface_command_linux");
            boolean hasCommand = cmdWin instanceof List<?> lw && !lw.isEmpty()
                    || cmdLinux instanceof List<?> ll && !ll.isEmpty();
            if (!hasCommand && raw == null) {
                enabled = false;
            }
        }

        String base = pythonDetectorBaseUrl == null || pythonDetectorBaseUrl.isBlank()
                ? "http://127.0.0.1:8000"
                : pythonDetectorBaseUrl.trim();
        if (base.endsWith("/")) {
            base = base.substring(0, base.length() - 1);
        }
        String path = healthPath.startsWith("/") ? healthPath : "/" + healthPath;
        String healthUrl = base + path;
        Path workingDir = projectRoot.resolve(workingDirRel).normalize();
        return new AutostartSettings(enabled, workingDir, healthUrl, timeoutMs);
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
                    "127.0.0.1",
                    "--port",
                    "8000"
            );
        }
        return List.of(
                isWindows ? "python" : "python3",
                "-m",
                "uvicorn",
                "app.main:app",
                "--host",
                "127.0.0.1",
                "--port",
                "8000"
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

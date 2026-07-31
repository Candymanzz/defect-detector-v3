package com.example.iml.orchestrator.integration.python;

import com.example.iml.orchestrator.integration.config.CameraWorkerPaths;
import com.example.iml.orchestrator.integration.config.YamlScalars;
import com.example.iml.orchestrator.integration.subprocess.ExternalServiceProcess;
import org.apache.logging.log4j.Logger;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Автозапуск пула FastAPI analisSurface (uvicorn) из {@code integration.analis_surface_command_*}.
 * Размер пула совпадает с {@code integration.python_parallelism}: отдельный OS-процесс и порт на инстанс.
 */
public final class AnalisSurfaceLauncher {

    private static final String PROCESS_LABEL = "analis-surface";

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
        List<String> baseUrls = AnalisSurfaceLaunchSupport.buildBaseUrlPool(pythonDetectorBaseUrl, size);
        if (!settings.enabled()) {
            log.info(
                    "analisSurface autostart disabled — HTTP pool size={} expects external servers at {}",
                    size,
                    baseUrls
            );
            return new PoolStartResult(List.of(), baseUrls, false);
        }

        String cmdKey = isWindows ? "analis_surface_command_windows" : "analis_surface_command_linux";
        List<String> commandTemplate = CameraWorkerPaths.pickIntegrationCommandList(
                integration, isWindows, cmdKey, cmdKey);
        if (commandTemplate.isEmpty()) {
            commandTemplate = AnalisSurfaceLaunchSupport.defaultCommand(projectRoot, isWindows);
        }
        if (commandTemplate.isEmpty()) {
            log.warn(
                    "analisSurface autostart: команда не задана и venv не найден ({})",
                    AnalisSurfaceLaunchSupport.DEFAULT_BACKEND_DIR
            );
            return new PoolStartResult(List.of(), baseUrls, true);
        }
        if (!Files.isDirectory(settings.workingDir())) {
            log.warn(
                    "analisSurface working_dir не найден: {} — проверьте integration.analis_surface_autostart.working_dir",
                    settings.workingDir().toAbsolutePath()
            );
            return new PoolStartResult(List.of(), baseUrls, true);
        }

        List<String> resolvedTemplate = AnalisSurfaceLaunchSupport.resolveCommandPaths(
                commandTemplate, projectRoot);
        List<ExternalServiceProcess> processes = new ArrayList<>(size);
        AnalisSurfaceLaunchSupport.ParsedBase parsed =
                AnalisSurfaceLaunchSupport.parseDetectorBaseUrl(pythonDetectorBaseUrl);
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
            String baseUrl = AnalisSurfaceLaunchSupport.formatBaseUrl(parsed.host(), port);
            String healthUrl = baseUrl + healthPath;
            List<String> command = AnalisSurfaceLaunchSupport.withPort(resolvedTemplate, port);
            String processName = size == 1 ? PROCESS_LABEL : (PROCESS_LABEL + "-" + i);
            try {
                ExternalServiceProcess process = ExternalServiceProcess.start(
                        processName,
                        command,
                        settings.workingDir(),
                        Map.of("ANALIS_INSPECT_WORKERS", String.valueOf(settings.inspectWorkers()))
                );
                AnalisSurfaceLaunchSupport.waitForHealth(healthUrl, settings.startupTimeoutMs(), process);
                processes.add(process);
                log.info(
                        "analisSurface ready index={} url={} command={} cwd={}",
                        i,
                        baseUrl,
                        command,
                        settings.workingDir().toAbsolutePath()
                );
            } catch (Exception e) {
                log.warn(
                        "analisSurface autostart failed index={} port={} command={}: {}",
                        i, port, command, e.getMessage());
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
        String workingDirRel = AnalisSurfaceLaunchSupport.DEFAULT_BACKEND_DIR;
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
        return AnalisSurfaceLaunchSupport.buildBaseUrlPool(pythonDetectorBaseUrl, poolSize);
    }

    static List<String> withPort(List<String> command, int port) {
        return AnalisSurfaceLaunchSupport.withPort(command, port);
    }
}

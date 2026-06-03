package com.example.iml.orchestrator.integration.lighting;

import com.example.iml.orchestrator.integration.subprocess.ExternalServiceProcess;
import org.apache.logging.log4j.Logger;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Опциональный запуск LightServer.v3 из {@code integration.light_server_command_*}.
 */
public final class LightServerLauncher {

    private final Logger log;

    public LightServerLauncher(Logger log) {
        this.log = log;
    }

    public ExternalServiceProcess startIfConfigured(
            Map<String, Object> integration,
            Path projectRoot,
            boolean isWindows,
            int startupDelayMs
    ) {
        if (integration == null) {
            return null;
        }
        String configKey = isWindows ? "light_server_command_windows" : "light_server_command_linux";
        Object raw = integration.get(configKey);
        if (!(raw instanceof List<?> list) || list.isEmpty()) {
            return null;
        }
        List<String> command = new ArrayList<>();
        for (Object e : list) {
            command.add(String.valueOf(e));
        }
        try {
            Map<String, String> extraEnv = contentRootEnvForLightServer(command, projectRoot);
            ExternalServiceProcess process = ExternalServiceProcess.start(
                    "light-server", command, projectRoot, extraEnv);
            if (startupDelayMs > 0) {
                Thread.sleep(startupDelayMs);
            }
            log.info("started light-server (LightServer.v3) command={} contentRoot={}",
                    command, extraEnv.getOrDefault("ASPNETCORE_CONTENTROOT", projectRoot.toString()));
            return process;
        } catch (Exception e) {
            log.warn("failed to start optional light-server command={}: {}", command, e.getMessage());
            return null;
        }
    }

    /**
     * Оркестратор стартует процесс с cwd = корень репозитория; appsettings с {@code ComLightDevices}
     * лежит рядом с {@code LightServer.dll}. Без этого GET /api/com/light → {@code devices:[]}.
     */
    private Map<String, String> contentRootEnvForLightServer(List<String> command, Path projectRoot) {
        for (String part : command) {
            if (part == null || part.length() < 4
                    || !part.regionMatches(true, part.length() - 4, ".dll", 0, 4)) {
                continue;
            }
            Path dll = Path.of(part);
            if (!dll.isAbsolute()) {
                dll = projectRoot.resolve(dll).normalize();
            }
            Path dir = dll.getParent();
            if (dir != null && Files.isDirectory(dir)) {
                Map<String, String> env = new LinkedHashMap<>();
                env.put("ASPNETCORE_CONTENTROOT", dir.toString());
                return env;
            }
            break;
        }
        return Map.of();
    }
}

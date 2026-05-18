package com.example.iml.orchestrator.integration.lighting;

import com.example.iml.orchestrator.integration.subprocess.ExternalServiceProcess;
import org.apache.logging.log4j.Logger;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Опциональный запуск процессов LightServer и LightServerv.v2 из {@code integration.light_server*_command_*}.
 */
public final class LightServerLauncher {

    private final Logger log;

    public LightServerLauncher(Logger log) {
        this.log = log;
    }

    public record StartedProcesses(ExternalServiceProcess primary, ExternalServiceProcess secondary) {
    }

    public StartedProcesses startAllIfConfigured(
            Map<String, Object> integration,
            Path projectRoot,
            boolean isWindows,
            int startupDelayMs
    ) {
        ExternalServiceProcess primary = startOne(integration, projectRoot, isWindows,
                isWindows ? "light_server_command_windows" : "light_server_command_linux",
                "light-server", startupDelayMs);
        ExternalServiceProcess secondary = startOne(integration, projectRoot, isWindows,
                isWindows ? "light_server_v2_command_windows" : "light_server_v2_command_linux",
                "light-server-v2", 0);
        return new StartedProcesses(primary, secondary);
    }

    public ExternalServiceProcess startIfConfigured(
            Map<String, Object> integration,
            Path projectRoot,
            boolean isWindows,
            int startupDelayMs
    ) {
        return startAllIfConfigured(integration, projectRoot, isWindows, startupDelayMs).primary();
    }

    private ExternalServiceProcess startOne(
            Map<String, Object> integration,
            Path projectRoot,
            boolean isWindows,
            String configKey,
            String processLabel,
            int startupDelayMs
    ) {
        if (integration == null) {
            return null;
        }
        Object raw = integration.get(configKey);
        if (!(raw instanceof List<?> list) || list.isEmpty()) {
            return null;
        }
        List<String> command = new ArrayList<>();
        for (Object e : list) {
            command.add(String.valueOf(e));
        }
        try {
            ExternalServiceProcess process = ExternalServiceProcess.start(processLabel, command, projectRoot);
            if (startupDelayMs > 0) {
                Thread.sleep(startupDelayMs);
            }
            log.info("started {} command={}", processLabel, command);
            return process;
        } catch (Exception e) {
            log.warn("failed to start optional {} command={}: {}", processLabel, command, e.getMessage());
            return null;
        }
    }
}

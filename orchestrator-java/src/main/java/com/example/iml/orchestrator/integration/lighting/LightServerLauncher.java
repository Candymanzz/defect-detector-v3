package com.example.iml.orchestrator.integration.lighting;

import com.example.iml.orchestrator.integration.subprocess.ExternalServiceProcess;
import org.apache.logging.log4j.Logger;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Опциональный запуск процесса LightServer из {@code integration.light_server_command_*}.
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
        String key = isWindows ? "light_server_command_windows" : "light_server_command_linux";
        Object raw = integration.get(key);
        if (!(raw instanceof List<?> list) || list.isEmpty()) {
            return null;
        }
        List<String> command = new ArrayList<>();
        for (Object e : list) {
            command.add(String.valueOf(e));
        }
        try {
            ExternalServiceProcess process = ExternalServiceProcess.start("light-server", command, projectRoot);
            if (startupDelayMs > 0) {
                Thread.sleep(startupDelayMs);
            }
            return process;
        } catch (Exception e) {
            log.warn("failed to start optional light-server process command={}: {}", command, e.getMessage());
            return null;
        }
    }
}

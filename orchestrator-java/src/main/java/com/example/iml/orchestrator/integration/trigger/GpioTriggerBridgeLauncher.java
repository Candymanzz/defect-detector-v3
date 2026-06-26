package com.example.iml.orchestrator.integration.trigger;

import com.example.iml.orchestrator.integration.subprocess.ExternalServiceProcess;
import org.apache.logging.log4j.Logger;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Опциональный запуск {@code tools/GpioUdpBridge} — DI → UDP на оркестратор.
 * LightServer к триггеру не относится (только подсветка).
 */
public final class GpioTriggerBridgeLauncher {

    private final Logger log;

    public GpioTriggerBridgeLauncher(Logger log) {
        this.log = log;
    }

    public ExternalServiceProcess startIfConfigured(
            Map<String, Object> integration,
            Path projectRoot,
            boolean isWindows
    ) {
        if (integration == null) {
            return null;
        }
        if (!isTriggerBridgeEnabled(integration)) {
            return null;
        }
        String configKey = isWindows ? "gpio_trigger_command_windows" : "gpio_trigger_command_linux";
        Object raw = integration.get(configKey);
        if (!(raw instanceof List<?> list) || list.isEmpty()) {
            log.warn("gpio_trigger_bridge enabled but {} is empty", configKey);
            return null;
        }
        List<String> command = new ArrayList<>();
        for (Object entry : list) {
            command.add(String.valueOf(entry));
        }
        try {
            ExternalServiceProcess process = ExternalServiceProcess.start("gpio-udp-bridge", command, projectRoot, Map.of());
            log.info("started gpio-udp-bridge (DI → UDP orchestrator) command={}", command);
            watchProcessHealth(process);
            return process;
        } catch (Exception e) {
            log.warn("failed to start gpio-udp-bridge command={}: {}", command, e.getMessage());
            return null;
        }
    }

    private void watchProcessHealth(ExternalServiceProcess process) {
        Thread watcher = new Thread(() -> {
            try {
                Thread.sleep(2000);
                if (!process.isAlive()) {
                    log.error(
                            "gpio-udp-bridge exited immediately — кнопка DI не работает. "
                                    + "Запустите run.ps1 от администратора или вручную: "
                                    + "tools/GpioUdpBridge/bin/Release/net10.0/GpioUdpBridge.exe --monitor");
                    return;
                }
                while (process.isAlive()) {
                    Thread.sleep(5000);
                }
                log.error(
                        "gpio-udp-bridge stopped unexpectedly — кнопка DI больше не работает. "
                                + "Проверьте WinIO (права администратора) и лог GpioUdpBridge.");
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }, "gpio-udp-bridge-watch");
        watcher.setDaemon(true);
        watcher.start();
    }

    private static boolean isTriggerBridgeEnabled(Map<String, Object> integration) {
        Object raw = integration.get("gpio_trigger_bridge");
        if (raw instanceof Map<?, ?> map) {
            Object enabled = map.get("enabled");
            if (enabled != null) {
                return Boolean.parseBoolean(String.valueOf(enabled));
            }
        }
        return integration.containsKey("gpio_trigger_command_windows")
                || integration.containsKey("gpio_trigger_command_linux");
    }
}

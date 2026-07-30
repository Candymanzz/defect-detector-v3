package com.example.iml.orchestrator.config;

import com.example.iml.orchestrator.integration.config.YamlMaps;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import java.nio.file.Path;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * Формирует сводку по загруженному конфигу в лог (отдельно от загрузки и от точки входа).
 */
public final class StartupConfigurationReporter {

    private static final Logger log = LogManager.getLogger(StartupConfigurationReporter.class);

    public void report(Map<String, Object> root, Path configPath) {
        Object version = root.get("version");
        List<Map<String, Object>> cameras = YamlMaps.listOfStringObjectMaps(root.get("cameras"));
        Map<String, Object> orchestrator = YamlMaps.stringObjectMapOrNull(root.get("orchestrator"));
        Map<String, Object> client = YamlMaps.stringObjectMapOrNull(root.get("client"));
        Map<String, Object> robot = YamlMaps.stringObjectMapOrNull(root.get("robot"));

        log.info(
                "Оркестратор: старт с конфигом версии {} ({}) — камер: {}",
                version,
                configPath.toAbsolutePath(),
                cameras.size()
        );

        logMapKeys(orchestrator, "control_pipe", "control_pipe_linux");
        logMapKey(client, "client.url", "url");
        Map<String, Object> clientWs = YamlMaps.stringObjectMapOrNull(root.get("client_ws"));
        Optional.ofNullable(clientWs).ifPresent(ws -> log.info(
                "  client_ws: enabled={} host={} port={} path={}",
                ws.get("enabled"),
                ws.get("host"),
                ws.get("port"),
                ws.get("path")
        ));
        Optional.ofNullable(robot)
                .ifPresent(r -> log.info("  robot.url: {} enabled={}", r.get("url"), r.get("enabled")));
    }

    /** Печатает строки {@code label: value} для каждого ключа из секции; секция отсутствует — ничего не делаем. */
    private void logMapKeys(Map<String, Object> section, String... keys) {
        Optional.ofNullable(section)
                .ifPresent(s -> Arrays.stream(keys).forEach(k -> log.info("  {}: {}", k, s.get(k))));
    }

    /** Одна строка с произвольной подписью и значением из карты по {@code mapKey}. */
    private void logMapKey(Map<String, Object> section, String label, String mapKey) {
        Optional.ofNullable(section).ifPresent(s -> log.info("  {}: {}", label, s.get(mapKey)));
    }
}

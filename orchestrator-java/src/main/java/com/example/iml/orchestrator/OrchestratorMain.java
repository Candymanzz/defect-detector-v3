package com.example.iml.orchestrator;

import com.example.iml.orchestrator.config.StartupConfigurationReporter;
import com.example.iml.orchestrator.config.YamlFileConfigLoader;
import com.example.iml.orchestrator.integration.logging.LogSession;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import java.nio.file.Files;
import java.nio.file.Path;

/**
 * Точка входа: только разбор аргументов, проверка пути и делегирование {@link OrchestratorApplication}.
 */
public final class OrchestratorMain {

    private static Logger log;

    public static void main(String[] args) {
        Path configPath = Path.of(args.length > 0 ? args[0] : "config/config.yaml");
        // Папка сессии логов должна появиться до первой инициализации log4j2 (iml.log.dir).
        LogSession.init(LogSession.logsRootForConfig(configPath));
        log = LogManager.getLogger(OrchestratorMain.class);
        if (!Files.isRegularFile(configPath)) {
            log.error(
                    "Файл конфигурации не найден: {}. Запуск из корня репозитория или передайте путь к YAML.",
                    configPath.toAbsolutePath()
            );
            System.exit(1);
        }

        var application = new OrchestratorApplication(
                new YamlFileConfigLoader(),
                new StartupConfigurationReporter()
        );
        try {
            application.run(configPath);
        } catch (OrchestratorStartupException e) {
            log.error("Ошибка при старте оркестратора: {}", e.getMessage(), e);
            System.exit(2);
        }
    }
}

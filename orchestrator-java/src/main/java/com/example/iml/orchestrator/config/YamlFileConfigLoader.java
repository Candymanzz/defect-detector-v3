package com.example.iml.orchestrator.config;

import org.yaml.snakeyaml.Yaml;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;

/** Загрузка корневого дерева конфигурации из YAML-файла. */
public final class YamlFileConfigLoader {

    private final Yaml yaml = new Yaml();

    @SuppressWarnings("unchecked")
    public Map<String, Object> load(Path path) throws IOException {
        try (InputStream in = Files.newInputStream(path)) {
            return (Map<String, Object>) yaml.load(in);
        }
    }
}

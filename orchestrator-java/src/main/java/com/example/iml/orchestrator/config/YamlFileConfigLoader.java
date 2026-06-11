package com.example.iml.orchestrator.config;

import org.yaml.snakeyaml.Yaml;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** Загрузка корневого дерева конфигурации из YAML-файла. */
public final class YamlFileConfigLoader {

    private final Yaml yaml = new Yaml();

    @SuppressWarnings("unchecked")
    public Map<String, Object> load(Path path) throws IOException {
        return loadWithImports(path.toAbsolutePath().normalize(), new HashSet<>());
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> loadWithImports(Path path, Set<Path> stack) throws IOException {
        if (!stack.add(path)) {
            throw new IOException("Обнаружен циклический imports в YAML: " + path);
        }
        try {
            final Map<String, Object> root;
            try (InputStream in = Files.newInputStream(path)) {
                root = (Map<String, Object>) yaml.load(in);
            }
            if (root == null) {
                return null;
            }
            Object importsRaw = root.remove("imports");
            if (!(importsRaw instanceof List<?> imports) || imports.isEmpty()) {
                return root;
            }

            Path baseDir = path.getParent();
            Map<String, Object> merged = new LinkedHashMap<>();
            for (Object importEntry : imports) {
                if (importEntry == null) {
                    continue;
                }
                String importPathText = String.valueOf(importEntry).trim();
                if (importPathText.isEmpty()) {
                    continue;
                }
                Path importPath = resolveImportPath(baseDir, importPathText);
                Map<String, Object> imported = loadWithImports(importPath, stack);
                if (imported != null) {
                    mergeMapsDeep(merged, imported);
                }
            }
            mergeMapsDeep(merged, root);
            return merged;
        } finally {
            stack.remove(path);
        }
    }

    private static Path resolveImportPath(Path baseDir, String rawPath) {
        Path candidate = Path.of(rawPath);
        if (!candidate.isAbsolute()) {
            candidate = baseDir.resolve(candidate);
        }
        return candidate.normalize().toAbsolutePath();
    }

    @SuppressWarnings("unchecked")
    private static void mergeMapsDeep(Map<String, Object> target, Map<String, Object> source) {
        for (Map.Entry<String, Object> entry : source.entrySet()) {
            String key = entry.getKey();
            Object sourceValue = entry.getValue();
            Object targetValue = target.get(key);
            if (sourceValue instanceof Map<?, ?> sourceMap && targetValue instanceof Map<?, ?> targetMap) {
                Map<String, Object> mergedChild = new LinkedHashMap<>((Map<String, Object>) targetMap);
                mergeMapsDeep(mergedChild, (Map<String, Object>) sourceMap);
                target.put(key, mergedChild);
                continue;
            }
            if (sourceValue instanceof List<?> sourceList) {
                target.put(key, new ArrayList<>(sourceList));
                continue;
            }
            target.put(key, sourceValue);
        }
    }
}

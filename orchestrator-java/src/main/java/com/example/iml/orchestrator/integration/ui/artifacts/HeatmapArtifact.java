package com.example.iml.orchestrator.integration.ui.artifacts;

import java.nio.file.Path;

/**
 * Путь и размеры heatmap после ответа {@code inspect_shm} (поля заголовка задаёт Python).
 */
public record HeatmapArtifact(Path path, int width, int height) {
    public static HeatmapArtifact empty() {
        return new HeatmapArtifact(null, 0, 0);
    }
}

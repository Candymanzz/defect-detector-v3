package com.example.iml.orchestrator.integration.ui.artifacts;

import java.nio.file.Path;

/** Frozen inspection frame buffer used by async UI JPEG/heatmap publish. */
public record FrozenFrame(Path path, String shmName, boolean deleteWhenDone) {
}

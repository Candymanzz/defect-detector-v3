package com.example.iml.orchestrator.integration.pipeline.bucket;

/**
 * Необязательный preset, если {@code inspection_bucket.groups} не задан явно в YAML.
 */
public enum InspectionOperationMode {
    FIVE_CAMERAS(5),
    TEN_CAMERAS(10);

    private final int cameraCount;

    InspectionOperationMode(int cameraCount) {
        this.cameraCount = cameraCount;
    }

    public int cameraCount() {
        return cameraCount;
    }

    public static InspectionOperationMode fromConfig(String raw) {
        if (raw == null || raw.isBlank()) {
            return FIVE_CAMERAS;
        }
        String normalized = raw.trim().toLowerCase().replace('-', '_');
        return switch (normalized) {
            case "ten_cameras", "10", "10_cameras" -> TEN_CAMERAS;
            case "five_cameras", "5", "5_cameras" -> FIVE_CAMERAS;
            default -> throw new IllegalArgumentException(
                    "unsupported inspection_bucket.mode: " + raw + " (expected five_cameras or ten_cameras)"
            );
        };
    }
}

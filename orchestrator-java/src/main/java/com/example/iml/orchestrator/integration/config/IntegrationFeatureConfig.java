package com.example.iml.orchestrator.integration.config;

import java.util.Map;

/**
 * Разбор секции {@code integration:} YAML: режимы пайплайна, логи стадий, сохранение кадров.
 */
public final class IntegrationFeatureConfig {

    private IntegrationFeatureConfig() {
    }

    public record SingleFrameBenchmarkConfig(boolean enabled, int referenceRepeats, int inspectionRepeats) {
    }

    public record ConveyorBenchmarkConfig(
            boolean enabled,
            int buckets,
            int photosPerBucket,
            int referenceRepeats,
            int cycleDelayMs,
            String productTypePrefix
    ) {
    }

    public record ContinuousInspectionConfig(boolean enabled, int cycleDelayMs) {
    }

    public record SaveCapturesConfig(boolean enabled, String relativeDir, float jpegQuality) {
    }

    public record TimingStagesLogConfig(boolean enabled, String relativePath) {
    }

    public static TimingStagesLogConfig parseTimingStagesLog(Map<String, Object> integration) {
        if (integration == null) {
            return new TimingStagesLogConfig(false, "logs/pipeline-stages.jsonl");
        }
        Object raw = integration.get("timing_stages_log");
        if (!(raw instanceof Map<?, ?> m)) {
            return new TimingStagesLogConfig(false, "logs/pipeline-stages.jsonl");
        }
        boolean enabled = YamlScalars.toBool(m.get("enabled"), false);
        String file = m.get("file") != null ? String.valueOf(m.get("file")) : "logs/pipeline-stages.jsonl";
        return new TimingStagesLogConfig(enabled, file);
    }

    public static SaveCapturesConfig parseSaveCaptures(Map<String, Object> integration) {
        String dir = "testimage";
        boolean enabled = true;
        float q = 0.92f;
        if (integration != null) {
            Object raw = integration.get("save_captures");
            if (raw instanceof Map<?, ?> m) {
                enabled = YamlScalars.toBool(m.get("enabled"), true);
                if (m.get("directory") != null) {
                    dir = String.valueOf(m.get("directory"));
                }
                q = (float) YamlScalars.toDouble(m.get("jpeg_quality"), 0.92);
            }
        }
        q = Math.min(1f, Math.max(0.05f, q));
        return new SaveCapturesConfig(enabled, dir, q);
    }

    public static ContinuousInspectionConfig parseContinuousInspection(Map<String, Object> integration) {
        if (integration == null) {
            return new ContinuousInspectionConfig(false, 0);
        }
        Object raw = integration.get("continuous_inspection");
        if (!(raw instanceof Map<?, ?> m)) {
            return new ContinuousInspectionConfig(false, 0);
        }
        boolean enabled = YamlScalars.toBool(m.get("enabled"), false);
        int delayMs = Math.max(0, YamlScalars.toInt(m.get("cycle_delay_ms"), 0));
        return new ContinuousInspectionConfig(enabled, delayMs);
    }

    public static SingleFrameBenchmarkConfig parseSingleFrameBenchmark(Map<String, Object> integration) {
        if (integration == null) {
            return new SingleFrameBenchmarkConfig(false, 1, 1);
        }
        Object raw = integration.get("single_frame_benchmark");
        if (!(raw instanceof Map<?, ?> m)) {
            return new SingleFrameBenchmarkConfig(false, 1, 1);
        }
        boolean enabled = YamlScalars.toBool(m.get("enabled"), false);
        int refR = Math.max(1, YamlScalars.toInt(m.get("reference_repeats"), 5));
        int inspR = Math.max(1, YamlScalars.toInt(m.get("inspection_repeats"), 5));
        return new SingleFrameBenchmarkConfig(enabled, refR, inspR);
    }

    public static ConveyorBenchmarkConfig parseConveyorBenchmark(Map<String, Object> integration) {
        if (integration == null) {
            return new ConveyorBenchmarkConfig(false, 0, 0, 1, 0, "bucket-");
        }
        Object raw = integration.get("conveyor_benchmark");
        if (!(raw instanceof Map<?, ?> m)) {
            return new ConveyorBenchmarkConfig(false, 0, 0, 1, 0, "bucket-");
        }
        boolean enabled = YamlScalars.toBool(m.get("enabled"), false);
        int buckets = Math.max(1, YamlScalars.toInt(m.get("buckets"), 100));
        int photos = Math.max(1, YamlScalars.toInt(m.get("photos_per_bucket"), 5));
        int refR = Math.max(1, YamlScalars.toInt(m.get("reference_repeats"), 5));
        int delay = Math.max(0, YamlScalars.toInt(m.get("cycle_delay_ms"), 0));
        String prefix = m.get("product_type_prefix") != null ? String.valueOf(m.get("product_type_prefix")) : "bucket-";
        return new ConveyorBenchmarkConfig(enabled, buckets, photos, refR, delay, prefix);
    }
}

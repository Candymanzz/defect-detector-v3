package com.example.iml.orchestrator.integration.config;

import java.util.Map;

/**
 * Разбор секции {@code integration:} YAML: режимы пайплайна, логи стадий, сохранение кадров.
 */
public final class IntegrationFeatureConfig {

    public enum InspectionTriggerMode {
        TIMER,
        CONTINUOUS,
        EXTERNAL
    }

    private IntegrationFeatureConfig() {
    }

    public static InspectionTriggerMode resolveInspectionTriggerMode(Map<String, Object> integration) {
        DevAutoTriggerStubConfig devStub = parseDevAutoTriggerStub(integration);
        ContinuousInspectionConfig continuous = parseContinuousInspection(integration);
        if (devStub.enabled()) {
            return InspectionTriggerMode.TIMER;
        }
        if (continuous.enabled()) {
            return InspectionTriggerMode.CONTINUOUS;
        }
        return InspectionTriggerMode.EXTERNAL;
    }

    public record ContinuousInspectionConfig(boolean enabled, int cycleDelayMs) {
    }

    /** Временная заглушка: периодический запуск цикла вместо ожидания внешнего триггера. */
    public record DevAutoTriggerStubConfig(boolean enabled, int intervalMs) {
    }

    public static ReferenceSource parseReferenceSource(Map<String, Object> integration) {
        if (integration == null) {
            return ReferenceSource.CAMERA;
        }
        return ReferenceSource.fromConfig(integration.get("reference_source"));
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

    public static int parseInspectionCycleTimeoutMs(Map<String, Object> integration) {
        if (integration == null) {
            return 4000;
        }
        return Math.max(500, YamlScalars.toInt(integration.get("inspection_cycle_timeout_ms"), 4000));
    }

    public static DevAutoTriggerStubConfig parseDevAutoTriggerStub(Map<String, Object> integration) {
        if (integration == null) {
            return new DevAutoTriggerStubConfig(false, 5000);
        }
        Object raw = integration.get("dev_auto_trigger_stub");
        if (!(raw instanceof Map<?, ?> m)) {
            return new DevAutoTriggerStubConfig(false, 5000);
        }
        boolean enabled = YamlScalars.toBool(m.get("enabled"), false);
        int intervalMs = Math.max(1000, YamlScalars.toInt(m.get("interval_ms"), 5000));
        return new DevAutoTriggerStubConfig(enabled, intervalMs);
    }

}

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

    /**
     * Optional capture-frame downscale in shared memory before geometry/python/UI stages.
     */
    public record CaptureFrameDownscaleConfig(
            boolean enabled,
            double scale,
            boolean applyToInspectionCapture,
            boolean applyToReferenceCapture,
            boolean applyToClientReferenceBundle
    ) {
        public static CaptureFrameDownscaleConfig disabled() {
            return new CaptureFrameDownscaleConfig(false, 1.0d, false, false, false);
        }
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

    /**
     * When true, external trigger runs camera capture even if {@code reference_source=client}
     * and no {@code client.reference_bundle} has been received yet (geometry/python skipped until reference exists).
     */
    public static boolean parseCaptureWithoutReference(Map<String, Object> integration) {
        if (integration == null) {
            return false;
        }
        Object explicit = integration.get("capture_without_reference");
        if (explicit != null) {
            return YamlScalars.toBool(explicit, false);
        }
        // По умолчанию: при эталоне от клиента снимаем по триггеру до reference_bundle.
        return parseReferenceSource(integration) == ReferenceSource.CLIENT;
    }

    public static CaptureFrameDownscaleConfig parseCaptureFrameDownscale(Map<String, Object> integration) {
        if (integration == null) {
            return CaptureFrameDownscaleConfig.disabled();
        }
        Object raw = integration.get("capture_frame_downscale");
        if (!(raw instanceof Map<?, ?> m)) {
            return CaptureFrameDownscaleConfig.disabled();
        }
        boolean enabled = YamlScalars.toBool(m.get("enabled"), false);
        double scale = YamlScalars.toDouble(m.get("scale"), 1.0d);
        if (!Double.isFinite(scale)) {
            scale = 1.0d;
        }
        scale = Math.max(0.1d, Math.min(1.0d, scale));
        boolean applyInspection = YamlScalars.toBool(m.get("apply_to_inspection_capture"), true);
        boolean applyReference = YamlScalars.toBool(m.get("apply_to_reference_capture"), true);
        boolean applyClientReference = YamlScalars.toBool(m.get("apply_to_client_reference_bundle"), true);
        if (!enabled || scale >= 0.999d) {
            return CaptureFrameDownscaleConfig.disabled();
        }
        return new CaptureFrameDownscaleConfig(
                true,
                scale,
                applyInspection,
                applyReference,
                applyClientReference
        );
    }

}

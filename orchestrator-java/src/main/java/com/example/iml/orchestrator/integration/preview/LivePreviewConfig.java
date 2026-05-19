package com.example.iml.orchestrator.integration.preview;

import com.example.iml.orchestrator.integration.config.IntegrationFeatureConfig;
import com.example.iml.orchestrator.integration.config.YamlScalars;

import java.util.Map;

/**
 * Живой preview-кадр для UI ({@code integration.live_preview} + {@code client.preview_max_fps}).
 * Приоритет интервала: {@code live_preview.interval_ms} → {@code dev_auto_trigger_stub.interval_ms} → FPS.
 */
public record LivePreviewConfig(boolean enabled, int maxFps, int intervalMs) {

    public static LivePreviewConfig fromRootYaml(Map<String, Object> root) {
        if (root == null) {
            return disabled();
        }
        boolean enabled = true;
        int fps = 10;
        int intervalMs = 0;

        Object client = root.get("client");
        if (client instanceof Map<?, ?> cm) {
            fps = Math.max(1, Math.min(30, YamlScalars.toInt(cm.get("preview_max_fps"), 10)));
        }

        @SuppressWarnings("unchecked")
        Map<String, Object> integration = root.get("integration") instanceof Map<?, ?> im
                ? (Map<String, Object>) im
                : null;

        if (integration != null) {
            Object raw = integration.get("live_preview");
            if (raw instanceof Map<?, ?> m) {
                enabled = YamlScalars.toBool(m.get("enabled"), true);
                int overrideFps = YamlScalars.toInt(m.get("preview_max_fps"), 0);
                if (overrideFps > 0) {
                    fps = Math.max(1, Math.min(30, overrideFps));
                }
                intervalMs = Math.max(0, YamlScalars.toInt(m.get("interval_ms"), 0));
            }
            if (intervalMs <= 0) {
                IntegrationFeatureConfig.DevAutoTriggerStubConfig stub =
                        IntegrationFeatureConfig.parseDevAutoTriggerStub(integration);
                if (stub.enabled()) {
                    intervalMs = stub.intervalMs();
                }
            }
        }

        return new LivePreviewConfig(enabled, fps, intervalMs);
    }

    public static LivePreviewConfig disabled() {
        return new LivePreviewConfig(false, 10, 0);
    }

    /** Период между кадрами preview (не чаще имитации триггера). */
    public int tickIntervalMs() {
        if (intervalMs > 0) {
            return intervalMs;
        }
        return Math.max(1000, 1000 / Math.max(1, maxFps));
    }
}
